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
import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.business.OaBusinessHooks;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.contract.OaContractHook;
import com.guardfind.backend.oa.contract.OaSealRequestHook;
import com.guardfind.backend.oa.document.OaDocumentHook;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaContractArchiveReq;
import com.guardfind.backend.oa.model.OaContractDetailResp;
import com.guardfind.backend.oa.model.OaContractResp;
import com.guardfind.backend.oa.model.OaContractSignReq;
import com.guardfind.backend.oa.model.OaDocumentDetailResp;
import com.guardfind.backend.oa.model.OaDocumentInboxResp;
import com.guardfind.backend.oa.model.OaDocumentReadResp;
import com.guardfind.backend.oa.model.OaDocumentReceiptResp;
import com.guardfind.backend.oa.model.OaDocumentResp;
import com.guardfind.backend.oa.model.OaDocumentRevokeReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLinkedFileResp;
import com.guardfind.backend.oa.model.OaSealExecuteReq;
import com.guardfind.backend.oa.model.OaSealReq;
import com.guardfind.backend.oa.model.OaSealRequestResp;
import com.guardfind.backend.oa.model.OaSealResp;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaContractRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDocumentRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaContractService;
import com.guardfind.backend.oa.service.OaDocumentService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 14.x 合同用印与公文知识：审批只到待签署/待用印，签署归档与用印由对应经办人幂等登记；公文按受众快照生成回执。
 */
class OaContractDocumentServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long LEGAL = 103;
    private static final long FINANCE = 104;
    private static final long GM = 104;
    private static final long CONTRACT_RELEASE = 41;
    private static final long SEAL_RELEASE = 42;
    private static final long DOCUMENT_RELEASE = 43;
    private static final LocalDate TODAY = LocalDate.now();

    @Test
    void templatesPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture.contractService.sealTemplate(TENANT, GM)).hasMessageContaining("没有启用的印章");
            fixture.seal("GZ", "公章", "OFFICIAL");
            String contractSchema = fixture.contractService.contractSchema();
            String sealSchema = fixture.contractService.sealSchema(fixture.contractService.seals(TENANT, "ENABLED"));
            String documentSchema = fixture.documentService.documentSchema(new OaDocumentRepository(fixture.jdbc)
                .depts(TENANT));
            for (String schema : List.of(contractSchema, sealSchema, documentSchema)) {
                assertThat(new OaFormSchemaValidator().validate(schema, true).valid()).isTrue();
            }
            assertThat(fixture.hooks.validateForm(OaContractHook.BUSINESS_TYPE, contractSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaSealRequestHook.BUSINESS_TYPE, sealSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaDocumentHook.BUSINESS_TYPE, documentSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaContractHook.BUSINESS_TYPE, sealSchema)).isNotEmpty();
            assertThat(fixture.hooks.validateForm(OaDocumentHook.BUSINESS_TYPE, contractSchema)).isNotEmpty();

            OaBpmnValidationResult contract = new OaBpmnValidator().validate(OaContractService.CONTRACT_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(contract.errors()).isEmpty();
            assertThat(contract.processKey()).isEqualTo(OaContractService.CONTRACT_MODEL_KEY);
            assertThat(contract.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaContractHook.VAR_LEGAL_REVIEWER, OaContractHook.VAR_FINANCE_REVIEWER, OaContractHook.VAR_FINAL_APPROVER);
            OaBpmnValidationResult seal = new OaBpmnValidator().validate(OaContractService.SEAL_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(seal.errors()).isEmpty();
            assertThat(seal.processKey()).isEqualTo(OaContractService.SEAL_MODEL_KEY);
            assertThat(seal.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaSealRequestHook.VAR_FINAL_APPROVER);
            OaBpmnValidationResult document = new OaBpmnValidator().validate(OaDocumentService.BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(document.errors()).isEmpty();
            assertThat(document.processKey()).isEqualTo(OaDocumentService.MODEL_KEY);
            assertThat(document.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaDocumentHook.VAR_FINAL_APPROVER);
        });
    }

    @Test
    void contractApprovalSignArchiveAndRevision() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            seedFile(fixture.jdbc, 601, APPLICANT, "合同.pdf");
            seedFile(fixture.jdbc, 602, APPLICANT, "合同-修订.pdf");
            seedFile(fixture.jdbc, 603, APPLICANT, "合同-再改.pdf");
            assertThatThrownBy(() -> fixture.submitContract("c0", contract("采购框架", "1000", TODAY, TODAY
                .minusDays(1), 601L, null, null))).hasMessageContaining("结束日期不能早于开始日期");
            assertThatThrownBy(() -> fixture
                .submitContract("c0", contract("采购框架", "1000", TODAY, TODAY, 601L, "HT-NOPE", "改价")))
                .hasMessageContaining("合同编号 HT-NOPE 不存在");

            long app = fixture.submitContract("c1", contract("采购框架", "200000", TODAY, TODAY
                .plusYears(1), 601L, null, null));
            long id = fixture.contractId(app);
            OaContractResp approving = fixture.contractService.contractDetail(TENANT, null, id).contract();
            assertThat(approving.status()).isEqualTo(OaContractHook.STATUS_APPROVING);
            assertThat(approving.contractNo()).matches("HT\\d{6}\\d{4,}");
            assertThat(fixture.variable(app, OaContractHook.VAR_VALUE)).isEqualTo(200000.0);
            assertThatThrownBy(() -> fixture.contractService.sign(TENANT, LEGAL, id, sign("s0", TODAY)))
                .hasMessageContaining("合同尚未审批通过，不能签署");

            fixture.approve(SUPERVISOR, app, "a1");
            assertThat(fixture.task(app).getTaskDefinitionKey()).isEqualTo("legalApprove");
            fixture.approve(LEGAL, app, "a2");
            assertThat(fixture.task(app).getTaskDefinitionKey()).isEqualTo("financeApprove");
            fixture.approve(FINANCE, app, "a3");
            assertThat(fixture.task(app).getTaskDefinitionKey()).isEqualTo("gmApprove");
            fixture.approve(GM, app, "a4");
            assertThat(fixture.applicationStatus(app)).isEqualTo("APPROVED");
            OaContractDetailResp approved = fixture.contractService.contractDetail(TENANT, APPLICANT, id);
            assertThat(approved.contract().status()).isEqualTo(OaContractHook.STATUS_PENDING_SIGN);
            assertThat(approved.versions()).singleElement()
                .satisfies(version -> assertThat(version.status()).isEqualTo(OaContractHook.VERSION_APPROVED));
            assertThat(approved.files()).extracting(OaLinkedFileResp::fileId).containsExactly(601L);
            assertThatThrownBy(() -> fixture.contractService.contractDetail(TENANT, SUPERVISOR, id))
                .hasMessageContaining("合同不存在或无权查看");
            assertThatThrownBy(() -> fixture.contractService.contractDetail(102, null, id))
                .hasMessageContaining("合同不存在或无权查看");

            assertThatThrownBy(() -> fixture.submitContract("c2", contract("采购框架", "50000", TODAY, TODAY
                .plusYears(1), 602L, approving.contractNo(), null))).hasMessageContaining("请填写修订说明");
            long revision = fixture.submitContract("c2", contract("采购框架（修订）", "50000", TODAY, TODAY
                .plusYears(1), 602L, approving.contractNo(), "金额下调"));
            assertThat(fixture.contractId(revision)).isEqualTo(id);
            OaContractResp revising = fixture.contractService.contractDetail(TENANT, null, id).contract();
            assertThat(revising.status()).isEqualTo(OaContractHook.STATUS_APPROVING);
            assertThat(revising.currentVersion()).isEqualTo(2);
            assertThat(revising.currentApplicationId()).isEqualTo(revision);
            assertThatThrownBy(() -> fixture.submitContract("c3", contract("采购框架", "1", TODAY, TODAY, 603L, approving
                .contractNo(), "再改"))).hasMessageContaining("合同当前状态为审批中，不能发起修订");
            fixture.approve(SUPERVISOR, revision, "b1");
            fixture.approve(LEGAL, revision, "b2");
            fixture.approve(FINANCE, revision, "b3");
            assertThat(fixture.applicationStatus(revision)).isEqualTo("APPROVED");

            OaContractDetailResp revised = fixture.contractService.contractDetail(TENANT, null, id);
            assertThat(revised.contract().status()).isEqualTo(OaContractHook.STATUS_PENDING_SIGN);
            assertThat(revised.contract().title()).isEqualTo("采购框架（修订）");
            assertThat(revised.contract().amount()).isEqualByComparingTo("50000");
            assertThat(revised.versions()).extracting(version -> version.versionNo()).containsExactly(2, 1);
            assertThat(revised.versions().get(0).changeNote()).isEqualTo("金额下调");
            assertThat(revised.files()).extracting(OaLinkedFileResp::fileId).containsExactlyInAnyOrder(601L, 602L);
            assertThat(fixture.contractService.contractFile(TENANT, APPLICANT, id, 602).fileName())
                .isEqualTo("合同-修订.pdf");
            assertThatThrownBy(() -> fixture.contractService.contractFile(TENANT, SUPERVISOR, id, 602))
                .hasMessageContaining("合同不存在或无权查看");
            assertThatThrownBy(() -> fixture.contractService.contractFile(TENANT, null, id, 999))
                .isInstanceOf(BusinessException.class);

            assertThatThrownBy(() -> fixture.contractService.sign(TENANT, APPLICANT, id, sign("s1", TODAY)))
                .hasMessageContaining("不能为本人经办的合同登记签署");
            assertThatThrownBy(() -> fixture.contractService.sign(TENANT, LEGAL, id, sign("s1", TODAY.plusDays(1))))
                .hasMessageContaining("签署日期不能晚于今天");
            assertThatThrownBy(() -> fixture.contractService.archive(TENANT, LEGAL, id, archive("ar0", "DA-001")))
                .hasMessageContaining("签署后才能归档");
            OaContractResp signed = fixture.contractService.sign(TENANT, LEGAL, id, sign("s1", TODAY));
            assertThat(signed.status()).isEqualTo(OaContractHook.STATUS_SIGNED);
            assertThat(signed.signedByName()).isEqualTo("Carol");
            assertThat(signed.signatories()).isEqualTo("甲方张三 / 乙方李四");
            assertThat(fixture.contractService.sign(TENANT, LEGAL, id, sign("s1", TODAY)).status())
                .isEqualTo(OaContractHook.STATUS_SIGNED);
            assertThatThrownBy(() -> fixture.contractService.sign(TENANT, LEGAL, id, sign("s2", TODAY)))
                .hasMessageContaining("合同已签署");
            assertThat(fixture.outbox(OaContractService.EVENT_CONTRACT_SIGNED)).isEqualTo(1);

            OaContractResp archived = fixture.contractService.archive(TENANT, LEGAL, id, archive("ar1", "DA-001"));
            assertThat(archived.status()).isEqualTo(OaContractHook.STATUS_ARCHIVED);
            assertThat(archived.archiveNo()).isEqualTo("DA-001");
            assertThat(archived.archivedByName()).isEqualTo("Carol");
            assertThat(fixture.contractService.archive(TENANT, LEGAL, id, archive("ar1", "DA-001")).status())
                .isEqualTo(OaContractHook.STATUS_ARCHIVED);
            assertThatThrownBy(() -> fixture.contractService.archive(TENANT, LEGAL, id, archive("ar2", "DA-002")))
                .hasMessageContaining("合同已归档");
            assertThatThrownBy(() -> fixture.submitContract("c4", contract("采购框架", "1", TODAY, TODAY, 603L, approving
                .contractNo(), "再改"))).hasMessageContaining("合同当前状态为已归档，不能发起修订");

            assertThat(fixture.contractService.contracts(TENANT, "ARCHIVED", "修订", null, null, 1, 10)).hasSize(1);
            assertThat(fixture.contractService.contracts(TENANT, null, null, SUPERVISOR, null, 1, 10)).isEmpty();
            assertThat(fixture.contractService.countContracts(TENANT, null, null, APPLICANT, null)).isEqualTo(1);
            assertThat(fixture.contractService.countContracts(102, null, null, null, null)).isZero();
            assertThatThrownBy(() -> fixture.contractService.contracts(TENANT, "BOGUS", null, null, null, 1, 10))
                .hasMessageContaining("合同状态无效");
        });
    }

    @Test
    void contractRejectRevisionAndExpiryReminderDedup() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            seedFile(fixture.jdbc, 611, APPLICANT, "a.pdf");
            seedFile(fixture.jdbc, 612, APPLICANT, "b.pdf");
            seedFile(fixture.jdbc, 613, APPLICANT, "c.pdf");
            seedFile(fixture.jdbc, 614, APPLICANT, "d.pdf");

            long rejected = fixture.submitContract("c1", contract("服务协议", "8000", TODAY, TODAY
                .plusDays(10), 611L, null, null));
            long id = fixture.contractId(rejected);
            String contractNo = fixture.contractService.contractDetail(TENANT, null, id).contract().contractNo();
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(rejected)
                .getId(), new OaApprovalActionReq("r1", "条款不清"));
            OaContractDetailResp closed = fixture.contractService.contractDetail(TENANT, null, id);
            assertThat(closed.contract().status()).isEqualTo(OaContractHook.STATUS_CLOSED);
            assertThat(closed.versions().get(0).status()).isEqualTo("REJECTED");
            assertThatThrownBy(() -> fixture.contractService.sign(TENANT, LEGAL, id, sign("s0", TODAY)))
                .hasMessageContaining("合同已关闭，不能签署");

            long withdrawn = fixture.submitContract("c2", contract("服务协议", "8000", TODAY, TODAY
                .plusDays(10), 612L, contractNo, "补充条款"));
            fixture.approvalService.withdraw(TENANT, APPLICANT, withdrawn, action("w1"));
            assertThat(fixture.contractService.contractDetail(TENANT, null, id).contract().status())
                .isEqualTo(OaContractHook.STATUS_CLOSED);

            long approved = fixture.submitContract("c3", contract("服务协议", "8000", TODAY, TODAY
                .plusDays(10), 613L, contractNo, "再次送审"));
            fixture.approve(SUPERVISOR, approved, "a1");
            fixture.approve(LEGAL, approved, "a2");
            fixture.approve(FINANCE, approved, "a3");
            OaContractDetailResp pending = fixture.contractService.contractDetail(TENANT, null, id);
            assertThat(pending.contract().status()).isEqualTo(OaContractHook.STATUS_PENDING_SIGN);
            assertThat(pending.contract().currentVersion()).isEqualTo(3);
            assertThat(pending.versions()).extracting(version -> version.status())
                .containsExactly("APPROVED", "WITHDRAWN", "REJECTED");

            assertThat(fixture.contractService.scanExpiring(TODAY)).isZero();
            fixture.contractService.sign(TENANT, LEGAL, id, sign("s1", TODAY));
            long far = fixture.submitContract("c4", contract("长期协议", "100", TODAY, TODAY
                .plusDays(90), 614L, null, null));
            long farId = fixture.contractId(far);
            fixture.approve(SUPERVISOR, far, "f1");
            fixture.approve(LEGAL, far, "f2");
            fixture.approve(FINANCE, far, "f3");
            fixture.contractService.sign(TENANT, LEGAL, farId, sign("s2", TODAY));
            assertThatThrownBy(() -> fixture.contractService.sign(TENANT, LEGAL, farId, sign("s1", TODAY)))
                .hasMessageContaining("请求号已被使用");

            assertThat(fixture.contractService.contracts(TENANT, null, null, null, 30, 1, 10))
                .extracting(OaContractResp::id)
                .containsExactly(id);
            assertThat(fixture.contractService.scanExpiring(TODAY)).isEqualTo(1);
            assertThat(fixture.contractService.scanExpiring(TODAY)).isZero();
            assertThat(fixture.outbox(OaContractService.EVENT_CONTRACT_EXPIRING)).isEqualTo(1);
        });
    }

    @Test
    void sealArchiveAndKeeperOnlyExecution() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture.contractService
                .createSeal(TENANT, GM, sealReq("GZ", "公章", "OFFICIAL", 999L, null))).hasMessageContaining("保管人不是在职员工");
            OaSealResp official = fixture.seal("GZ", "公章", "OFFICIAL");
            OaSealResp contractSeal = fixture.seal("HTZ", "合同章", "CONTRACT");
            assertThat(official.keeperName()).isEqualTo("Carol");
            assertThatThrownBy(() -> fixture.seal("GZ", "公章2", "OTHER")).hasMessageContaining("印章编码已存在");
            assertThatThrownBy(() -> fixture.contractService.updateSeal(TENANT, GM, contractSeal
                .id(), sealReq("HTZ", "合同专用章", "CONTRACT", 103L, null))).hasMessageContaining("缺少版本号");
            assertThatThrownBy(() -> fixture.contractService.updateSeal(TENANT, GM, contractSeal
                .id(), sealReq("HTZ", "合同专用章", "CONTRACT", 103L, 9))).hasMessageContaining("印章已被修改");
            assertThatThrownBy(() -> fixture.contractService.updateSeal(TENANT, GM, contractSeal
                .id(), sealReq("GZ", "合同专用章", "CONTRACT", 103L, contractSeal.version())))
                .hasMessageContaining("印章编码已存在");
            OaSealResp renamed = fixture.contractService.updateSeal(TENANT, GM, contractSeal
                .id(), sealReq("HTZ", "合同专用章", "CONTRACT", 103L, contractSeal.version()));
            assertThat(renamed.name()).isEqualTo("合同专用章");
            assertThat(renamed.version()).isEqualTo(contractSeal.version() + 1);
            fixture.seedSealRelease();
            seedFile(fixture.jdbc, 621, APPLICANT, "用印件.pdf");

            assertThatThrownBy(() -> fixture.submitSeal("x0", "HTZ", 1, TODAY.minusDays(1), null, null))
                .hasMessageContaining("用印日期不能早于今天");
            assertThatThrownBy(() -> fixture.submitSeal("x0", "HTZ", 1, TODAY, "HT-NOPE", null))
                .hasMessageContaining("合同编号 HT-NOPE 不存在或不是本人经办");

            long normal = fixture.submitSeal("x1", "HTZ", 2, TODAY, null, 621L);
            assertThat(fixture.variable(normal, OaSealRequestHook.VAR_RISK)).isEqualTo(0);
            long normalId = fixture.sealRequestId(normal);
            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, LEGAL, normalId, execute("e0", 1)))
                .hasMessageContaining("用印尚未审批通过，不能登记");
            fixture.approve(SUPERVISOR, normal, "a1");
            assertThat(fixture.applicationStatus(normal)).isEqualTo("APPROVED");
            assertThat(fixture.sealRequest(normalId).status()).isEqualTo(OaSealRequestHook.STATUS_PENDING_SEAL);

            long risky = fixture.submitSeal("x2", "GZ", 1, TODAY, null, null);
            assertThat(fixture.variable(risky, OaSealRequestHook.VAR_RISK)).isEqualTo(1);
            fixture.approve(SUPERVISOR, risky, "b1");
            assertThat(fixture.task(risky).getTaskDefinitionKey()).isEqualTo("gmApprove");
            fixture.approve(GM, risky, "b2");
            long manyCopies = fixture.submitSeal("x3", "HTZ", 11, TODAY, null, null);
            assertThat(fixture.variable(manyCopies, OaSealRequestHook.VAR_RISK)).isEqualTo(1);
            fixture.approvalService.withdraw(TENANT, APPLICANT, manyCopies, action("w1"));
            long closedId = fixture.sealRequestId(manyCopies);
            assertThat(fixture.sealRequest(closedId).status()).isEqualTo(OaSealRequestHook.STATUS_CLOSED);
            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, LEGAL, closedId, execute("e0", 1)))
                .hasMessageContaining("用印申请已关闭");

            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, GM, normalId, execute("e1", 1)))
                .hasMessageContaining("只有印章保管人可以登记用印");
            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, LEGAL, normalId, execute("e1", 3)))
                .hasMessageContaining("实际用印份数不能超过审批份数 2");
            OaSealRequestResp used = fixture.contractService.executeSeal(TENANT, LEGAL, normalId, execute("e1", 2));
            assertThat(used.status()).isEqualTo(OaSealRequestHook.STATUS_USED);
            assertThat(used.usedCopies()).isEqualTo(2);
            assertThat(used.executorName()).isEqualTo("Carol");
            assertThat(fixture.contractService.executeSeal(TENANT, LEGAL, normalId, execute("e1", 2)).status())
                .isEqualTo(OaSealRequestHook.STATUS_USED);
            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, LEGAL, normalId, execute("e2", 1)))
                .hasMessageContaining("该用印已登记");
            long riskyId = fixture.sealRequestId(risky);
            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, LEGAL, riskyId, execute("e1", 1)))
                .hasMessageContaining("请求号已被使用");
            assertThat(fixture.outbox(OaContractService.EVENT_SEAL_USED)).isEqualTo(1);

            assertThat(fixture.contractService.sealRequestFiles(TENANT, null, LEGAL, normalId))
                .extracting(OaLinkedFileResp::fileId)
                .containsExactly(621L);
            assertThat(fixture.contractService.sealRequestFile(TENANT, APPLICANT, null, normalId, 621).fileName())
                .isEqualTo("用印件.pdf");
            assertThatThrownBy(() -> fixture.contractService.sealRequestFiles(TENANT, null, GM, normalId))
                .hasMessageContaining("用印申请不存在或无权查看");
            assertThatThrownBy(() -> fixture.contractService.sealRequestFile(TENANT, SUPERVISOR, null, normalId, 621))
                .hasMessageContaining("用印申请不存在或无权查看");
            assertThatThrownBy(() -> fixture.contractService.sealRequestFile(TENANT, null, LEGAL, riskyId, 621))
                .isInstanceOf(BusinessException.class);
            assertThat(fixture.contractService.sealRequests(TENANT, null, null, LEGAL, 1, 10)).hasSize(3);
            assertThat(fixture.contractService.sealRequests(TENANT, null, null, GM, 1, 10)).isEmpty();
            assertThat(fixture.contractService.countSealRequests(TENANT, "PENDING_SEAL", APPLICANT, null)).isEqualTo(1);
            assertThat(fixture.contractService.countSealRequests(102, null, null, null)).isZero();

            OaSealResp official2 = fixture.contractService.seals(TENANT, null)
                .stream()
                .filter(seal -> seal.code().equals("GZ"))
                .findFirst()
                .orElseThrow();
            fixture.contractService.updateSeal(TENANT, GM, official
                .id(), new OaSealReq("GZ", "公章", "OFFICIAL", 103L, "DISABLED", null, official2.version()));
            assertThatThrownBy(() -> fixture.contractService.executeSeal(TENANT, LEGAL, riskyId, execute("e3", 1)))
                .hasMessageContaining("印章已停用，不能登记用印");
            assertThatThrownBy(() -> fixture.submitSeal("x4", "GZ", 1, TODAY, null, null))
                .hasMessageContaining("印章「公章」已停用");
            assertThat(fixture.contractService.seals(TENANT, "ENABLED")).extracting(OaSealResp::code)
                .containsExactly("HTZ");
        });
    }

    @Test
    void sealRequestCanReferenceOwnSignableContract() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            fixture.seal("HTZ", "合同章", "CONTRACT");
            fixture.seedSealRelease();
            seedFile(fixture.jdbc, 631, APPLICANT, "合同.pdf");
            long app = fixture.submitContract("c1", contract("小额合同", "100", TODAY, TODAY
                .plusDays(60), 631L, null, null));
            long contractId = fixture.contractId(app);
            String contractNo = fixture.contractService.contractDetail(TENANT, null, contractId)
                .contract()
                .contractNo();
            assertThatThrownBy(() -> fixture.submitSeal("x0", "HTZ", 1, TODAY, contractNo, null))
                .hasMessageContaining("合同当前状态为审批中，不能申请用印");
            fixture.approve(SUPERVISOR, app, "a1");
            fixture.approve(LEGAL, app, "a2");
            fixture.approve(FINANCE, app, "a3");
            long seal = fixture.submitSeal("x1", "HTZ", 2, TODAY, contractNo, null);
            OaSealRequestResp request = fixture.sealRequest(fixture.sealRequestId(seal));
            assertThat(request.contractId()).isEqualTo(contractId);
            assertThat(request.contractNo()).isEqualTo(contractNo);
            assertThat(fixture.contractService.contractDetail(TENANT, APPLICANT, contractId).sealRequests())
                .extracting(OaSealRequestResp::id)
                .containsExactly(request.id());
        });
    }

    @Test
    void documentPublishReadAckRevisionAndRevoke() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            seedFile(fixture.jdbc, 641, APPLICANT, "制度.pdf");
            assertThatThrownBy(() -> fixture
                .submitDocument("d0", document("NOTICE", "通知", OaDocumentHook.SCOPE_CUSTOM, List.of(), List
                    .of(), "Y", null, null, null))).hasMessageContaining("请选择发布部门或人员");

            long app = fixture.submitDocument("d1", document("NOTICE", "年会通知", OaDocumentHook.SCOPE_CUSTOM, List
                .of("10"), List.of(), "Y", 641L, null, null));
            long id = fixture.documentId(app);
            OaDocumentResp approving = fixture.documentService.detail(TENANT, id).document();
            assertThat(approving.status()).isEqualTo(OaDocumentHook.STATUS_APPROVING);
            assertThat(approving.docNo()).matches("GW\\d{6}\\d{4,}");
            assertThat(fixture.documentService.inbox(TENANT, SUPERVISOR, null, false, 1, 10)).isEmpty();
            assertThatThrownBy(() -> fixture.documentService.read(TENANT, SUPERVISOR, id))
                .hasMessageContaining("公文不存在或无权查看");
            assertThatThrownBy(() -> fixture.documentService.revoke(TENANT, GM, id, new OaDocumentRevokeReq("误发")))
                .hasMessageContaining("公文当前状态为审批中，不能撤稿");

            fixture.approve(SUPERVISOR, app, "a1");
            assertThat(fixture.task(app).getTaskDefinitionKey()).isEqualTo("gmApprove");
            fixture.approve(GM, app, "a2");
            OaDocumentDetailResp published = fixture.documentService.detail(TENANT, id);
            assertThat(published.document().status()).isEqualTo(OaDocumentHook.STATUS_PUBLISHED);
            assertThat(published.document().publishedVersion()).isEqualTo(1);
            assertThat(published.receipts()).extracting(OaDocumentReceiptResp::userId)
                .containsExactlyInAnyOrder(101L, 102L, 104L);
            assertThat(fixture.outbox(OaDocumentHook.EVENT_PUBLISHED)).isEqualTo(1);

            assertThat(fixture.documentService.inbox(TENANT, LEGAL, null, false, 1, 10)).isEmpty();
            assertThatThrownBy(() -> fixture.documentService.read(TENANT, LEGAL, id))
                .hasMessageContaining("公文不存在或无权查看");
            assertThatThrownBy(() -> fixture.documentService.inboxFile(TENANT, LEGAL, id, 641))
                .hasMessageContaining("公文不存在或无权查看");
            assertThat(fixture.documentService.countInbox(TENANT, SUPERVISOR, null, true)).isEqualTo(1);
            OaDocumentReadResp read = fixture.documentService.read(TENANT, SUPERVISOR, id);
            assertThat(read.content()).isEqualTo("正文 年会通知");
            assertThat(read.item().readTime()).isNotNull();
            assertThat(read.item().ackTime()).isNull();
            assertThat(read.files()).extracting(OaLinkedFileResp::fileId).containsExactly(641L);
            assertThat(fixture.documentService.inboxFile(TENANT, SUPERVISOR, id, 641).fileName()).isEqualTo("制度.pdf");
            assertThat(fixture.documentService.countInbox(TENANT, SUPERVISOR, null, true)).isEqualTo(1);
            OaDocumentInboxResp acked = fixture.documentService.ack(TENANT, SUPERVISOR, id);
            assertThat(acked.ackTime()).isNotNull();
            assertThat(fixture.documentService.countInbox(TENANT, SUPERVISOR, null, true)).isZero();
            OaDocumentResp counted = fixture.documentService.detail(TENANT, id).document();
            assertThat(counted.receiptCount()).isEqualTo(3);
            assertThat(counted.readCount()).isEqualTo(1);
            assertThat(counted.ackCount()).isEqualTo(1);

            String docNo = approving.docNo();
            assertThatThrownBy(() -> fixture
                .submitDocument("d2", document("KNOWLEDGE", "年会通知", OaDocumentHook.SCOPE_ALL, List.of(), List
                    .of(), "Y", null, docNo, "改期"))).hasMessageContaining("修订不能改变公文类型");
            long revision = fixture.submitDocument("d2", document("NOTICE", "年会通知（改期）", OaDocumentHook.SCOPE_ALL, List
                .of(), List.of(), "Y", null, docNo, "改期"));
            assertThat(fixture.documentId(revision)).isEqualTo(id);
            assertThatThrownBy(() -> fixture
                .submitDocument("d3", document("NOTICE", "再改", OaDocumentHook.SCOPE_ALL, List.of(), List
                    .of(), "Y", null, docNo, "再改"))).hasMessageContaining("该公文已有审批中的修订");
            assertThatThrownBy(() -> fixture.documentService.revoke(TENANT, GM, id, new OaDocumentRevokeReq("误发")))
                .hasMessageContaining("公文有审批中的修订，不能撤稿");
            assertThat(fixture.documentService.read(TENANT, SUPERVISOR, id).item().versionNo()).isEqualTo(1);
            fixture.approve(SUPERVISOR, revision, "b1");
            fixture.approve(GM, revision, "b2");

            OaDocumentDetailResp revised = fixture.documentService.detail(TENANT, id);
            assertThat(revised.document().publishedVersion()).isEqualTo(2);
            assertThat(revised.document().title()).isEqualTo("年会通知（改期）");
            assertThat(revised.receipts()).extracting(OaDocumentReceiptResp::userId)
                .containsExactlyInAnyOrder(101L, 102L, 103L, 104L);
            assertThat(revised.versions()).extracting(version -> version.versionNo()).containsExactly(2, 1);
            OaDocumentInboxResp reAck = fixture.documentService.inbox(TENANT, SUPERVISOR, null, true, 1, 10).get(0);
            assertThat(reAck.versionNo()).isEqualTo(2);
            assertThat(reAck.readTime()).isNull();
            assertThat(reAck.ackTime()).isNull();
            OaDocumentReadResp legalRead = fixture.documentService.read(TENANT, LEGAL, id);
            assertThat(legalRead.changeNote()).isEqualTo("改期");
            assertThat(legalRead.files()).isEmpty();
            assertThatThrownBy(() -> fixture.documentService.inboxFile(TENANT, SUPERVISOR, id, 641))
                .isInstanceOf(BusinessException.class);
            assertThat(fixture.documentService.documentFile(TENANT, id, 641).fileName()).isEqualTo("制度.pdf");

            OaDocumentResp revoked = fixture.documentService.revoke(TENANT, GM, id, new OaDocumentRevokeReq("活动取消"));
            assertThat(revoked.status()).isEqualTo(OaDocumentHook.STATUS_REVOKED);
            assertThat(revoked.revokeByName()).isEqualTo("Dave");
            assertThat(revoked.revokeReason()).isEqualTo("活动取消");
            assertThat(fixture.documentService.inbox(TENANT, LEGAL, null, false, 1, 10)).isEmpty();
            assertThatThrownBy(() -> fixture.documentService.read(TENANT, LEGAL, id))
                .hasMessageContaining("公文不存在或无权查看");
            assertThatThrownBy(() -> fixture.documentService.revoke(TENANT, GM, id, new OaDocumentRevokeReq("再撤")))
                .hasMessageContaining("公文当前状态为已撤稿，不能撤稿");
            assertThatThrownBy(() -> fixture.documentService.detail(102, id)).hasMessageContaining("公文不存在");
        });
    }

    @Test
    void knowledgeSkipsGmAndRejectedDraftCloses() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture
                .submitDocument("k0", document("KNOWLEDGE", "手册", OaDocumentHook.SCOPE_CUSTOM, List.of(), List
                    .of(999L), "N", null, null, null))).hasMessageContaining("发布人员中包含非在职员工");
            long knowledge = fixture
                .submitDocument("k1", document("KNOWLEDGE", "报销手册", OaDocumentHook.SCOPE_CUSTOM, List.of(), List
                    .of(LEGAL), "N", null, null, null));
            long id = fixture.documentId(knowledge);
            assertThat(fixture.variable(knowledge, OaDocumentHook.VAR_NOTICE)).isEqualTo(0);
            fixture.approve(SUPERVISOR, knowledge, "a1");
            OaDocumentResp published = fixture.documentService.detail(TENANT, id).document();
            assertThat(published.status()).isEqualTo(OaDocumentHook.STATUS_PUBLISHED);
            assertThat(published.docNo()).startsWith("ZS");
            assertThat(published.requireAck()).isFalse();
            assertThat(fixture.documentService.inbox(TENANT, LEGAL, "报销", false, 1, 10))
                .extracting(OaDocumentInboxResp::documentId)
                .containsExactly(id);
            assertThat(fixture.documentService.inbox(TENANT, LEGAL, "不存在", false, 1, 10)).isEmpty();
            assertThatThrownBy(() -> fixture.documentService.ack(TENANT, LEGAL, id)).hasMessageContaining("该公文无需签收");
            fixture.documentService.read(TENANT, LEGAL, id);
            assertThat(fixture.documentService.countInbox(TENANT, LEGAL, null, true)).isZero();

            long rejected = fixture.submitDocument("k2", document("NOTICE", "草稿通知", OaDocumentHook.SCOPE_ALL, List
                .of(), List.of(), "Y", null, null, null));
            long rejectedId = fixture.documentId(rejected);
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(rejected)
                .getId(), new OaApprovalActionReq("r1", "措辞"));
            OaDocumentDetailResp closed = fixture.documentService.detail(TENANT, rejectedId);
            assertThat(closed.document().status()).isEqualTo(OaDocumentHook.STATUS_CLOSED);
            assertThat(closed.receipts()).isEmpty();
            assertThat(closed.versions().get(0).status()).isEqualTo("REJECTED");

            String knowledgeNo = published.docNo();
            long failedRevision = fixture
                .submitDocument("k3", document("KNOWLEDGE", "报销手册 v2", OaDocumentHook.SCOPE_CUSTOM, List.of(), List
                    .of(LEGAL), "N", null, knowledgeNo, "补充差旅"));
            fixture.approvalService.withdraw(TENANT, APPLICANT, failedRevision, action("w1"));
            OaDocumentResp kept = fixture.documentService.detail(TENANT, id).document();
            assertThat(kept.status()).isEqualTo(OaDocumentHook.STATUS_PUBLISHED);
            assertThat(kept.publishedVersion()).isEqualTo(1);
            assertThat(kept.pendingApplicationId()).isNull();
            assertThat(fixture.documentService.read(TENANT, LEGAL, id).item().title()).isEqualTo("报销手册");

            assertThat(fixture.documentService.documents(TENANT, "PUBLISHED", "KNOWLEDGE", null, 1, 10)).hasSize(1);
            assertThat(fixture.documentService.countDocuments(TENANT, null, null, "草稿")).isEqualTo(1);
            assertThat(fixture.documentService.countDocuments(102, null, null, null)).isZero();
            assertThatThrownBy(() -> fixture.documentService.documents(TENANT, "BOGUS", null, null, 1, 10))
                .hasMessageContaining("公文状态无效");
        });
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private static OaContractSignReq sign(String requestId, LocalDate date) {
        return new OaContractSignReq(requestId, date, "甲方张三 / 乙方李四", null);
    }

    private static OaContractArchiveReq archive(String requestId, String archiveNo) {
        return new OaContractArchiveReq(requestId, archiveNo, "档案柜 A", null);
    }

    private static OaSealExecuteReq execute(String requestId, int copies) {
        return new OaSealExecuteReq(requestId, copies, TODAY, null);
    }

    private static OaSealReq sealReq(String code, String name, String type, Long keeperId, Integer version) {
        return new OaSealReq(code, name, type, keeperId, "ENABLED", null, version);
    }

    private static Map<String, Object> contract(String title,
                                                String amount,
                                                LocalDate start,
                                                LocalDate end,
                                                Long fileId,
                                                String baseNo,
                                                String changeNote) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(OaContractHook.FIELD_TITLE, title);
        form.put(OaContractHook.FIELD_COUNTERPARTY, "某某科技有限公司");
        form.put(OaContractHook.FIELD_TYPE, "PURCHASE");
        form.put(OaContractHook.FIELD_AMOUNT, new BigDecimal(amount));
        form.put(OaContractHook.FIELD_START, start.toString());
        form.put(OaContractHook.FIELD_END, end.toString());
        form.put(OaContractHook.FIELD_SUMMARY, "付款与交付条款");
        form.put(OaContractHook.FIELD_FILES, List.of(Map.of("fileId", fileId)));
        if (baseNo != null) {
            form.put(OaContractHook.FIELD_BASE_NO, baseNo);
        }
        if (changeNote != null) {
            form.put(OaContractHook.FIELD_CHANGE_NOTE, changeNote);
        }
        return form;
    }

    private static Map<String, Object> document(String type,
                                                String title,
                                                String scope,
                                                List<String> depts,
                                                List<Long> users,
                                                String requireAck,
                                                Long fileId,
                                                String baseNo,
                                                String changeNote) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(OaDocumentHook.FIELD_TYPE, type);
        form.put(OaDocumentHook.FIELD_TITLE, title);
        form.put(OaDocumentHook.FIELD_CONTENT, "正文 " + title);
        form.put(OaDocumentHook.FIELD_SCOPE, scope);
        if (!depts.isEmpty()) {
            form.put(OaDocumentHook.FIELD_DEPTS, depts);
        }
        if (!users.isEmpty()) {
            form.put(OaDocumentHook.FIELD_USERS, users);
        }
        form.put(OaDocumentHook.FIELD_REQUIRE_ACK, requireAck);
        if (fileId != null) {
            form.put(OaDocumentHook.FIELD_FILES, List.of(Map.of("fileId", fileId)));
        }
        if (baseNo != null) {
            form.put(OaDocumentHook.FIELD_BASE_NO, baseNo);
        }
        if (changeNote != null) {
            form.put(OaDocumentHook.FIELD_CHANGE_NOTE, changeNote);
        }
        return form;
    }

    private static void seedFile(JdbcTemplate jdbc, long id, long uploaderId, String originalName) {
        jdbc.update("MERGE INTO sys_storage KEY (id) VALUES (1, 'local', 0)");
        jdbc.update("""
            INSERT INTO sys_file
              (id, tenant_id, name, original_name, size, parent_path, path, extension, content_type,
               type, storage_id, create_user, deleted)
            VALUES (?, ?, ?, ?, 1024, '/2026/11/10', ?, 'pdf', 'application/pdf', 1, 1, ?, 0)
            """, id, TENANT, "stored-" + id + ".pdf", originalName, "/2026/11/10/stored-" + id + ".pdf", uploaderId);
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-contract-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        OaExpenseServiceTest.createTables(jdbc);
        OaExpenseServiceTest.seedAccounts(jdbc);
        createContractTables(jdbc);
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (10, 101, '研发部', 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, parent_id, deleted) VALUES (11, 101, '研发一组', 10, 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (20, 101, '财务部', 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (30, 102, '他租户', 0)");
        jdbc.update("UPDATE sys_user SET dept_id = 11 WHERE id = 104");
        for (long userId = 101; userId <= 104; userId++) {
            jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, employment_status, deleted) VALUES (?, 101, ?, 'ACTIVE', 0)", userId, userId);
        }

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-contract")
            .tenantId(Long.toString(TENANT))
            .addString("contract.bpmn20.xml", OaContractService.CONTRACT_BPMN)
            .addString("seal.bpmn20.xml", OaContractService.SEAL_BPMN)
            .addString("document.bpmn20.xml", OaDocumentService.BPMN)
            .deploy();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        StubStaffDirectory directory = new StubStaffDirectory();

        OaContractRepository contractRepository = new OaContractRepository(jdbc);
        OaDocumentRepository documentRepository = new OaDocumentRepository(jdbc);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaBusinessHooks hooks = new OaBusinessHooks(List
            .of(new OaContractHook(contractRepository), new OaSealRequestHook(contractRepository, directory), new OaDocumentHook(documentRepository, directory, outboxService)), launchRepository);
        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, context
            .getBean(org.flowable.engine.HistoryService.class), repositoryService);
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaContractService contractService = new OaContractService(contractRepository, directory, attachmentService, null, null, outboxService, objectMapper, transactionManager);
        OaDocumentService documentService = new OaDocumentService(documentRepository, attachmentService, null, null, objectMapper, transactionManager);

        seedRelease(jdbc, repositoryService, 1, CONTRACT_RELEASE, OaContractService.CONTRACT_MODEL_KEY, OaContractService.CONTRACT_BPMN, OaContractHook.BUSINESS_TYPE, contractService
            .contractSchema());
        seedRelease(jdbc, repositoryService, 3, DOCUMENT_RELEASE, OaDocumentService.MODEL_KEY, OaDocumentService.BPMN, OaDocumentHook.BUSINESS_TYPE, documentService
            .documentSchema(documentRepository.depts(TENANT)));

        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, repositoryService, hooks, contractService, documentService, launchService, approvalService, runtimeService, taskService);
    }

    private static void seedRelease(JdbcTemplate jdbc,
                                    RepositoryService repositoryService,
                                    long modelId,
                                    long releaseId,
                                    String processKey,
                                    String bpmn,
                                    String businessType,
                                    String schema) {
        String definitionId = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(processKey)
            .singleResult()
            .getId();
        long formId = modelId + 19;
        long versionId = modelId + 29;
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, business_type, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (?, 101, ?, ?, 'contract', '说明', 'PUBLISHED', ?, ?, 1, ?, ?, 0, 7, CURRENT_TIMESTAMP, 0)
            """, modelId, processKey, processKey, businessType, releaseId, formId, versionId);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (?, 101, ?, 1, ?, ?, ?, ?, 1, ?, ?, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, releaseId, modelId, bpmn, "a".repeat(64), definitionId, processKey, formId, versionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (?, 101, ?, ?, 'PUBLISHED', 0)", formId, processKey, processKey);
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (?, 101, ?, 1, ?, ?, 3, 7, CURRENT_TIMESTAMP)
            """, versionId, formId, schema, "b".repeat(64));
    }

    static void createContractTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_storage (id BIGINT PRIMARY KEY, code VARCHAR(30) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE sys_file (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL,
              original_name VARCHAR(255) NOT NULL, size BIGINT, parent_path VARCHAR(512) NOT NULL DEFAULT '/',
              path VARCHAR(512) NOT NULL, extension VARCHAR(32), content_type VARCHAR(255),
              type INT NOT NULL DEFAULT 1, storage_id BIGINT NOT NULL, create_user BIGINT NOT NULL,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_attachment_link (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT, business_type VARCHAR(64), business_key VARCHAR(128),
              file_id BIGINT NOT NULL, file_name VARCHAR(255), file_size BIGINT, content_type VARCHAR(100),
              category VARCHAR(32) DEFAULT 'FORM', scan_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
              available TINYINT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP, deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_attachment_file UNIQUE (tenant_id, file_id, deleted)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_contract (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, contract_no VARCHAR(32),
              title VARCHAR(200) NOT NULL, counterparty VARCHAR(200) NOT NULL, contract_type VARCHAR(20) NOT NULL,
              amount DECIMAL(14,2) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL, owner_id BIGINT NOT NULL,
              current_version INT NOT NULL DEFAULT 1, current_application_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
              sign_request_id VARCHAR(64), signed_date DATE, signatories VARCHAR(200), sign_remark VARCHAR(500),
              signed_by BIGINT, signed_time TIMESTAMP, archive_request_id VARCHAR(64), archive_no VARCHAR(64),
              archive_location VARCHAR(100), archived_by BIGINT, archived_time TIMESTAMP,
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_contract_no UNIQUE (tenant_id, contract_no),
              CONSTRAINT uk_contract_sign_request UNIQUE (tenant_id, sign_request_id),
              CONSTRAINT uk_contract_archive_request UNIQUE (tenant_id, archive_request_id),
              CONSTRAINT uk_contract_archive_no UNIQUE (tenant_id, archive_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_contract_version (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              contract_id BIGINT NOT NULL, version_no INT NOT NULL, application_id BIGINT NOT NULL,
              title VARCHAR(200) NOT NULL, counterparty VARCHAR(200) NOT NULL, contract_type VARCHAR(20) NOT NULL,
              amount DECIMAL(14,2) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,
              summary VARCHAR(2000) NOT NULL, change_note VARCHAR(500), status VARCHAR(20) NOT NULL,
              applicant_id BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_contract_version_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_contract_version_no UNIQUE (tenant_id, contract_id, version_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_seal (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(32) NOT NULL,
              name VARCHAR(100) NOT NULL, seal_type VARCHAR(20) NOT NULL, keeper_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', remark VARCHAR(500), version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_seal_code UNIQUE (tenant_id, code)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_seal_request (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, seal_id BIGINT NOT NULL, contract_id BIGINT,
              copies INT NOT NULL, use_date DATE NOT NULL, status VARCHAR(20) NOT NULL, execute_request_id VARCHAR(64),
              used_copies INT, used_date DATE, executor_id BIGINT, executed_time TIMESTAMP, execute_remark VARCHAR(500),
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_seal_request_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_seal_execute_request UNIQUE (tenant_id, execute_request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_document (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, doc_no VARCHAR(32),
              doc_type VARCHAR(20) NOT NULL, title VARCHAR(200) NOT NULL, category VARCHAR(64), owner_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL, published_version INT NOT NULL DEFAULT 0, latest_version INT NOT NULL DEFAULT 1,
              pending_application_id BIGINT, require_ack BOOLEAN NOT NULL DEFAULT FALSE, publish_time TIMESTAMP,
              revoke_by BIGINT, revoke_time TIMESTAMP, revoke_reason VARCHAR(500), create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP,
              CONSTRAINT uk_document_no UNIQUE (tenant_id, doc_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_document_version (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              document_id BIGINT NOT NULL, version_no INT NOT NULL, application_id BIGINT NOT NULL,
              title VARCHAR(200) NOT NULL, category VARCHAR(64), content CLOB NOT NULL, change_note VARCHAR(500),
              audience_json VARCHAR(4000) NOT NULL, require_ack BOOLEAN NOT NULL DEFAULT FALSE,
              status VARCHAR(20) NOT NULL, applicant_id BIGINT NOT NULL, audience_count INT NOT NULL DEFAULT 0,
              publish_time TIMESTAMP, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_document_version_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_document_version_no UNIQUE (tenant_id, document_id, version_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_document_receipt (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              document_id BIGINT NOT NULL, version_no INT NOT NULL, user_id BIGINT NOT NULL, read_time TIMESTAMP,
              ack_time TIMESTAMP, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_document_receipt UNIQUE (tenant_id, document_id, version_no, user_id)
            )
            """);
    }

    /** 员工目录桩：101–104 在职，101 的直属上级是 102；LEGAL 岗位是 103，FINANCE/GM 岗位是 104。 */
    static final class StubStaffDirectory implements OaStaffDirectory {

        @Override
        public boolean activeEmployee(long tenantId, long userId) {
            return tenantId == TENANT && userId >= 101 && userId <= 104;
        }

        @Override
        public Long departmentId(long tenantId, long userId) {
            return userId >= 103 ? 20L : 10L;
        }

        @Override
        public Long supervisorId(long tenantId, long userId) {
            return userId == APPLICANT ? SUPERVISOR : null;
        }

        @Override
        public boolean enabledPosition(long tenantId, String code) {
            return tenantId == TENANT && List.of("LEGAL", "FINANCE", "GM").contains(code);
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            return switch (code) {
                case "LEGAL" -> List.of(LEGAL);
                case "FINANCE" -> List.of(FINANCE);
                case "GM" -> List.of(GM);
                default -> List.of();
            };
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(SUPERVISOR);
        }
    }

    private record Fixture(JdbcTemplate jdbc, RepositoryService repositoryService, OaBusinessHooks hooks,
                           OaContractService contractService, OaDocumentService documentService,
                           OaLaunchService launchService, OaApprovalService approvalService,
                           RuntimeService runtimeService, TaskService taskService) {

        private OaSealResp seal(String code, String name, String type) {
            return contractService.createSeal(TENANT, GM, sealReq(code, name, type, LEGAL, null));
        }

        private void seedSealRelease() {
            seedRelease(jdbc, repositoryService, 2, SEAL_RELEASE, OaContractService.SEAL_MODEL_KEY, OaContractService.SEAL_BPMN, OaSealRequestHook.BUSINESS_TYPE, contractService
                .sealSchema(contractService.seals(TENANT, "ENABLED")));
        }

        private long submit(String requestId, long releaseId, String title, Map<String, Object> form) {
            return launchService.submit(TENANT, APPLICANT, releaseId, new OaLaunchSubmitReq(requestId, title, form, List
                .of())).applicationId();
        }

        private long submitContract(String requestId, Map<String, Object> form) {
            return submit(requestId, CONTRACT_RELEASE, "合同审批", form);
        }

        private long submitDocument(String requestId, Map<String, Object> form) {
            return submit(requestId, DOCUMENT_RELEASE, "公文发布", form);
        }

        private long submitSeal(String requestId,
                                String sealCode,
                                int copies,
                                LocalDate useDate,
                                String contractNo,
                                Long fileId) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaSealRequestHook.FIELD_SEAL, sealCode);
            form.put(OaSealRequestHook.FIELD_PURPOSE, "合同盖章");
            form.put(OaSealRequestHook.FIELD_COPIES, copies);
            form.put(OaSealRequestHook.FIELD_USE_DATE, useDate.toString());
            if (contractNo != null) {
                form.put(OaSealRequestHook.FIELD_CONTRACT_NO, contractNo);
            }
            if (fileId != null) {
                form.put(OaSealRequestHook.FIELD_FILES, List.of(Map.of("fileId", fileId)));
            }
            return submit(requestId, SEAL_RELEASE, "用印申请", form);
        }

        private void approve(long userId, long applicationId, String requestId) {
            approvalService.approve(TENANT, userId, task(applicationId).getId(), action(requestId));
        }

        private Task task(long applicationId) {
            Task task = taskService.createTaskQuery().processInstanceId(instanceId(applicationId)).singleResult();
            assertThat(task).isNotNull();
            return task;
        }

        private String instanceId(long applicationId) {
            return jdbc
                .queryForObject("SELECT process_instance_id FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private Object variable(long applicationId, String name) {
            return runtimeService.getVariable(instanceId(applicationId), name);
        }

        private String applicationStatus(long applicationId) {
            return jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private long contractId(long applicationId) {
            return jdbc
                .queryForObject("SELECT contract_id FROM oa_contract_version WHERE application_id = ?", Long.class, applicationId);
        }

        private long sealRequestId(long applicationId) {
            return jdbc
                .queryForObject("SELECT id FROM oa_seal_request WHERE application_id = ?", Long.class, applicationId);
        }

        private OaSealRequestResp sealRequest(long id) {
            return contractService.sealRequests(TENANT, null, null, null, 1, 100)
                .stream()
                .filter(request -> request.id() == id)
                .findFirst()
                .orElseThrow();
        }

        private long documentId(long applicationId) {
            return jdbc
                .queryForObject("SELECT document_id FROM oa_document_version WHERE application_id = ?", Long.class, applicationId);
        }

        private long outbox(String eventType) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM oa_outbox WHERE event_type = ?", Long.class, eventType);
        }
    }
}
