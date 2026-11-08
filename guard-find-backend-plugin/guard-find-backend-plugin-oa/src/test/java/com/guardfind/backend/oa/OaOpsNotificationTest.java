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
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaAssigneeBindingReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaOpsTerminateReq;
import com.guardfind.backend.oa.model.OaUrgeResp;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaNotificationRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaNotificationService;
import com.guardfind.backend.oa.service.OaOpsService;
import com.guardfind.backend.oa.service.OaOutboxDispatcher;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.service.OaOverdueService;
import com.guardfind.backend.oa.service.OaUrgeService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
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
 * 10.6 outbox 投递、站内通知、催办限频、超期去重与运维终止。
 */
class OaOpsNotificationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final String PROCESS_KEY = "oaApproval";

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="单级审批" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="manager"/>
            <bpmn:userTask id="manager" name="主管审批" flowable:assignee="${manager}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="manager" targetRef="end"/>
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
    void dispatcherDeliversNotificationOnceAndMarksSent() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture.submit();

            assertThat(fixture.jdbc.queryForObject("SELECT status FROM oa_outbox", String.class)).isEqualTo("PENDING");

            OaOutboxDispatcher.DispatchResult result = fixture.dispatcher.dispatchDue();
            assertThat(result.sent()).isEqualTo(1);
            assertThat(result.failed()).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT status FROM oa_outbox", String.class)).isEqualTo("SENT");
            assertThat(fixture.jdbc.queryForObject("SELECT sent_time FROM oa_outbox", java.sql.Timestamp.class))
                .isNotNull();

            var inbox = fixture.notificationService.page(TENANT, 102, false, 1, 10);
            assertThat(inbox.total()).isEqualTo(1);
            assertThat(inbox.items().get(0).title()).contains("待审批");
            assertThat(inbox.items().get(0).applicationId()).isEqualTo(applicationId);
            assertThat(fixture.notificationService.unreadCount(TENANT, 102)).isEqualTo(1);
            assertThat(fixture.notificationService.page(TENANT, 103, false, 1, 10).total()).isZero();

            assertThat(fixture.dispatcher.dispatchDue().scanned()).isZero();
            assertThat(fixture.notificationService.page(TENANT, 102, false, 1, 10).total()).isEqualTo(1);

            long notificationId = inbox.items().get(0).id();
            assertThatThrownBy(() -> fixture.notificationService.markRead(TENANT, 103, notificationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
            fixture.notificationService.markRead(TENANT, 102, notificationId);
            assertThat(fixture.notificationService.unreadCount(TENANT, 102)).isZero();
        });
    }

    @Test
    void failedDeliveryBacksOffAndManualRetryDelivers() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture.submit();
            fixture.jdbc.update("""
                INSERT INTO oa_outbox
                  (tenant_id, event_id, aggregate_type, aggregate_id, event_type, payload, dedup_key,
                   status, retry_count, create_time)
                VALUES (?, 'broken-event', 'APPLICATION', ?, 'APPLICATION_SUBMITTED', 'not-json', 'BROKEN:1',
                        'PENDING', 0, ?)
                """, TENANT, Long.toString(applicationId), LocalDateTime.now());

            OaOutboxDispatcher.DispatchResult result = fixture.dispatcher.dispatchDue();
            assertThat(result.failed()).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("""
                SELECT status FROM oa_outbox WHERE dedup_key = 'BROKEN:1'
                """, String.class)).isEqualTo("FAILED");
            assertThat(fixture.jdbc.queryForObject("""
                SELECT retry_count FROM oa_outbox WHERE dedup_key = 'BROKEN:1'
                """, Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("""
                SELECT next_retry_time FROM oa_outbox WHERE dedup_key = 'BROKEN:1'
                """, java.sql.Timestamp.class)).isNotNull();

            long brokenId = fixture.jdbc
                .queryForObject("SELECT id FROM oa_outbox WHERE dedup_key = 'BROKEN:1'", Long.class);
            fixture.jdbc
                .update("""
                    UPDATE oa_outbox SET payload = ? WHERE id = ?
                    """, "{\"applicationId\":" + applicationId + ",\"notificationType\":\"TODO\",\"title\":\"重试成功\",\"content\":\"已恢复\",\"recipients\":[102]}", brokenId);
            fixture.opsService.retryOutbox(TENANT, brokenId);

            OaOutboxDispatcher.DispatchResult retried = fixture.dispatcher.dispatchDue();
            assertThat(retried.sent()).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT status FROM oa_outbox WHERE id = ?", String.class, brokenId))
                .isEqualTo("SENT");
            assertThat(fixture.notificationService.page(TENANT, 102, false, 1, 10).items()).anyMatch(item -> "重试成功"
                .equals(item.title()));
        });
    }

    @Test
    void urgeOnlyApplicantAndCoolsDownWithoutTouchingTaskTime() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture.submit();
            java.sql.Timestamp before = fixture.jdbc
                .queryForObject("SELECT CREATE_TIME_ FROM ACT_RU_TASK", java.sql.Timestamp.class);

            assertThatThrownBy(() -> fixture.urgeService
                .urge(TENANT, 102, applicationId, new OaApprovalActionReq("urge-wrong", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅申请人");

            OaUrgeResp urged = fixture.urgeService
                .urge(TENANT, 101, applicationId, new OaApprovalActionReq("urge-1", null));
            assertThat(urged.applicationId()).isEqualTo(applicationId);
            assertThat(urged.taskCount()).isEqualTo(1);
            assertThat(urged.recipients()).containsExactly(102L);
            assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM oa_outbox WHERE event_type = 'TASK_URGED'
                """, Integer.class)).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT CREATE_TIME_ FROM ACT_RU_TASK", java.sql.Timestamp.class))
                .isEqualTo(before);

            OaUrgeResp replay = fixture.urgeService
                .urge(TENANT, 101, applicationId, new OaApprovalActionReq("urge-1", null));
            assertThat(replay.taskCount()).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM oa_outbox WHERE event_type = 'TASK_URGED'
                """, Integer.class)).isEqualTo(1);

            assertThatThrownBy(() -> fixture.urgeService
                .urge(TENANT, 101, applicationId, new OaApprovalActionReq("urge-2", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("催办过于频繁");
        });
    }

    @Test
    void overdueRemindsOncePerTaskPerDay() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture.submit();
            String taskId = fixture.managerTaskId();
            Task task = fixture.taskService.createTaskQuery().taskId(taskId).singleResult();
            assertThat(task.getCreateTime()).isNotNull();
            fixture.jdbc.update("UPDATE ACT_RU_TASK SET CREATE_TIME_ = ? WHERE ID_ = ?", java.sql.Timestamp
                .valueOf(LocalDateTime.now().minusHours(30)), taskId);

            assertThat(fixture.overdueService.scanTenant(TENANT)).isEqualTo(1);
            assertThat(fixture.overdueService.scanTenant(TENANT)).isZero();
            assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM oa_outbox WHERE event_type = 'TASK_OVERDUE' AND aggregate_id = ?
                """, Integer.class, taskId)).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("""
                SELECT payload FROM oa_outbox WHERE event_type = 'TASK_OVERDUE'
                """, String.class)).contains(Long.toString(applicationId));
        });
    }

    @Test
    void terminateCancelsProcessWritesAuditAndNotifiesApplicant() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture.submit();

            var first = fixture.opsService
                .terminate(TENANT, 999, applicationId, new OaOpsTerminateReq("terminate-1", "信息不实"));
            assertThat(first.status()).isEqualTo("CANCELLED");
            assertThat(fixture.jdbc
                .queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId))
                .isEqualTo("CANCELLED");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            assertThat(fixture.jdbc.queryForObject("""
                SELECT reason FROM oa_approval_audit WHERE action = 'TERMINATE'
                """, String.class)).isEqualTo("信息不实");
            assertThat(fixture.jdbc.queryForObject("""
                SELECT COUNT(*) FROM oa_outbox WHERE event_type = 'APPLICATION_TERMINATED'
                """, Integer.class)).isEqualTo(1);

            fixture.dispatcher.dispatchDue();
            assertThat(fixture.notificationService.page(TENANT, 101, false, 1, 10).items()).anyMatch(item -> "CANCELLED"
                .equals(item.type()));

            fixture.opsService.terminate(TENANT, 999, applicationId, new OaOpsTerminateReq("terminate-1", "信息不实"));
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_approval_audit WHERE action = 'TERMINATE'", Integer.class))
                .isEqualTo(1);

            assertThatThrownBy(() -> fixture.opsService
                .terminate(TENANT, 999, applicationId, new OaOpsTerminateReq("terminate-2", "再次终止")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅进行中");
        });
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-ops-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        seedAccounts(jdbc);
        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-ops")
            .tenantId(Long.toString(TENANT))
            .addString("oa-ops.bpmn20.xml", BPMN)
            .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(PROCESS_KEY)
            .singleResult();
        seedRelease(jdbc, definition.getId());

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        HistoryService historyService = context.getBean(HistoryService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, historyService, repositoryService);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaOutboxRepository outboxRepository = new OaOutboxRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OaOutboxService outboxService = new OaOutboxService(outboxRepository, objectMapper);
        OaNotificationService notificationService = new OaNotificationService(new OaNotificationRepository(jdbc), objectMapper);
        OaOutboxDispatcher dispatcher = new OaOutboxDispatcher(outboxRepository, notificationService, 5, 50, 30, 600);
        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(new StubStaffDirectory()));
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, objectMapper, transactionManager);
        OaApprovalRepository approvalRepository = new OaApprovalRepository(jdbc);
        OaUrgeService urgeService = new OaUrgeService(approvalRepository, launchRepository, outboxRepository, engine, outboxService, objectMapper, transactionManager, 10, 5);
        OaOverdueService overdueService = new OaOverdueService(approvalRepository, outboxRepository, engine, outboxService, 24, 200);
        OaOpsService opsService = new OaOpsService(approvalRepository, launchRepository, dispatcher, outboxService, engine, objectMapper, transactionManager);
        return new Fixture(jdbc, launchService, notificationService, dispatcher, urgeService, overdueService, opsService, runtimeService, taskService);
    }

    private static void seedAccounts(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (101, 101, 'alice', 'Alice', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (102, 101, 'bob', 'Bob', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (103, 101, 'carol', 'Carol', 20, 1, 0)");
    }

    private static void seedRelease(JdbcTemplate jdbc, String engineDefinitionId) {
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
            """, BPMN, "a"
            .repeat(64), engineDefinitionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (20, 101, 'expense_form', '报销单', 'PUBLISHED', 0)");
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (30, 101, 20, 1, ?, ?, 1, 7, CURRENT_TIMESTAMP)
            """, SCHEMA, "b".repeat(64));
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL)");
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
        jdbc.execute("""
            CREATE TABLE oa_notification (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, title VARCHAR(200) NOT NULL, content VARCHAR(1000),
              application_id BIGINT, task_id VARCHAR(64), dedup_key VARCHAR(160) NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', read_time TIMESTAMP, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_notification_dedup UNIQUE (tenant_id, user_id, dedup_key)
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

    private record Fixture(JdbcTemplate jdbc, OaLaunchService launchService, OaNotificationService notificationService,
                           OaOutboxDispatcher dispatcher, OaUrgeService urgeService, OaOverdueService overdueService,
                           OaOpsService opsService, RuntimeService runtimeService, TaskService taskService) {

        private long submit() {
            return launchService.submit(TENANT, 101, 10, new OaLaunchSubmitReq("req-submit-" + System
                .nanoTime(), "差旅报销", Map.of("reason", "出差"), List
                    .of(new OaAssigneeBindingReq("manager", "EMPLOYEE", "102")))).applicationId();
        }

        private String managerTaskId() {
            Task task = taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskDefinitionKey("manager")
                .singleResult();
            assertThat(task).isNotNull();
            return task.getId();
        }
    }
}
