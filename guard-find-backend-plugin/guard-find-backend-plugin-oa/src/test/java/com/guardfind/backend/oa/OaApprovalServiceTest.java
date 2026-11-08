/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.guardfind.backend.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaApprovalActionResp;
import com.guardfind.backend.oa.model.OaApprovalDetailResp;
import com.guardfind.backend.oa.model.OaAssigneeBindingReq;
import com.guardfind.backend.oa.model.OaDelegateRuleCreateReq;
import com.guardfind.backend.oa.model.OaDelegateRuleResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitResp;
import com.guardfind.backend.oa.model.OaTaskTransferReq;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaDelegateService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import com.guardfind.backend.oa.staff.OaTaskHandover;
import com.guardfind.backend.oa.staff.OaTaskHandoverService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.starter.core.exception.BusinessException;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 10.2 审批中心：待办/已办身份隔离、通过/驳回/撤回/退回、幂等回放与不可变审计。
 */
class OaApprovalServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final String PROCESS_KEY = "oaApproval";

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="两级审批" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="manager"/>
            <bpmn:userTask id="manager" name="主管审批" flowable:assignee="${manager}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="manager" targetRef="finance"/>
            <bpmn:userTask id="finance" name="财务审批" flowable:assignee="${finance}"/>
            <bpmn:sequenceFlow id="flow3" sourceRef="finance" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String PARALLEL_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="带并行网关的两级审批" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="manager"/>
            <bpmn:userTask id="manager" name="主管审批" flowable:assignee="${manager}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="manager" targetRef="fork"/>
            <bpmn:parallelGateway id="fork"/>
            <bpmn:sequenceFlow id="flow3" sourceRef="fork" targetRef="finance"/>
            <bpmn:userTask id="finance" name="财务审批" flowable:assignee="${finance}"/>
            <bpmn:sequenceFlow id="flow4" sourceRef="finance" targetRef="join"/>
            <bpmn:parallelGateway id="join"/>
            <bpmn:sequenceFlow id="flow5" sourceRef="join" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String CANDIDATE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="候选审批" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="manager"/>
            <bpmn:userTask id="manager" name="主管审批" flowable:candidateUsers="${manager}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="manager" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String COUNTERSIGN_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="比例会签" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="review"/>
            <bpmn:userTask id="review" name="会签审批" flowable:assignee="${reviewer}">
              <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                                                    flowable:collection="${reviewerList}"
                                                    flowable:elementVariable="reviewer">
                <bpmn:completionCondition xsi:type="bpmn:tFormalExpression">${nrOfCompletedInstances / nrOfInstances >= 0.5}</bpmn:completionCondition>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:userTask>
            <bpmn:sequenceFlow id="flow2" sourceRef="review" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String COUNTERSIGN_ALL_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="全票会签" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="review"/>
            <bpmn:userTask id="review" name="会签审批" flowable:assignee="${reviewer}">
              <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                                                    flowable:collection="${reviewerList}"
                                                    flowable:elementVariable="reviewer"/>
            </bpmn:userTask>
            <bpmn:sequenceFlow id="flow2" sourceRef="review" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String COUNTERSIGN_THREE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="三人比例会签" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="review"/>
            <bpmn:userTask id="review" name="会签审批" flowable:assignee="${reviewer}">
              <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                                                    flowable:collection="${reviewerList}"
                                                    flowable:elementVariable="reviewer">
                <bpmn:completionCondition xsi:type="bpmn:tFormalExpression">${nrOfCompletedInstances / nrOfInstances >= 0.6}</bpmn:completionCondition>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:userTask>
            <bpmn:sequenceFlow id="flow2" sourceRef="review" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String SCHEMA = """
        {"version":1,"fields":[
          {"id":"reason","key":"reason","label":"事由","type":"text","required":true}
        ]}
        """;

    @Test
    void todoAndDoneAreScopedToCurrentApprover() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            long applicationId = fixture.submit();

            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).hasSize(1);
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).isEmpty();
            assertThat(fixture.approvalService.todo(TENANT, 101, null, 1, 10).items()).isEmpty();
            assertThat(fixture.approvalService.todo(202, 102, null, 1, 10).items()).isEmpty();

            String taskId = fixture.approvalService.todo(TENANT, 102, null, 1, 10).items().get(0).taskId();
            OaApprovalDetailResp detail = fixture.approvalService.detail(TENANT, 102, taskId);
            assertThat(detail.pending()).isTrue();
            assertThat(detail.application().id()).isEqualTo(applicationId);
            assertThat(detail.application().formData()).containsEntry("reason", "出差");
            assertThatThrownBy(() -> fixture.approvalService.detail(TENANT, 103, taskId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
            assertThatThrownBy(() -> fixture.approvalService.detail(202, 102, taskId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权查看");
            assertThatThrownBy(() -> fixture.approvalService.approve(TENANT, 103, taskId, action("wrong-actor")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权处理");

            fixture.approvalService.approve(TENANT, 102, taskId, action("approve-1"));
            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).isEmpty();
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).hasSize(1);

            var done = fixture.approvalService.done(TENANT, 102, null, 1, 10);
            assertThat(done.total()).isEqualTo(1);
            assertThat(done.items().get(0).action()).isEqualTo("APPROVE");
            assertThat(done.items().get(0).applicationId()).isEqualTo(applicationId);
            assertThat(fixture.approvalService.done(TENANT, 103, null, 1, 10).total()).isZero();

            OaApprovalDetailResp handled = fixture.approvalService.detail(TENANT, 102, taskId);
            assertThat(handled.pending()).isFalse();
            assertThat(handled.application().timeline()).hasSize(2);
        });
    }

    @Test
    void approvesSequentiallyAndReplaysRequest() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            long applicationId = fixture.submit();
            String managerTask = fixture.taskId(102);

            OaApprovalActionResp first = fixture.approvalService.approve(TENANT, 102, managerTask, action("approve-1"));
            assertThat(first.status()).isEqualTo("RUNNING");
            assertThat(first.replayed()).isFalse();
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("RUNNING");

            OaApprovalActionResp replayed = fixture.approvalService
                .approve(TENANT, 102, managerTask, action("approve-1"));
            assertThat(replayed.replayed()).isTrue();
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_approval_audit WHERE action = 'APPROVE'", Integer.class))
                .isEqualTo(1);

            String financeTask = fixture.taskId(103);
            OaApprovalActionResp second = fixture.approvalService
                .approve(TENANT, 103, financeTask, action("approve-2"));
            assertThat(second.status()).isEqualTo("APPROVED");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("APPROVED");
            assertThat(fixture.jdbc
                .queryForObject("SELECT finished_time FROM oa_application WHERE id = ?", java.sql.Timestamp.class, applicationId))
                .isNotNull();
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();

            List<String> actions = fixture.jdbc
                .queryForList("SELECT action FROM oa_approval_audit WHERE application_id = ? ORDER BY id", String.class, applicationId);
            assertThat(actions).containsExactly("SUBMIT", "APPROVE", "APPROVE");
            assertThat(fixture.approvalService.done(TENANT, 103, null, 1, 10).total()).isEqualTo(1);
            assertThatThrownBy(() -> fixture.approvalService.approve(TENANT, 103, financeTask, action("approve-3")))
                .isInstanceOf(BusinessException.class);
        });
    }

    @Test
    void rejectRequiresReasonAndEndsApplication() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            long applicationId = fixture.submit();
            String taskId = fixture.taskId(102);

            assertThatThrownBy(() -> fixture.approvalService.reject(TENANT, 102, taskId, action("reject-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请填写驳回原因");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("RUNNING");

            OaApprovalActionResp result = fixture.approvalService
                .reject(TENANT, 102, taskId, new OaApprovalActionReq("reject-1", "预算不足"));
            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("REJECTED");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            assertThat(fixture.jdbc
                .queryForObject("SELECT reason FROM oa_approval_audit WHERE action = 'REJECT'", String.class))
                .isEqualTo("预算不足");
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).isEmpty();
        });
    }

    @Test
    void withdrawOnlyByApplicantBeforeApproval() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            long applicationId = fixture.submit();

            assertThatThrownBy(() -> fixture.approvalService.withdraw(TENANT, 102, applicationId, action("withdraw-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权撤回");

            OaApprovalActionResp result = fixture.approvalService
                .withdraw(TENANT, 101, applicationId, action("withdraw-1"));
            assertThat(result.status()).isEqualTo("WITHDRAWN");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("WITHDRAWN");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).isEmpty();

            OaApprovalActionResp replayed = fixture.approvalService
                .withdraw(TENANT, 101, applicationId, action("withdraw-1"));
            assertThat(replayed.replayed()).isTrue();
            assertThatThrownBy(() -> fixture.approvalService.withdraw(TENANT, 101, applicationId, action("withdraw-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅进行中");

            long secondId = fixture.submit("req-submit-2");
            String taskId = fixture.taskId(102);
            fixture.approvalService.approve(TENANT, 102, taskId, action("approve-2"));

            assertThatThrownBy(() -> fixture.approvalService.withdraw(TENANT, 101, secondId, action("withdraw-3")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有审批动作");
            assertThat(fixture.applicationStatus(secondId)).isEqualTo("RUNNING");
        });
    }

    @Test
    void returnsToPreviousHumanTaskAndRestoresAssignee() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            long applicationId = fixture.submit();

            String managerTask = fixture.taskId(102);
            assertThatThrownBy(() -> fixture.approvalService
                .returnToPrevious(TENANT, 102, managerTask, new OaApprovalActionReq("return-first", "先补充信息")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可退回的上一人工节点");

            fixture.approvalService.approve(TENANT, 102, managerTask, action("approve-1"));
            String financeTask = fixture.taskId(103);
            OaApprovalActionResp returned = fixture.approvalService
                .returnToPrevious(TENANT, 103, financeTask, new OaApprovalActionReq("return-1", "金额需复核"));
            assertThat(returned.status()).isEqualTo("RUNNING");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("RUNNING");

            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).isEmpty();
            var managerTodo = fixture.approvalService.todo(TENANT, 102, null, 1, 10).items();
            assertThat(managerTodo).hasSize(1);
            Task restored = fixture.taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskDefinitionKey("manager")
                .singleResult();
            assertThat(restored).isNotNull();
            assertThat(restored.getAssignee()).isEqualTo("102");
            assertThat(fixture.jdbc
                .queryForObject("SELECT reason FROM oa_approval_audit WHERE action = 'RETURN'", String.class))
                .isEqualTo("金额需复核");
        });
    }

    @Test
    void rejectsReturnWhenProcessContainsParallelBranching() {
        runner().run(context -> {
            Fixture fixture = fixture(context, PARALLEL_BPMN);
            fixture.submit();
            String managerTask = fixture.taskId(102);
            fixture.approvalService.approve(TENANT, 102, managerTask, action("approve-1"));
            String financeTask = fixture.taskId(103);

            assertThatThrownBy(() -> fixture.approvalService
                .returnToPrevious(TENANT, 103, financeTask, new OaApprovalActionReq("return-1", "退回")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("并行流程暂不支持退回");
            assertThat(fixture.taskService.createTaskQuery().taskId(financeTask).count()).isEqualTo(1);
        });
    }

    @Test
    void claimsCandidateTaskIdempotentlyAndRejectsSecondClaimer() {
        runner().run(context -> {
            Fixture fixture = fixture(context, CANDIDATE_BPMN);
            fixture.submitCandidate();
            String taskId = fixture.managerTaskId();

            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).hasSize(1);
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).hasSize(1);

            OaApprovalActionResp claimed = fixture.approvalService.claim(TENANT, 102, taskId, action("claim-1"));
            assertThat(claimed.status()).isEqualTo("RUNNING");
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).isEmpty();

            OaApprovalActionResp replay = fixture.approvalService.claim(TENANT, 102, taskId, action("claim-1"));
            assertThat(replay.replayed()).isTrue();
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_approval_audit WHERE action = 'CLAIM'", Integer.class))
                .isEqualTo(1);
            assertThat(fixture.taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee())
                .isEqualTo("102");

            assertThatThrownBy(() -> fixture.approvalService.claim(TENANT, 103, taskId, action("claim-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权领取");
            assertThatThrownBy(() -> fixture.approvalService.approve(TENANT, 103, taskId, action("approve-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权处理");
        });
    }

    @Test
    void transferRequiresClaimAndMovesTaskToActiveEmployee() {
        runner().run(context -> {
            Fixture fixture = fixture(context, CANDIDATE_BPMN);
            fixture.submitCandidate();
            String taskId = fixture.managerTaskId();

            assertThatThrownBy(() -> fixture.approvalService
                .transfer(TENANT, 102, taskId, new OaTaskTransferReq("transfer-1", 103L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先领取任务后再转交");

            fixture.approvalService.claim(TENANT, 102, taskId, action("claim-1"));
            assertThatThrownBy(() -> fixture.approvalService
                .transfer(TENANT, 102, taskId, new OaTaskTransferReq("transfer-2", 999L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是本租户在职员工");
            assertThatThrownBy(() -> fixture.approvalService
                .transfer(TENANT, 102, taskId, new OaTaskTransferReq("transfer-3", 102L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能转交给自己");

            OaApprovalActionResp result = fixture.approvalService
                .transfer(TENANT, 102, taskId, new OaTaskTransferReq("transfer-4", 103L, "交给同事"));
            assertThat(result.status()).isEqualTo("RUNNING");
            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).isEmpty();
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).hasSize(1);
            assertThat(fixture.taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee())
                .isEqualTo("103");
            assertThat(fixture.jdbc
                .queryForObject("SELECT original_assignee_id FROM oa_approval_audit WHERE action = 'TRANSFER'", Long.class))
                .isEqualTo(102L);
        });
    }

    @Test
    void delegateSeesAndActsOnBehalfOfDelegator() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            fixture.submit();
            LocalDateTime now = LocalDateTime.now();
            OaDelegateRuleResp rule = fixture.delegateService.create(TENANT, 102, new OaDelegateRuleCreateReq(103L, now
                .minusHours(1), now.plusHours(2), "出差"));
            assertThat(rule.delegateName()).isEqualTo("Carol");

            var proxyTodo = fixture.approvalService.todo(TENANT, 103, null, 1, 10).items();
            assertThat(proxyTodo).hasSize(1);
            assertThat(proxyTodo.get(0).proxyForId()).isEqualTo(102L);
            assertThat(proxyTodo.get(0).proxyForName()).isEqualTo("Bob");

            String taskId = proxyTodo.get(0).taskId();
            OaApprovalDetailResp detail = fixture.approvalService.detail(TENANT, 103, taskId);
            assertThat(detail.pending()).isTrue();
            assertThat(detail.proxyForId()).isEqualTo(102L);
            assertThatThrownBy(() -> fixture.approvalService.claim(TENANT, 103, taskId, action("claim-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权领取");

            fixture.approvalService.approve(TENANT, 103, taskId, action("approve-proxy"));
            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).isEmpty();
            assertThat(fixture.approvalService.done(TENANT, 103, null, 1, 10).total()).isEqualTo(1);
            assertThat(fixture.jdbc
                .queryForObject("SELECT operator_id FROM oa_approval_audit WHERE action = 'APPROVE'", Long.class))
                .isEqualTo(103L);
            assertThat(fixture.jdbc
                .queryForObject("SELECT original_assignee_id FROM oa_approval_audit WHERE action = 'APPROVE'", Long.class))
                .isEqualTo(102L);
            assertThat(fixture.jdbc
                .queryForObject("SELECT detail_json FROM oa_approval_audit WHERE action = 'APPROVE'", String.class))
                .contains("\"proxyForId\":102");
        });
    }

    @Test
    void delegateRulesRejectOverlapAndCycle() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            LocalDateTime base = LocalDateTime.now();
            OaDelegateRuleResp first = fixture.delegateService
                .create(TENANT, 101, new OaDelegateRuleCreateReq(102L, base.plusHours(1), base.plusHours(2), null));
            assertThat(first.status()).isEqualTo("ENABLED");

            assertThatThrownBy(() -> fixture.delegateService.create(TENANT, 101, new OaDelegateRuleCreateReq(102L, base
                .plusMinutes(90), base.plusHours(3), null))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有代理规则");
            assertThatThrownBy(() -> fixture.delegateService.create(TENANT, 102, new OaDelegateRuleCreateReq(101L, base
                .plusHours(1), base.plusHours(2), null))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("环路");
            assertThatThrownBy(() -> fixture.delegateService.create(TENANT, 101, new OaDelegateRuleCreateReq(101L, base
                .plusHours(1), base.plusHours(2), null))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("代理给自己");
            assertThatThrownBy(() -> fixture.delegateService
                .create(TENANT, 101, new OaDelegateRuleCreateReq(103L, base, base.plusDays(91), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("90 天");

            assertThat(fixture.delegateService.list(TENANT, 101)).hasSize(1);
            assertThatThrownBy(() -> fixture.delegateService.delete(TENANT, 102, first.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权操作");
            fixture.delegateService.delete(TENANT, 101, first.id());
            assertThat(fixture.delegateService.list(TENANT, 101)).isEmpty();
        });
    }

    @Test
    void handoverMovesCandidateTasksAndClearsOriginalIdentity() {
        runner().run(context -> {
            Fixture fixture = fixture(context, CANDIDATE_BPMN);
            fixture.submitCandidate();
            String taskId = fixture.managerTaskId();

            assertThat(fixture.handover.transferRunningTasks(TENANT, 7, 102, 103, "离职转交")).isEqualTo(1);
            assertThat(fixture.approvalService.todo(TENANT, 102, null, 1, 10).items()).isEmpty();
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).hasSize(1);
            assertThat(fixture.taskService.createTaskQuery().taskId(taskId).singleResult().getAssignee())
                .isEqualTo("103");
            assertThat(fixture.jdbc
                .queryForObject("SELECT original_assignee_id FROM oa_approval_audit WHERE action = 'TRANSFER'", Long.class))
                .isEqualTo(102L);
            assertThat(fixture.jdbc
                .queryForObject("SELECT request_id FROM oa_approval_audit WHERE action = 'TRANSFER'", String.class))
                .startsWith("HANDOVER-");
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_outbox WHERE event_type = 'TASK_ASSIGNED' AND dedup_key LIKE 'HANDOVER:%' AND payload LIKE '%TRANSFERRED%' AND payload LIKE '%103%'", Integer.class))
                .isEqualTo(1);
            assertThatThrownBy(() -> fixture.handover.transferRunningTasks(TENANT, 7, 103, 999, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是本租户在职员工");
        });
    }

    @Test
    void countersignRatioCompletesAndCancelsRemainingTasks() {
        runner().run(context -> {
            Fixture fixture = fixture(context, COUNTERSIGN_BPMN);
            long applicationId = fixture.submitCountersign("req-countersign-1", "APPROVER");
            assertThat(fixture.reviewerTasks()).hasSize(2);
            String cancelledTaskId = fixture.taskId(103);
            String taskId = fixture.taskId(102);

            OaApprovalActionResp result = fixture.approvalService.approve(TENANT, 102, taskId, action("cs-approve-1"));

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("APPROVED");
            assertThat(fixture.taskService.createTaskQuery().taskId(cancelledTaskId).count()).isZero();
            assertThat(fixture.approvalService.todo(TENANT, 103, null, 1, 10).items()).isEmpty();
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            String detail = fixture.jdbc
                .queryForObject("SELECT detail_json FROM oa_approval_audit WHERE action = 'APPROVE'", String.class);
            assertThat(detail).contains("\"countersign\":true", "\"cancelReason\"", cancelledTaskId);
        });
    }

    @Test
    void countersignAllVoteWaitsForEveryApproverAndReplaysRequest() {
        runner().run(context -> {
            Fixture fixture = fixture(context, COUNTERSIGN_ALL_BPMN);
            long applicationId = fixture.submitCountersign("req-countersign-all", "APPROVER");
            assertThat(fixture.reviewerTasks()).hasSize(2);
            String firstTask = fixture.taskId(102);

            OaApprovalActionResp first = fixture.approvalService.approve(TENANT, 102, firstTask, action("cs-all-1"));
            assertThat(first.status()).isEqualTo("RUNNING");
            assertThat(fixture.reviewerTasks()).hasSize(1);
            OaApprovalActionResp replay = fixture.approvalService.approve(TENANT, 102, firstTask, action("cs-all-1"));
            assertThat(replay.replayed()).isTrue();
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_approval_audit WHERE action = 'APPROVE'", Integer.class))
                .isEqualTo(1);

            String lastTask = fixture.taskId(103);
            OaApprovalActionResp second = fixture.approvalService.approve(TENANT, 103, lastTask, action("cs-all-2"));
            assertThat(second.status()).isEqualTo("APPROVED");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("APPROVED");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
        });
    }

    @Test
    void countersignRejectVetoesAndCancelsRemainingTasks() {
        runner().run(context -> {
            Fixture fixture = fixture(context, COUNTERSIGN_ALL_BPMN);
            long applicationId = fixture.submitCountersign("req-countersign-veto", "APPROVER");
            String taskId = fixture.taskId(102);
            String otherTaskId = fixture.taskId(103);

            OaApprovalActionResp result = fixture.approvalService
                .reject(TENANT, 102, taskId, new OaApprovalActionReq("cs-veto-1", "资料不全"));

            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("REJECTED");
            assertThat(fixture.taskService.createTaskQuery().taskId(otherTaskId).count()).isZero();
            String detail = fixture.jdbc
                .queryForObject("SELECT detail_json FROM oa_approval_audit WHERE action = 'REJECT'", String.class);
            assertThat(detail).contains("\"veto\":true", "\"countersign\":true", otherTaskId, "\"cancelReason\"");
            assertThatThrownBy(() -> fixture.approvalService.approve(TENANT, 103, otherTaskId, action("cs-veto-late")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在或无权处理");
        });
    }

    @Test
    void countersignRatioCancelsOnlyRemainingTasksAfterThreshold() {
        runner().run(context -> {
            Fixture fixture = fixture(context, COUNTERSIGN_THREE_BPMN);
            fixture.submitCountersign("req-countersign-three", "REVIEWERS");
            assertThat(fixture.reviewerTasks()).hasSize(3);

            fixture.approvalService.approve(TENANT, 102, fixture.taskId(102), action("cs-three-1"));
            assertThat(fixture.reviewerTasks()).hasSize(2);
            String remainingTaskId = fixture.taskId(101);

            OaApprovalActionResp result = fixture.approvalService.approve(TENANT, 103, fixture
                .taskId(103), action("cs-three-2"));
            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(fixture.taskService.createTaskQuery().taskId(remainingTaskId).count()).isZero();
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_approval_audit WHERE action = 'APPROVE'", Integer.class))
                .isEqualTo(2);
        });
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-approval-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context, String bpmn) {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        seedAccounts(jdbc);
        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-approval")
            .tenantId(Long.toString(TENANT))
            .addString("oa-approval.bpmn20.xml", bpmn)
            .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(PROCESS_KEY)
            .singleResult();
        seedRelease(jdbc, bpmn, definition.getId());

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        HistoryService historyService = context.getBean(HistoryService.class);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaEngineTemplate engineTemplate = new OaEngineTemplate(runtimeService, taskService);
        OaProcessLauncher launcher = new OaProcessLauncher(engineTemplate, new OaAssigneeResolver(new StubStaffDirectory()));
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        OaApprovalEngine approvalEngine = new OaApprovalEngine(runtimeService, taskService, historyService, repositoryService);
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), approvalEngine);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), new ObjectMapper());
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, approvalEngine, outboxService, new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService), new OaDraftRepository(jdbc), new ObjectMapper(), transactionManager);
        StubStaffDirectory directory = new StubStaffDirectory();
        OaDelegateRepository delegateRepository = new OaDelegateRepository(jdbc);
        OaApprovalRepository approvalRepository = new OaApprovalRepository(jdbc);
        OaApprovalService approvalService = new OaApprovalService(approvalRepository, launchRepository, delegateRepository, directory, approvalEngine, outboxService, new ObjectMapper(), transactionManager);
        OaDelegateService delegateService = new OaDelegateService(delegateRepository, directory);
        OaTaskHandover handover = new OaTaskHandoverService(approvalEngine, approvalRepository, directory, outboxService, new ObjectMapper());
        return new Fixture(jdbc, launchService, approvalService, delegateService, handover, runtimeService, taskService);
    }

    private static void seedAccounts(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (101, 101, 'alice', 'Alice', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (102, 101, 'bob', 'Bob', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (103, 101, 'carol', 'Carol', 20, 1, 0)");
    }

    private static void seedRelease(JdbcTemplate jdbc, String bpmn, String engineDefinitionId) {
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (1, 101, 'expense', '报销流程', 'finance', '说明', 'PUBLISHED', 10, 1, 20, 30, 0, 7, CURRENT_TIMESTAMP, 0)
            """);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (10, 101, 1, 1, ?, ?, ?, 'oaApproval', 1, 20, 30, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, bpmn, "a"
            .repeat(64), engineDefinitionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (20, 101, 'expense_form', '报销单', 'PUBLISHED', 0)");
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (30, 101, 20, 1, ?, ?, 1, 7, CURRENT_TIMESTAMP)
            """, SCHEMA, "b".repeat(64));
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE oa_cc_receipt (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT NOT NULL, process_instance_id VARCHAR(64), receiver_id BIGINT NOT NULL, receiver_name VARCHAR(64), source_task_id VARCHAR(64), status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', read_time TIMESTAMP, create_time TIMESTAMP NOT NULL, CONSTRAINT uk_cc UNIQUE (tenant_id, application_id, receiver_id))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE oa_employee (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, employment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE oa_process_model (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, model_key VARCHAR(64) NOT NULL,
              name VARCHAR(100) NOT NULL, category VARCHAR(64), description VARCHAR(500), status VARCHAR(20) NOT NULL,
              current_release_id BIGINT, current_release_version INT NOT NULL DEFAULT 0, form_id BIGINT,
              form_version_id BIGINT, version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_process_release (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, model_id BIGINT NOT NULL, release_version INT NOT NULL,
              bpmn_xml CLOB NOT NULL, xml_digest VARCHAR(64) NOT NULL, engine_definition_id VARCHAR(128) NOT NULL,
              process_definition_key VARCHAR(128) NOT NULL, process_definition_version INT NOT NULL, form_id BIGINT,
              form_version_id BIGINT, form_version INT, launch_scope VARCHAR(2000), status VARCHAR(20) NOT NULL,
              published_by BIGINT NOT NULL, publish_time TIMESTAMP NOT NULL, remark VARCHAR(500)
            )
            """);
        jdbc.execute("CREATE TABLE oa_form (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, form_key VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE oa_form_version (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, form_id BIGINT NOT NULL, version INT NOT NULL,
              schema_json CLOB NOT NULL, schema_digest VARCHAR(64) NOT NULL, field_count INT NOT NULL DEFAULT 0,
              published_by BIGINT NOT NULL, publish_time TIMESTAMP NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_application (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              business_type VARCHAR(64) NOT NULL, business_key VARCHAR(128) NOT NULL, title VARCHAR(200) NOT NULL,
              applicant_id BIGINT NOT NULL, applicant_name VARCHAR(64), applicant_dept_id BIGINT,
              applicant_dept_name VARCHAR(100), release_id BIGINT NOT NULL, form_version_id BIGINT,
              form_version INT, form_data CLOB, process_instance_id VARCHAR(64), status VARCHAR(20) NOT NULL,
              submitted_time TIMESTAMP, finished_time TIMESTAMP, version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_request_dedup (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              actor_id BIGINT NOT NULL, command VARCHAR(64) NOT NULL, request_id VARCHAR(64) NOT NULL,
              status VARCHAR(20) NOT NULL, result_json CLOB, create_time TIMESTAMP NOT NULL,
              expire_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_dedup UNIQUE (tenant_id, actor_id, command, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_approval_audit (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, process_instance_id VARCHAR(64) NOT NULL, task_id VARCHAR(64),
              action VARCHAR(32) NOT NULL, operator_id BIGINT NOT NULL, operator_name VARCHAR(64),
              original_assignee_id BIGINT, reason VARCHAR(500), request_id VARCHAR(64) NOT NULL,
              detail_json CLOB, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_audit_request UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_delegate_rule (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              delegator_id BIGINT NOT NULL, delegate_id BIGINT NOT NULL, start_time TIMESTAMP NOT NULL,
              end_time TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', reason VARCHAR(500),
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT,
              update_time TIMESTAMP, deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_outbox (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              event_id VARCHAR(64) NOT NULL, aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(128) NOT NULL,
              event_type VARCHAR(64) NOT NULL, payload CLOB NOT NULL, dedup_key VARCHAR(160),
              status VARCHAR(20) NOT NULL DEFAULT 'PENDING', retry_count INT NOT NULL DEFAULT 0,
              next_retry_time TIMESTAMP, last_error VARCHAR(1000), create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP, sent_time TIMESTAMP,
              CONSTRAINT uk_event_id UNIQUE (event_id), CONSTRAINT uk_outbox_dedup UNIQUE (tenant_id, dedup_key)
            )
            """);
    }

    /** 员工目录桩：101/102/103 在职。 */
    private static final class StubStaffDirectory implements OaStaffDirectory {

        @Override
        public boolean activeEmployee(long tenantId, long userId) {
            return tenantId == TENANT && (userId == 101 || userId == 102 || userId == 103);
        }

        @Override
        public Long departmentId(long tenantId, long userId) {
            return userId == 103 ? 20L : 10L;
        }

        @Override
        public Long supervisorId(long tenantId, long userId) {
            return null;
        }

        @Override
        public boolean enabledPosition(long tenantId, String code) {
            return tenantId == TENANT && ("APPROVER".equals(code) || "REVIEWERS".equals(code));
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            return switch (code) {
                case "APPROVER" -> List.of(102L, 103L);
                case "REVIEWERS" -> List.of(101L, 102L, 103L);
                default -> List.of();
            };
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(102L);
        }
    }

    private record Fixture(JdbcTemplate jdbc, OaLaunchService launchService, OaApprovalService approvalService,
                           OaDelegateService delegateService, OaTaskHandover handover, RuntimeService runtimeService,
                           TaskService taskService) {

        private long submit() {
            return submit("req-submit-1");
        }

        private long submit(String requestId) {
            OaLaunchSubmitResp resp = launchService.submit(TENANT, 101, 10, new OaLaunchSubmitReq(requestId, "差旅报销", Map
                .of("reason", "出差"), List
                    .of(new OaAssigneeBindingReq("manager", "EMPLOYEE", "102"), new OaAssigneeBindingReq("finance", "EMPLOYEE", "103"))));
            return resp.applicationId();
        }

        private long submitCandidate() {
            OaLaunchSubmitResp resp = launchService
                .submit(TENANT, 101, 10, new OaLaunchSubmitReq("req-submit-candidate", "候选审批", Map
                    .of("reason", "出差"), List.of(new OaAssigneeBindingReq("manager", "POSITION", "APPROVER"))));
            return resp.applicationId();
        }

        private long submitCountersign(String requestId, String position) {
            OaLaunchSubmitResp resp = launchService.submit(TENANT, 101, 10, new OaLaunchSubmitReq(requestId, "会签审批", Map
                .of("reason", "出差"), List.of(new OaAssigneeBindingReq("reviewer", "POSITION", position))));
            return resp.applicationId();
        }

        private List<Task> reviewerTasks() {
            return taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskDefinitionKey("review")
                .orderByTaskAssignee()
                .asc()
                .list();
        }

        private String managerTaskId() {
            Task task = taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskDefinitionKey("manager")
                .singleResult();
            assertThat(task).isNotNull();
            return task.getId();
        }

        private String taskId(long assignee) {
            Task task = taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskAssignee(Long.toString(assignee))
                .singleResult();
            assertThat(task).isNotNull();
            return task.getId();
        }

        private String applicationStatus(long applicationId) {
            return jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId);
        }
    }
}
