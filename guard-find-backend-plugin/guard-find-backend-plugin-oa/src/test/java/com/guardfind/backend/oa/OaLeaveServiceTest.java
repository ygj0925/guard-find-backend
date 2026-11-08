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
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.leave.OaLeaveHook;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaLaunchDetailResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLeaveBalanceResp;
import com.guardfind.backend.oa.model.OaLeaveGrantReq;
import com.guardfind.backend.oa.model.OaLeavePreviewResp;
import com.guardfind.backend.oa.model.OaLeaveTypeReq;
import com.guardfind.backend.oa.model.OaLeaveTypeResp;
import com.guardfind.backend.oa.model.OaWorkCalendarReq;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaAttendanceRepository;
import com.guardfind.backend.oa.repository.OaLeaveRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaLeaveService;
import com.guardfind.backend.oa.service.OaOutboxService;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 11.1 请假试点：发起占用、通过扣减、驳回/撤回/终止释放、余额不足回滚、并发不超占与模板合法性。
 */
class OaLeaveServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long HR = 103;
    private static final LocalDate MON = LocalDate.of(2026, 3, 2);

    @Test
    void templateSchemaAndBpmnPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            String schema = fixture.leaveService.templateSchema(fixture.leaveService.types(TENANT, true));
            assertThat(new OaFormSchemaValidator().validate(schema, true).valid()).isTrue();
            assertThat(fixture.hooks.validateForm(OaLeaveHook.BUSINESS_TYPE, schema)).isEmpty();
            assertThat(fixture.hooks
                .validateForm(OaLeaveHook.BUSINESS_TYPE, "{\"fields\":[{\"key\":\"startDate\",\"type\":\"text\"}]}"))
                .anyMatch(error -> error.contains("leaveType"))
                .anyMatch(error -> error.contains("startDate 必须是 date"));
            assertThat(fixture.hooks.validateForm("GENERIC", "{}")).isEmpty();

            OaBpmnValidationResult bpmn = new OaBpmnValidator().validate(OaLeaveService.TEMPLATE_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(bpmn.errors()).isEmpty();
            assertThat(bpmn.processKey()).isEqualTo(OaLeaveService.TEMPLATE_MODEL_KEY);
            assertThat(bpmn.assigneeVariables()).containsExactlyInAnyOrder("supervisor", OaLeaveHook.VAR_HR_REVIEWER);

            OaLaunchDetailResp detail = fixture.launchService.detail(TENANT, APPLICANT, 10);
            assertThat(detail.businessType()).isEqualTo(OaLeaveHook.BUSINESS_TYPE);
            assertThat(detail.assigneeVariables()).anyMatch(v -> v.name().equals("supervisor") && "SUPERVISOR".equals(v
                .suggestedType()))
                .anyMatch(v -> v.name().equals(OaLeaveHook.VAR_HR_REVIEWER) && "POSITION".equals(v
                    .suggestedType()) && OaLeaveHook.HR_POSITION.equals(v.suggestedValue()));
        });
    }

    @Test
    void shortLeaveOccupiesThenDeductsOnApproval() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            grant(fixture, "5");

            long applicationId = fixture.submit("req-short", "ANNUAL", MON, "AM", MON.plusDays(2), "PM");
            assertBalance(fixture, "5", "0", "3");
            assertThat(fixture.requestStatus(applicationId)).isEqualTo(OaLeaveHook.STATUS_OCCUPIED);
            assertThat(fixture.variable(applicationId, OaLeaveHook.VAR_DAYS)).isEqualTo(3.0);

            fixture.approvalService.approve(TENANT, SUPERVISOR, fixture.taskId("supervisorApprove"), action("a1"));
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("APPROVED");
            assertBalance(fixture, "5", "3", "0");
            assertThat(fixture.requestStatus(applicationId)).isEqualTo(OaLeaveHook.STATUS_DEDUCTED);

            fixture.hooks.finished(TENANT, applicationId, "APPROVED", SUPERVISOR, LocalDateTime.now());
            fixture.hooks.finished(TENANT, applicationId, "CANCELLED", 1, LocalDateTime.now());
            assertBalance(fixture, "5", "3", "0");
            assertThat(fixture.ledgerActions()).containsExactly("GRANT", "OCCUPY", "DEDUCT");
            assertThat(fixture.leaveService.myRequests(TENANT, APPLICANT, 1, 10)).singleElement().satisfies(row -> {
                assertThat(row.applicationStatus()).isEqualTo("APPROVED");
                assertThat(row.days()).isEqualByComparingTo("3");
            });
        });
    }

    @Test
    void longLeaveRoutesToHrAndRejectReleases() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            grant(fixture, "10");

            long applicationId = fixture.submit("req-long", "ANNUAL", MON, "AM", MON.plusDays(3), "PM");
            assertBalance(fixture, "10", "0", "4");
            fixture.approvalService.approve(TENANT, SUPERVISOR, fixture.taskId("supervisorApprove"), action("a1"));
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("RUNNING");
            Task hrTask = fixture.task("hrApprove");
            assertThat(fixture.taskService.getIdentityLinksForTask(hrTask.getId())).anyMatch(link -> Long.toString(HR)
                .equals(link.getUserId()));

            fixture.approvalService.reject(TENANT, HR, hrTask.getId(), new OaApprovalActionReq("r1", "不批"));
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("REJECTED");
            assertBalance(fixture, "10", "0", "0");
            assertThat(fixture.requestStatus(applicationId)).isEqualTo(OaLeaveHook.STATUS_RELEASED);
            assertThat(fixture.ledgerActions()).containsExactly("GRANT", "OCCUPY", "RELEASE");
        });
    }

    @Test
    void withdrawAndTerminateRelease() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            grant(fixture, "5");

            long first = fixture.submit("req-w", "ANNUAL", MON, "AM", MON, "PM");
            long second = fixture.submit("req-c", "ANNUAL", MON.plusDays(1), "AM", MON.plusDays(1), "AM");
            assertBalance(fixture, "5", "0", "1.5");

            fixture.approvalService.withdraw(TENANT, APPLICANT, first, action("w1"));
            assertThat(fixture.applicationStatus(first)).isEqualTo("WITHDRAWN");
            assertBalance(fixture, "5", "0", "0.5");

            fixture.hooks.finished(TENANT, second, "CANCELLED", 1, LocalDateTime.now());
            fixture.hooks.finished(TENANT, second, "CANCELLED", 1, LocalDateTime.now());
            assertBalance(fixture, "5", "0", "0");
            assertThat(fixture.requestStatus(second)).isEqualTo(OaLeaveHook.STATUS_RELEASED);
        });
    }

    @Test
    void insufficientBalanceRollsBackWholeSubmit() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture.submit("req-none", "ANNUAL", MON, "AM", MON, "PM"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未发放 2026 年度年假额度");

            grant(fixture, "1");
            assertThatThrownBy(() -> fixture.submit("req-over", "ANNUAL", MON, "AM", MON.plusDays(1), "PM"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("年假余额不足：可用 1 天，本次需 2 天");

            assertThat(fixture.count("oa_application")).isZero();
            assertThat(fixture.count("oa_leave_request")).isZero();
            assertThat(fixture.count("oa_request_dedup")).isZero();
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            assertBalance(fixture, "1", "0", "0");

            OaLeavePreviewResp preview = fixture.leaveService.preview(TENANT, APPLICANT, form("ANNUAL", MON, "AM", MON
                .plusDays(1), "PM"));
            assertThat(preview.days()).isEqualByComparingTo("2");
            assertThat(preview.availableDays()).isEqualByComparingTo("1");
            assertThat(preview.message()).contains("余额不足");
        });
    }

    @Test
    void recordOnlyTypesAndRequestLimits() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            fixture.leaveService.saveCalendar(TENANT, 1, new OaWorkCalendarReq(MON.plusDays(1), "HOLIDAY", "测试节日"));

            long applicationId = fixture.submit("req-sick", "SICK", MON, "AM", MON.plusDays(2), "PM");
            assertThat(fixture.requestStatus(applicationId)).isEqualTo(OaLeaveHook.STATUS_RECORDED);
            assertThat(fixture.jdbc
                .queryForObject("SELECT days FROM oa_leave_request WHERE application_id = ?", BigDecimal.class, applicationId))
                .isEqualByComparingTo("2");
            assertThat(fixture.count("oa_leave_ledger")).isZero();

            assertThatThrownBy(() -> fixture.submit("req-limit", "LIMITED", MON, "AM", MON.plusDays(2), "PM"))
                .hasMessageContaining("单次最多 1 天");
            assertThatThrownBy(() -> fixture.submit("req-weekend", "SICK", MON.plusDays(5), "AM", MON
                .plusDays(6), "PM")).hasMessageContaining("没有工作日");
            assertThatThrownBy(() -> fixture.submit("req-off", "OFF", MON, "AM", MON, "PM"))
                .isInstanceOf(BusinessException.class);
            assertThat(fixture.leaveService.preview(TENANT, APPLICANT, form("OFF", MON, "AM", MON, "PM")).message())
                .contains("不存在或已停用");
            assertThat(fixture.leaveService.preview(TENANT, APPLICANT, form("OTHER", MON, "AM", MON, "PM")).message())
                .contains("不存在或已停用");
        });
    }

    @Test
    void concurrentSubmitsNeverOverOccupy() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            grant(fixture, "3");
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "req-race-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        fixture.submit(requestId, "ANNUAL", MON, "AM", MON.plusDays(1), "PM");
                        return true;
                    } catch (BusinessException e) {
                        assertThat(e.getMessage()).contains("余额不足");
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            pool.shutdown();
            assertThat(succeeded).isEqualTo(1);
            assertBalance(fixture, "3", "0", "2");
            assertThat(fixture.count("oa_leave_request")).isEqualTo(1);
        });
    }

    @Test
    void grantAdjustsWithinUsedAndOccupied() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            grant(fixture, "5");
            fixture.submit("req-g", "ANNUAL", MON, "AM", MON.plusDays(2), "PM");

            assertThatThrownBy(() -> grant(fixture, "-3")).hasMessageContaining("不能低于已扣减与审批中占用之和");
            assertThatThrownBy(() -> grant(fixture, "0.3")).hasMessageContaining("0.5 的整数倍");
            long sick = fixture.typeId("SICK");
            assertThatThrownBy(() -> fixture.leaveService
                .grant(TENANT, 1, new OaLeaveGrantReq(APPLICANT, sick, 2026, BigDecimal.ONE, null)))
                .hasMessageContaining("无需发放额度");
            assertThatThrownBy(() -> fixture.leaveService.grant(TENANT, 1, new OaLeaveGrantReq(999L, fixture
                .typeId("ANNUAL"), 2026, BigDecimal.ONE, null))).hasMessageContaining("员工不存在");

            OaLeaveBalanceResp after = grant(fixture, "-2");
            assertThat(after.totalDays()).isEqualByComparingTo("3");
            assertThat(after.availableDays()).isEqualByComparingTo("0");
            assertThat(fixture.leaveService.ledger(TENANT, after.id())).extracting(row -> row.action())
                .containsExactly("GRANT", "OCCUPY", "GRANT");
            assertThat(fixture.leaveService.myBalances(TENANT, APPLICANT, 2026)).singleElement()
                .satisfies(row -> assertThat(row.userName()).isEqualTo("Alice"));

            assertThatThrownBy(() -> fixture.leaveService
                .createType(TENANT, 1, new OaLeaveTypeReq("ANNUAL", "重复", true, null, null, null, null, null)))
                .hasMessageContaining("类型编码已存在");
            OaLeaveTypeResp annual = fixture.leaveService.types(TENANT, false)
                .stream()
                .filter(type -> type.code().equals("ANNUAL"))
                .findFirst()
                .orElseThrow();
            fixture.leaveService.updateType(TENANT, 1, annual
                .id(), new OaLeaveTypeReq(null, "年休假", true, null, "ENABLED", 1, null, annual.version()));
            assertThatThrownBy(() -> fixture.leaveService.updateType(TENANT, 1, annual
                .id(), new OaLeaveTypeReq(null, "冲突", true, null, "ENABLED", 1, null, annual.version())))
                .hasMessageContaining("已被其他人修改");
        });
    }

    private static OaLeaveBalanceResp grant(Fixture fixture, String days) {
        return fixture.leaveService.grant(TENANT, 1, new OaLeaveGrantReq(APPLICANT, fixture
            .typeId("ANNUAL"), 2026, new BigDecimal(days), "测试发放"));
    }

    private static void assertBalance(Fixture fixture, String total, String used, String occupied) {
        Map<String, Object> row = fixture.jdbc
            .queryForMap("SELECT total_days, used_days, occupied_days FROM oa_leave_balance WHERE user_id = ?", APPLICANT);
        assertThat((BigDecimal)row.get("TOTAL_DAYS")).isEqualByComparingTo(total);
        assertThat((BigDecimal)row.get("USED_DAYS")).isEqualByComparingTo(used);
        assertThat((BigDecimal)row.get("OCCUPIED_DAYS")).isEqualByComparingTo(occupied);
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private static Map<String, Object> form(String type,
                                            LocalDate start,
                                            String startHalf,
                                            LocalDate end,
                                            String endHalf) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(OaLeaveHook.FIELD_TYPE, type);
        form.put(OaLeaveHook.FIELD_START_DATE, start.toString());
        form.put(OaLeaveHook.FIELD_START_HALF, startHalf);
        form.put(OaLeaveHook.FIELD_END_DATE, end.toString());
        form.put(OaLeaveHook.FIELD_END_HALF, endHalf);
        form.put("reason", "家事");
        return form;
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-leave-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
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
        seedTypes(jdbc);

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-leave")
            .tenantId(Long.toString(TENANT))
            .addString("leave.bpmn20.xml", OaLeaveService.TEMPLATE_BPMN)
            .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(OaLeaveService.TEMPLATE_MODEL_KEY)
            .singleResult();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        HistoryService historyService = context.getBean(HistoryService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        StubStaffDirectory directory = new StubStaffDirectory();

        OaLeaveRepository leaveRepository = new OaLeaveRepository(jdbc);
        OaLeaveHook hook = new OaLeaveHook(leaveRepository, new OaAttendanceRepository(jdbc));
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaBusinessHooks hooks = new OaBusinessHooks(List.of(hook), launchRepository);
        OaLeaveService leaveService = new OaLeaveService(leaveRepository, hook, null, null, objectMapper, transactionManager);
        seedRelease(jdbc, leaveService.templateSchema(leaveService.types(TENANT, true)), definition.getId());

        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, historyService, repositoryService);
        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, hooks, leaveService, launchService, approvalService, runtimeService, taskService);
    }

    private static void seedAccounts(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (101, 101, 'alice', 'Alice', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (102, 101, 'bob', 'Bob', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (103, 101, 'carol', 'Carol', 20, 1, 0)");
    }

    private static void seedTypes(JdbcTemplate jdbc) {
        String sql = """
            INSERT INTO oa_leave_type
              (tenant_id, code, name, balance_required, max_days_per_request, status, sort, version, create_user, create_time)
            VALUES (101, ?, ?, ?, ?, ?, ?, 0, 1, CURRENT_TIMESTAMP)
            """;
        jdbc.update(sql, "ANNUAL", "年假", true, null, "ENABLED", 1);
        jdbc.update(sql, "SICK", "病假", false, null, "ENABLED", 2);
        jdbc.update(sql, "LIMITED", "婚假", false, new BigDecimal("1"), "ENABLED", 3);
        jdbc.update(sql, "OFF", "停用假", false, null, "DISABLED", 4);
        jdbc.update(sql, "OTHER", "他租户假", true, null, "ENABLED", 5);
        jdbc.update("UPDATE oa_leave_type SET tenant_id = 202 WHERE code = 'OTHER'");
    }

    private static void seedRelease(JdbcTemplate jdbc, String schema, String engineDefinitionId) {
        String bpmn = OaLeaveService.TEMPLATE_BPMN;
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, business_type, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (1, 101, 'leave_pilot', '请假审批', 'hr', '说明', 'PUBLISHED', 'LEAVE', 10, 1, 20, 30, 0, 7, CURRENT_TIMESTAMP, 0)
            """);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (10, 101, 1, 1, ?, ?, ?, 'leave_pilot', 1, 20, 30, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, bpmn, "a"
            .repeat(64), engineDefinitionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (20, 101, 'leave_request', '请假单', 'PUBLISHED', 0)");
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (30, 101, 20, 1, ?, ?, 6, 7, CURRENT_TIMESTAMP)
            """, schema, "b".repeat(64));
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE oa_cc_receipt (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT NOT NULL, process_instance_id VARCHAR(64), receiver_id BIGINT NOT NULL, receiver_name VARCHAR(64), source_task_id VARCHAR(64), cc_by BIGINT, status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', read_time TIMESTAMP, revoke_by BIGINT, revoke_time TIMESTAMP, create_time TIMESTAMP NOT NULL, CONSTRAINT uk_cc UNIQUE (tenant_id, application_id, receiver_id))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE oa_employee (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, employment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE oa_process_model (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, model_key VARCHAR(64) NOT NULL,
              name VARCHAR(100) NOT NULL, category VARCHAR(64), description VARCHAR(500), status VARCHAR(20) NOT NULL,
              business_type VARCHAR(32) NOT NULL DEFAULT 'GENERIC',
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
        createLeaveTables(jdbc);
        OaAttendanceServiceTest.createAttendanceTables(jdbc);
    }

    static void createLeaveTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_leave_type (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(32) NOT NULL,
              name VARCHAR(64) NOT NULL, balance_required BOOLEAN NOT NULL DEFAULT TRUE,
              max_days_per_request DECIMAL(6,1), status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
              sort INT NOT NULL DEFAULT 0, remark VARCHAR(500), version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_leave_type UNIQUE (tenant_id, code)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_work_calendar (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              calendar_date DATE NOT NULL, day_type VARCHAR(20) NOT NULL, name VARCHAR(64),
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_calendar UNIQUE (tenant_id, calendar_date)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_leave_balance (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
              leave_type_id BIGINT NOT NULL, period_year INT NOT NULL, total_days DECIMAL(8,1) NOT NULL DEFAULT 0,
              used_days DECIMAL(8,1) NOT NULL DEFAULT 0, occupied_days DECIMAL(8,1) NOT NULL DEFAULT 0,
              version INT NOT NULL DEFAULT 0, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_balance UNIQUE (tenant_id, user_id, leave_type_id, period_year)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_leave_request (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, leave_type_id BIGINT NOT NULL,
              leave_type_code VARCHAR(32) NOT NULL, leave_type_name VARCHAR(64) NOT NULL, start_date DATE NOT NULL,
              start_half VARCHAR(2) NOT NULL, end_date DATE NOT NULL, end_half VARCHAR(2) NOT NULL,
              days DECIMAL(8,1) NOT NULL, period_year INT NOT NULL, balance_id BIGINT, status VARCHAR(20) NOT NULL,
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_leave_request UNIQUE (tenant_id, application_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_leave_ledger (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              balance_id BIGINT NOT NULL, application_id BIGINT, action VARCHAR(20) NOT NULL,
              days DECIMAL(8,1) NOT NULL, total_after DECIMAL(8,1) NOT NULL, used_after DECIMAL(8,1) NOT NULL,
              occupied_after DECIMAL(8,1) NOT NULL, operator_id BIGINT NOT NULL, remark VARCHAR(500),
              create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_ledger UNIQUE (tenant_id, application_id, action)
            )
            """);
    }

    /** 员工目录桩：101/102/103 在职，101 的直属上级是 102，HR 岗位只有 103。 */
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
            return userId == APPLICANT ? SUPERVISOR : null;
        }

        @Override
        public boolean enabledPosition(long tenantId, String code) {
            return tenantId == TENANT && OaLeaveHook.HR_POSITION.equals(code);
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            return OaLeaveHook.HR_POSITION.equals(code) ? List.of(HR) : List.of();
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(SUPERVISOR);
        }
    }

    private record Fixture(JdbcTemplate jdbc, OaBusinessHooks hooks, OaLeaveService leaveService,
                           OaLaunchService launchService, OaApprovalService approvalService,
                           RuntimeService runtimeService, TaskService taskService) {

        private long submit(String requestId,
                            String type,
                            LocalDate start,
                            String startHalf,
                            LocalDate end,
                            String endHalf) {
            return launchService
                .submit(TENANT, APPLICANT, 10, new OaLaunchSubmitReq(requestId, "请假", form(type, start, startHalf, end, endHalf), List
                    .of()))
                .applicationId();
        }

        private long typeId(String code) {
            return jdbc
                .queryForObject("SELECT id FROM oa_leave_type WHERE tenant_id = 101 AND code = ?", Long.class, code);
        }

        private Task task(String key) {
            Task task = taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskDefinitionKey(key)
                .singleResult();
            assertThat(task).isNotNull();
            return task;
        }

        private String taskId(String key) {
            return task(key).getId();
        }

        private Object variable(long applicationId, String name) {
            String instanceId = jdbc
                .queryForObject("SELECT process_instance_id FROM oa_application WHERE id = ?", String.class, applicationId);
            return runtimeService.getVariable(instanceId, name);
        }

        private String applicationStatus(long applicationId) {
            return jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private String requestStatus(long applicationId) {
            return jdbc
                .queryForObject("SELECT status FROM oa_leave_request WHERE application_id = ?", String.class, applicationId);
        }

        private List<String> ledgerActions() {
            return jdbc.queryForList("SELECT action FROM oa_leave_ledger ORDER BY id", String.class);
        }

        private long count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        }
    }
}
