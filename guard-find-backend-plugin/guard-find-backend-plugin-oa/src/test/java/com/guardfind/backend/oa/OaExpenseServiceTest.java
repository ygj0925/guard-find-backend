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
import com.guardfind.backend.oa.expense.OaExpenseHook;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaExpenseCategoryReq;
import com.guardfind.backend.oa.model.OaExpenseCategoryResp;
import com.guardfind.backend.oa.model.OaExpenseDetailResp;
import com.guardfind.backend.oa.model.OaExpensePayReq;
import com.guardfind.backend.oa.model.OaExpensePreviewResp;
import com.guardfind.backend.oa.model.OaExpenseResp;
import com.guardfind.backend.oa.model.OaLaunchDetailResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaExpenseRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaExpenseService;
import com.guardfind.backend.oa.service.OaLaunchService;
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
 * 11.2 报销试点：明细校验、金额分支、通过待支付、财务登记支付、驳回/撤回释放发票与并发发票去重。
 */
class OaExpenseServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long FINANCE = 103;
    private static final long GM = 104;
    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    @Test
    void templateSchemaAndBpmnPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            String schema = fixture.expenseService.templateSchema(fixture.expenseService.categories(TENANT, true));
            assertThat(new OaFormSchemaValidator().validate(schema, true).valid()).isTrue();
            assertThat(fixture.hooks.validateForm(OaExpenseHook.BUSINESS_TYPE, schema)).isEmpty();
            assertThat(fixture.hooks
                .validateForm(OaExpenseHook.BUSINESS_TYPE, "{\"fields\":[{\"key\":\"expenseItems\",\"type\":\"text\"}]}"))
                .isNotEmpty();

            OaBpmnValidationResult bpmn = new OaBpmnValidator().validate(OaExpenseService.TEMPLATE_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(bpmn.errors()).isEmpty();
            assertThat(bpmn.processKey()).isEqualTo(OaExpenseService.TEMPLATE_MODEL_KEY);
            assertThat(bpmn.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaExpenseHook.VAR_FINANCE_REVIEWER, OaExpenseHook.VAR_FINAL_APPROVER);

            OaLaunchDetailResp detail = fixture.launchService.detail(TENANT, APPLICANT, 10);
            assertThat(detail.businessType()).isEqualTo(OaExpenseHook.BUSINESS_TYPE);
            assertThat(detail.assigneeVariables()).anyMatch(v -> v.name()
                .equals(OaExpenseHook.VAR_FINANCE_REVIEWER) && "POSITION".equals(v
                    .suggestedType()) && OaExpenseHook.FINANCE_POSITION.equals(v.suggestedValue()))
                .anyMatch(v -> v.name().equals(OaExpenseHook.VAR_FINAL_APPROVER) && "POSITION".equals(v
                    .suggestedType()) && OaExpenseHook.GM_POSITION.equals(v.suggestedValue()));
        });
    }

    @Test
    void smallClaimSkipsGmThenFinancePays() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture
                .submit("req-small", row("TRAFFIC", "120.5", null), row("MEAL", "300", "inv-001 "));
            long expenseId = fixture.expenseId(applicationId);
            assertThat(fixture.expenseStatus(applicationId)).isEqualTo(OaExpenseHook.STATUS_APPROVING);
            assertThat(fixture.variable(applicationId, OaExpenseHook.VAR_AMOUNT)).isEqualTo(420.5);

            fixture.approvalService.approve(TENANT, SUPERVISOR, fixture.taskId("supervisorApprove"), action("a1"));
            Task financeTask = fixture.task("financeApprove");
            assertThat(fixture.taskService.getIdentityLinksForTask(financeTask.getId())).anyMatch(link -> Long
                .toString(FINANCE)
                .equals(link.getUserId()));
            fixture.approvalService.approve(TENANT, FINANCE, financeTask.getId(), action("a2"));
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("APPROVED");
            assertThat(fixture.expenseStatus(applicationId)).isEqualTo(OaExpenseHook.STATUS_PENDING_PAYMENT);

            OaExpenseDetailResp detail = fixture.expenseService.myDetail(TENANT, APPLICANT, expenseId);
            assertThat(detail.expense().totalAmount()).isEqualByComparingTo("420.50");
            assertThat(detail.items()).hasSize(2);
            assertThat(detail.items().get(1).invoiceNo()).isEqualTo("INV-001");
            assertThat(detail.items().get(1).categoryName()).isEqualTo("餐饮");
            assertThatThrownBy(() -> fixture.expenseService.myDetail(TENANT, SUPERVISOR, expenseId))
                .hasMessageContaining("报销单不存在");

            assertThatThrownBy(() -> fixture.expenseService.pay(TENANT, APPLICANT, expenseId, pay("420.50", YESTERDAY)))
                .hasMessageContaining("不能为本人的报销登记支付");
            assertThatThrownBy(() -> fixture.expenseService.pay(TENANT, FINANCE, expenseId, pay("420", YESTERDAY)))
                .hasMessageContaining("实付金额 420 元与报销合计 420.5 元不一致");
            assertThatThrownBy(() -> fixture.expenseService.pay(TENANT, FINANCE, expenseId, pay("420.50", LocalDate
                .now()
                .plusDays(1)))).hasMessageContaining("支付日期不能晚于今天");

            OaExpenseResp paid = fixture.expenseService.pay(TENANT, FINANCE, expenseId, pay("420.50", YESTERDAY));
            assertThat(paid.status()).isEqualTo(OaExpenseHook.STATUS_PAID);
            assertThat(paid.payerName()).isEqualTo("Carol");
            assertThat(paid.payReference()).isEqualTo("TX-1");
            assertThatThrownBy(() -> fixture.expenseService.pay(TENANT, FINANCE, expenseId, pay("420.50", YESTERDAY)))
                .hasMessageContaining("该报销已登记支付");
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_outbox WHERE event_type = ?", Long.class, OaExpenseService.EVENT_EXPENSE_PAID))
                .isEqualTo(1);

            fixture.hooks.finished(TENANT, applicationId, "APPROVED", FINANCE, LocalDateTime.now());
            fixture.hooks.finished(TENANT, applicationId, "CANCELLED", 1, LocalDateTime.now());
            assertThat(fixture.expenseStatus(applicationId)).isEqualTo(OaExpenseHook.STATUS_PAID);
            assertThat(fixture.invoiceCount(applicationId)).isEqualTo(1);

            assertThat(fixture.expenseService.records(TENANT, "PAID", null, null, 1, 10)).singleElement()
                .satisfies(row -> assertThat(row.applicantName()).isEqualTo("Alice"));
            assertThat(fixture.expenseService.countRecords(TENANT, "PENDING_PAYMENT", null, null)).isZero();
            assertThatThrownBy(() -> fixture.expenseService.records(TENANT, "BOGUS", null, null, 1, 10))
                .hasMessageContaining("报销单状态无效");
        });
    }

    @Test
    void largeClaimRoutesToGmAndRejectReleasesInvoice() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long applicationId = fixture
                .submit("req-large", row("TRAFFIC", "6000", null), row("OFFICE", "100", "INV-9"));
            fixture.approvalService.approve(TENANT, SUPERVISOR, fixture.taskId("supervisorApprove"), action("a1"));
            fixture.approvalService.approve(TENANT, FINANCE, fixture.taskId("financeApprove"), action("a2"));
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("RUNNING");
            Task gmTask = fixture.task("gmApprove");
            assertThat(fixture.taskService.getIdentityLinksForTask(gmTask.getId())).anyMatch(link -> Long.toString(GM)
                .equals(link.getUserId()));

            long expenseId = fixture.expenseId(applicationId);
            assertThatThrownBy(() -> fixture.expenseService.pay(TENANT, FINANCE, expenseId, pay("6100", YESTERDAY)))
                .hasMessageContaining("尚未审批通过");

            fixture.approvalService.reject(TENANT, GM, gmTask.getId(), new OaApprovalActionReq("r1", "超预算"));
            assertThat(fixture.applicationStatus(applicationId)).isEqualTo("REJECTED");
            assertThat(fixture.expenseStatus(applicationId)).isEqualTo(OaExpenseHook.STATUS_CLOSED);
            assertThat(fixture.invoiceCount(applicationId)).isZero();
            assertThatThrownBy(() -> fixture.expenseService.pay(TENANT, FINANCE, expenseId, pay("6100", YESTERDAY)))
                .hasMessageContaining("报销单已关闭");

            long again = fixture.submit("req-again", row("OFFICE", "100", "inv-9"));
            assertThat(fixture.invoiceCount(again)).isEqualTo(1);
        });
    }

    @Test
    void duplicateInvoiceRollsBackWholeSubmit() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            fixture.submit("req-first", row("OFFICE", "50", "abc-1"));

            assertThatThrownBy(() -> fixture.submit("req-dup", row("OFFICE", "60", "ABC-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发票号 ABC-1 已被其他报销使用");
            assertThatThrownBy(() -> fixture.submit("req-self", row("OFFICE", "60", "x1"), row("MEAL", "20", "X 1")))
                .hasMessageContaining("第 2 行发票号 X1 在本次报销中重复");
            assertThatThrownBy(() -> fixture.submit("req-limit", row("MEAL", "600", "M-1")))
                .hasMessageContaining("第 1 行餐饮单笔不能超过 500 元，本行 600 元");
            assertThatThrownBy(() -> fixture.submit("req-noinv", row("MEAL", "100", null)))
                .hasMessageContaining("第 1 行餐饮必须填写发票号");

            assertThat(fixture.count("oa_application")).isEqualTo(1);
            assertThat(fixture.count("oa_expense")).isEqualTo(1);
            assertThat(fixture.count("oa_expense_item")).isEqualTo(1);
            assertThat(fixture.count("oa_expense_invoice")).isEqualTo(1);
            assertThat(fixture.count("oa_request_dedup")).isEqualTo(1);
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isEqualTo(1);
        });
    }

    @Test
    void concurrentSubmitsClaimInvoiceOnce() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "req-race-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        fixture.submit(requestId, row("OFFICE", "88", "RACE-1"));
                        return true;
                    } catch (BusinessException e) {
                        assertThat(e.getMessage()).contains("已被其他报销使用");
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
            assertThat(fixture.count("oa_expense")).isEqualTo(1);
            assertThat(fixture.count("oa_expense_invoice")).isEqualTo(1);
        });
    }

    @Test
    void replayWithdrawAndPreview() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long first = fixture.submit("req-replay", row("OFFICE", "10", "W-1"));
            assertThat(fixture.submit("req-replay", row("OFFICE", "10", "W-1"))).isEqualTo(first);
            assertThat(fixture.count("oa_expense")).isEqualTo(1);

            fixture.approvalService.withdraw(TENANT, APPLICANT, first, action("w1"));
            assertThat(fixture.applicationStatus(first)).isEqualTo("WITHDRAWN");
            assertThat(fixture.expenseStatus(first)).isEqualTo(OaExpenseHook.STATUS_CLOSED);
            assertThat(fixture.invoiceCount(first)).isZero();

            OaExpensePreviewResp ok = fixture.expenseService
                .preview(TENANT, APPLICANT, form(row("TRAFFIC", "1.5", null), blankRow(), row("OFFICE", "2", "W-1")));
            assertThat(ok.totalAmount()).isEqualByComparingTo("3.50");
            assertThat(ok.itemCount()).isEqualTo(2);
            assertThat(ok.message()).isNull();

            assertThat(preview(fixture, row("OFF", "1", null))).contains("费用类型不存在或已停用: OFF");
            assertThat(preview(fixture, row("OTHER", "1", null))).contains("费用类型不存在或已停用: OTHER");
            assertThat(preview(fixture, row("TRAFFIC", "0", null))).contains("金额必须大于 0");
            assertThat(preview(fixture, row("TRAFFIC", "1.234", null))).contains("金额最多两位小数");
            assertThat(preview(fixture, row("OFFICE", "1", "发票#1"))).contains("发票号只能包含字母、数字和短横线");
            Map<String, Object> future = row("TRAFFIC", "1", null);
            future.put(OaExpenseHook.COL_DATE, LocalDate.now().plusDays(1).toString());
            assertThat(preview(fixture, future)).contains("发生日期不能晚于今天");
            assertThat(fixture.expenseService.preview(TENANT, APPLICANT, form(blankRow())).message())
                .contains("请至少填写一条报销明细");
            assertThat(fixture.expenseService.preview(TENANT, APPLICANT, Map.of()).message()).contains("请至少填写一条报销明细");
        });
    }

    @Test
    void categoryCrudGuardsCodeAndVersion() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture.expenseService
                .createCategory(TENANT, 1, new OaExpenseCategoryReq("TRAFFIC", "重复", null, false, null, null, null, null)))
                .hasMessageContaining("类型编码已存在");
            OaExpenseCategoryResp created = fixture.expenseService
                .createCategory(TENANT, 1, new OaExpenseCategoryReq("TRAIN", "培训", new BigDecimal("2000"), true, null, 50, null, null));
            assertThat(created.status()).isEqualTo("ENABLED");
            assertThat(created.perItemLimit()).isEqualByComparingTo("2000");

            fixture.expenseService.updateCategory(TENANT, 1, created
                .id(), new OaExpenseCategoryReq(null, "培训费", null, false, "DISABLED", 50, null, created.version()));
            assertThatThrownBy(() -> fixture.expenseService.updateCategory(TENANT, 1, created
                .id(), new OaExpenseCategoryReq(null, "冲突", null, false, "ENABLED", 50, null, created.version())))
                .hasMessageContaining("已被其他人修改");
            assertThat(fixture.expenseService.categories(TENANT, true)).extracting(OaExpenseCategoryResp::code)
                .containsExactly("TRAFFIC", "MEAL", "OFFICE");
            assertThat(preview(fixture, row("TRAIN", "1", null))).contains("不存在或已停用");
        });
    }

    private static String preview(Fixture fixture, Map<String, Object> row) {
        return fixture.expenseService.preview(TENANT, APPLICANT, form(row)).message();
    }

    private static OaExpensePayReq pay(String amount, LocalDate date) {
        return new OaExpensePayReq(new BigDecimal(amount), date, "BANK_TRANSFER", "TX-1", null, null);
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private static Map<String, Object> row(String category, String amount, String invoiceNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(OaExpenseHook.COL_CATEGORY, category);
        row.put(OaExpenseHook.COL_AMOUNT, new BigDecimal(amount));
        row.put(OaExpenseHook.COL_DATE, YESTERDAY.toString());
        if (invoiceNo != null) {
            row.put(OaExpenseHook.COL_INVOICE, invoiceNo);
        }
        row.put(OaExpenseHook.COL_DESCRIPTION, "测试");
        return row;
    }

    private static Map<String, Object> blankRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(OaExpenseHook.COL_CATEGORY, "");
        row.put(OaExpenseHook.COL_INVOICE, " ");
        return row;
    }

    @SafeVarargs
    private static Map<String, Object> form(Map<String, Object>... rows) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("reason", "出差");
        form.put(OaExpenseHook.FIELD_ITEMS, List.of(rows));
        return form;
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-expense-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
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
        OaExpenseRepository expenseRepository = new OaExpenseRepository(jdbc);
        seedCategories(expenseRepository);

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-expense")
            .tenantId(Long.toString(TENANT))
            .addString("expense.bpmn20.xml", OaExpenseService.TEMPLATE_BPMN)
            .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(OaExpenseService.TEMPLATE_MODEL_KEY)
            .singleResult();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        HistoryService historyService = context.getBean(HistoryService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        StubStaffDirectory directory = new StubStaffDirectory();

        OaTravelRepository travelRepository = new OaTravelRepository(jdbc);
        OaExpenseHook hook = new OaExpenseHook(expenseRepository, travelRepository);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaBusinessHooks hooks = new OaBusinessHooks(List.of(hook), launchRepository);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaExpenseService expenseService = new OaExpenseService(expenseRepository, travelRepository, hook, null, null, outboxService, objectMapper, transactionManager);
        seedRelease(jdbc, expenseService.templateSchema(expenseService.categories(TENANT, true)), definition.getId());

        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, historyService, repositoryService);
        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, hooks, expenseService, launchService, approvalService, runtimeService, taskService);
    }

    static void seedAccounts(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (101, 101, 'alice', 'Alice', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (102, 101, 'bob', 'Bob', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (103, 101, 'carol', 'Carol', 20, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (104, 101, 'dave', 'Dave', 20, 1, 0)");
    }

    static void seedCategories(OaExpenseRepository repository) {
        LocalDateTime now = LocalDateTime.now();
        repository.insertCategory(TENANT, "TRAFFIC", "交通", null, false, "ENABLED", 1, null, 1, now);
        repository.insertCategory(TENANT, "MEAL", "餐饮", new BigDecimal("500.00"), true, "ENABLED", 2, null, 1, now);
        repository.insertCategory(TENANT, "OFFICE", "办公用品", null, true, "ENABLED", 3, null, 1, now);
        repository.insertCategory(TENANT, "OFF", "停用类型", null, false, "DISABLED", 4, null, 1, now);
        repository.insertCategory(202, "OTHER", "他租户类型", null, false, "ENABLED", 5, null, 1, now);
    }

    private static void seedRelease(JdbcTemplate jdbc, String schema, String engineDefinitionId) {
        String bpmn = OaExpenseService.TEMPLATE_BPMN;
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, business_type, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (1, 101, 'expense_pilot', '费用报销', 'finance', '说明', 'PUBLISHED', 'EXPENSE', 10, 1, 20, 30, 0, 7, CURRENT_TIMESTAMP, 0)
            """);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (10, 101, 1, 1, ?, ?, ?, 'expense_pilot', 1, 20, 30, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, bpmn, "a"
            .repeat(64), engineDefinitionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (20, 101, 'expense_claim', '报销单', 'PUBLISHED', 0)");
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (30, 101, 20, 1, ?, ?, 3, 7, CURRENT_TIMESTAMP)
            """, schema, "b".repeat(64));
    }

    static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE oa_cc_receipt (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT NOT NULL, process_instance_id VARCHAR(64), receiver_id BIGINT NOT NULL, receiver_name VARCHAR(64), source_task_id VARCHAR(64), cc_by BIGINT, status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', read_time TIMESTAMP, revoke_by BIGINT, revoke_time TIMESTAMP, create_time TIMESTAMP NOT NULL, CONSTRAINT uk_cc UNIQUE (tenant_id, application_id, receiver_id))");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, parent_id BIGINT NOT NULL DEFAULT 0, deleted BIGINT NOT NULL DEFAULT 0)");
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
        jdbc.execute("""
            CREATE TABLE oa_expense_category (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(32) NOT NULL,
              name VARCHAR(64) NOT NULL, per_item_limit DECIMAL(12,2), invoice_required BOOLEAN NOT NULL DEFAULT FALSE,
              status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', sort INT NOT NULL DEFAULT 0, remark VARCHAR(500),
              version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_expense_category UNIQUE (tenant_id, code)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_expense (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, total_amount DECIMAL(14,2) NOT NULL,
              item_count INT NOT NULL, status VARCHAR(20) NOT NULL, travel_id BIGINT,
              over_budget BOOLEAN NOT NULL DEFAULT FALSE, write_off_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
              paid_amount DECIMAL(14,2), pay_date DATE,
              pay_method VARCHAR(20), pay_reference VARCHAR(64), pay_remark VARCHAR(500), payer_id BIGINT,
              paid_time TIMESTAMP, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_expense_application UNIQUE (tenant_id, application_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_expense_item (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              expense_id BIGINT NOT NULL, application_id BIGINT NOT NULL, line_no INT NOT NULL,
              category_code VARCHAR(32) NOT NULL, category_name VARCHAR(64) NOT NULL, amount DECIMAL(12,2) NOT NULL,
              expense_date DATE, invoice_no VARCHAR(64), description VARCHAR(500),
              CONSTRAINT uk_expense_line UNIQUE (tenant_id, expense_id, line_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_expense_invoice (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              invoice_no VARCHAR(64) NOT NULL, application_id BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_expense_invoice UNIQUE (tenant_id, invoice_no)
            )
            """);
        createTravelTables(jdbc);
    }

    static void createTravelTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_travel (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, travel_no VARCHAR(32),
              destination VARCHAR(100) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL, days INT NOT NULL,
              budget_amount DECIMAL(14,2) NOT NULL, status VARCHAR(20) NOT NULL, create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP,
              CONSTRAINT uk_travel_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_travel_no UNIQUE (tenant_id, travel_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_loan (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, travel_id BIGINT,
              amount DECIMAL(14,2) NOT NULL, balance DECIMAL(14,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL,
              expected_repay_date DATE, disburse_date DATE, disburse_method VARCHAR(20), disburse_reference VARCHAR(64),
              disburser_id BIGINT, disbursed_time TIMESTAMP, settled_time TIMESTAMP, create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP,
              CONSTRAINT uk_loan_application UNIQUE (tenant_id, application_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_loan_ledger (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              loan_id BIGINT NOT NULL, action VARCHAR(20) NOT NULL, amount DECIMAL(14,2) NOT NULL,
              balance_after DECIMAL(14,2) NOT NULL, expense_id BIGINT, request_id VARCHAR(64), biz_date DATE,
              method VARCHAR(20), reference VARCHAR(64), operator_id BIGINT NOT NULL, remark VARCHAR(500),
              create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_ledger_loan_expense UNIQUE (tenant_id, loan_id, expense_id),
              CONSTRAINT uk_ledger_request UNIQUE (tenant_id, request_id)
            )
            """);
    }

    /** 员工目录桩：101–104 在职，101 的直属上级是 102，FINANCE 岗位只有 103，GM 岗位只有 104。 */
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
            return tenantId == TENANT && (OaExpenseHook.FINANCE_POSITION.equals(code) || OaExpenseHook.GM_POSITION
                .equals(code));
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            if (OaExpenseHook.FINANCE_POSITION.equals(code)) {
                return List.of(FINANCE);
            }
            return OaExpenseHook.GM_POSITION.equals(code) ? List.of(GM) : List.of();
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(SUPERVISOR);
        }
    }

    private record Fixture(JdbcTemplate jdbc, OaBusinessHooks hooks, OaExpenseService expenseService,
                           OaLaunchService launchService, OaApprovalService approvalService,
                           RuntimeService runtimeService, TaskService taskService) {

        @SafeVarargs
        private long submit(String requestId, Map<String, Object>... rows) {
            return launchService.submit(TENANT, APPLICANT, 10, new OaLaunchSubmitReq(requestId, "报销", form(rows), List
                .of())).applicationId();
        }

        private long expenseId(long applicationId) {
            return jdbc.queryForObject("SELECT id FROM oa_expense WHERE application_id = ?", Long.class, applicationId);
        }

        private String expenseStatus(long applicationId) {
            return jdbc
                .queryForObject("SELECT status FROM oa_expense WHERE application_id = ?", String.class, applicationId);
        }

        private long invoiceCount(long applicationId) {
            return jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_expense_invoice WHERE application_id = ?", Long.class, applicationId);
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

        private long count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        }
    }
}
