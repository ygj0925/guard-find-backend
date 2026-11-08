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
import com.guardfind.backend.oa.model.OaExpenseDetailResp;
import com.guardfind.backend.oa.model.OaExpensePayReq;
import com.guardfind.backend.oa.model.OaExpensePreviewResp;
import com.guardfind.backend.oa.model.OaExpenseResp;
import com.guardfind.backend.oa.model.OaExpenseWriteOffReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLoanDetailResp;
import com.guardfind.backend.oa.model.OaLoanDisburseReq;
import com.guardfind.backend.oa.model.OaLoanLedgerResp;
import com.guardfind.backend.oa.model.OaLoanRepayReq;
import com.guardfind.backend.oa.model.OaLoanResp;
import com.guardfind.backend.oa.model.OaTravelDetailResp;
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
import com.guardfind.backend.oa.service.OaTravelService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.travel.OaLoanHook;
import com.guardfind.backend.oa.travel.OaTravelHook;
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
 * 12.1 出差借款：出差重叠、借款预算、放款/还款台账、报销超预算复核与借款核销。
 */
class OaTravelLoanServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long FINANCE = 103;
    private static final long GM = 104;
    private static final long EXPENSE_RELEASE = 10;
    private static final long TRAVEL_RELEASE = 11;
    private static final long LOAN_RELEASE = 12;
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Test
    void templatesPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            String travelSchema = fixture.travelService.travelSchema();
            String loanSchema = fixture.travelService.loanSchema();
            assertThat(new OaFormSchemaValidator().validate(travelSchema, true).valid()).isTrue();
            assertThat(new OaFormSchemaValidator().validate(loanSchema, true).valid()).isTrue();
            assertThat(fixture.hooks.validateForm(OaTravelHook.BUSINESS_TYPE, travelSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaLoanHook.BUSINESS_TYPE, loanSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaTravelHook.BUSINESS_TYPE, loanSchema)).anyMatch(e -> e
                .contains("出差表单缺少字段 destination"));
            assertThat(fixture.hooks.validateForm(OaLoanHook.BUSINESS_TYPE, travelSchema)).anyMatch(e -> e
                .contains("借款表单缺少字段 amount"));

            OaBpmnValidationResult travel = new OaBpmnValidator().validate(OaTravelService.TRAVEL_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(travel.errors()).isEmpty();
            assertThat(travel.processKey()).isEqualTo(OaTravelService.TRAVEL_MODEL_KEY);
            assertThat(travel.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaTravelHook.VAR_FINANCE_REVIEWER);

            OaBpmnValidationResult loan = new OaBpmnValidator().validate(OaTravelService.LOAN_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(loan.errors()).isEmpty();
            assertThat(loan.processKey()).isEqualTo(OaTravelService.LOAN_MODEL_KEY);
            assertThat(loan.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaLoanHook.VAR_FINANCE_REVIEWER, OaLoanHook.VAR_FINAL_APPROVER);
        });
    }

    @Test
    void travelLoanExpenseWriteOffHappyPath() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long travelApp = fixture.submitTravel("t1", TODAY.plusDays(1), TODAY.plusDays(3), "2000");
            assertThat(fixture.variable(travelApp, OaTravelHook.VAR_DAYS)).isEqualTo(3);
            assertThat(fixture.travelStatus(travelApp)).isEqualTo(OaTravelHook.STATUS_APPROVING);
            String travelNo = fixture.travelNo(travelApp);
            assertThat(travelNo).matches("TR\\d{6}\\d{4,}");

            assertThatThrownBy(() -> fixture.submitLoan("l0", "100", travelNo))
                .hasMessageContaining("出差单 " + travelNo + " 尚未审批通过");
            fixture.approve(SUPERVISOR, travelApp, "a-t1");
            assertThat(fixture.applicationStatus(travelApp)).isEqualTo("APPROVED");
            assertThat(fixture.travelStatus(travelApp)).isEqualTo(OaTravelHook.STATUS_APPROVED);

            assertThatThrownBy(() -> fixture.submitTravel("t2", TODAY.plusDays(3), TODAY.plusDays(5), "100"))
                .hasMessageContaining("与出差 " + travelNo + " 时间重叠");

            long loanApp = fixture.submitLoan("l1", "1500", travelNo);
            long loanId = fixture.loanId(loanApp);
            assertThatThrownBy(() -> fixture.submitLoan("l2", "600", travelNo))
                .hasMessageContaining("借款合计 2100 元超出出差预算 2000 元");
            assertThatThrownBy(() -> fixture.travelService
                .disburse(TENANT, FINANCE, loanId, disburse("1500", YESTERDAY))).hasMessageContaining("借款尚未审批通过");
            fixture.approve(SUPERVISOR, loanApp, "a-l1");
            fixture.approve(FINANCE, loanApp, "a-l2");
            assertThat(fixture.applicationStatus(loanApp)).isEqualTo("APPROVED");
            assertThat(fixture.loanStatus(loanApp)).isEqualTo(OaLoanHook.STATUS_PENDING_DISBURSE);

            assertThatThrownBy(() -> fixture.travelService
                .disburse(TENANT, APPLICANT, loanId, disburse("1500", YESTERDAY))).hasMessageContaining("不能为本人的借款登记放款");
            assertThatThrownBy(() -> fixture.travelService
                .disburse(TENANT, FINANCE, loanId, disburse("1400", YESTERDAY)))
                .hasMessageContaining("放款金额 1400 元与借款金额 1500 元不一致");
            assertThatThrownBy(() -> fixture.travelService.disburse(TENANT, FINANCE, loanId, disburse("1500", TODAY
                .plusDays(1)))).hasMessageContaining("放款日期不能晚于今天");
            OaLoanResp disbursed = fixture.travelService.disburse(TENANT, FINANCE, loanId, disburse("1500", YESTERDAY));
            assertThat(disbursed.status()).isEqualTo(OaLoanHook.STATUS_OUTSTANDING);
            assertThat(disbursed.balance()).isEqualByComparingTo("1500");
            assertThat(disbursed.disburserName()).isEqualTo("Carol");
            assertThat(disbursed.travelNo()).isEqualTo(travelNo);
            assertThatThrownBy(() -> fixture.travelService
                .disburse(TENANT, FINANCE, loanId, disburse("1500", YESTERDAY))).hasMessageContaining("该借款已登记放款");
            assertThat(fixture
                .count("SELECT COUNT(*) FROM oa_outbox WHERE event_type = '" + OaTravelService.EVENT_LOAN_DISBURSED + "'"))
                .isEqualTo(1);
            assertThat(fixture.travelService.outstandingLoans(TENANT, APPLICANT)).extracting(OaLoanResp::id)
                .containsExactly(loanId);

            OaExpensePreviewResp preview = fixture.expenseService
                .preview(TENANT, APPLICANT, expenseForm(travelNo, "2500"));
            assertThat(preview.overBudget()).isTrue();
            assertThat(preview.budgetHint()).contains("出差 " + travelNo + " 预算 2000 元").contains("已超预算");

            long expenseApp = fixture.submitExpense("e1", travelNo, "2500");
            assertThat(fixture.variable(expenseApp, OaExpenseHook.VAR_OVER_BUDGET)).isEqualTo(true);
            fixture.approve(SUPERVISOR, expenseApp, "a-e1");
            fixture.approve(FINANCE, expenseApp, "a-e2");
            assertThat(fixture.applicationStatus(expenseApp)).isEqualTo("RUNNING");
            Task gmTask = fixture.task(expenseApp);
            assertThat(gmTask.getTaskDefinitionKey()).isEqualTo("gmApprove");
            fixture.approvalService.approve(TENANT, GM, gmTask.getId(), action("a-e3"));
            assertThat(fixture.expenseStatus(expenseApp)).isEqualTo(OaExpenseHook.STATUS_PENDING_PAYMENT);

            long expenseId = fixture.expenseId(expenseApp);
            OaExpenseResp paid = fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("1000", writeOff(loanId, "1500")));
            assertThat(paid.status()).isEqualTo(OaExpenseHook.STATUS_PAID);
            assertThat(paid.writeOffAmount()).isEqualByComparingTo("1500");
            assertThat(paid.paidAmount()).isEqualByComparingTo("1000");
            assertThat(paid.travelNo()).isEqualTo(travelNo);
            assertThat(paid.overBudget()).isTrue();

            OaLoanDetailResp loan = fixture.travelService.loanDetail(TENANT, APPLICANT, loanId);
            assertThat(loan.loan().status()).isEqualTo(OaLoanHook.STATUS_SETTLED);
            assertThat(loan.loan().balance()).isEqualByComparingTo("0");
            assertThat(loan.loan().settledTime()).isNotNull();
            assertThat(loan.ledger()).extracting(OaLoanLedgerResp::action).containsExactly("DISBURSE", "WRITE_OFF");
            assertThat(loan.ledger().get(1).expenseId()).isEqualTo(expenseId);

            OaExpenseDetailResp expense = fixture.expenseService.detail(TENANT, expenseId);
            assertThat(expense.writeOffs()).singleElement()
                .satisfies(row -> assertThat(row.amount()).isEqualByComparingTo("1500"));

            long travelId = fixture.travelId(travelApp);
            OaTravelDetailResp travel = fixture.travelService.travelDetail(TENANT, APPLICANT, travelId);
            assertThat(travel.travel().loanAmount()).isEqualByComparingTo("1500");
            assertThat(travel.travel().loanBalance()).isEqualByComparingTo("0");
            assertThat(travel.travel().expenseAmount()).isEqualByComparingTo("2500");
            assertThat(travel.loans()).hasSize(1);
            assertThat(travel.expenses()).hasSize(1);
            assertThatThrownBy(() -> fixture.travelService.travelDetail(TENANT, SUPERVISOR, travelId))
                .hasMessageContaining("出差单不存在");
            assertThat(fixture.travelService.travels(TENANT, "APPROVED", travelNo, null, 1, 10)).hasSize(1);
            assertThatThrownBy(() -> fixture.travelService.travels(TENANT, "BOGUS", null, null, 1, 10))
                .hasMessageContaining("出差单状态无效");
        });
    }

    @Test
    void writeOffGuardsAndRepayLedger() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long travelApp = fixture.submitTravel("t1", TODAY, TODAY.plusDays(1), "5000");
            fixture.approve(SUPERVISOR, travelApp, "a-t1");
            fixture.approve(FINANCE, travelApp, "a-t2");
            assertThat(fixture.travelStatus(travelApp)).isEqualTo(OaTravelHook.STATUS_APPROVED);
            String travelNo = fixture.travelNo(travelApp);

            long loanA = fixture.loanId(fixture.approvedLoan("la", "1000", travelNo));
            long loanB = fixture.loanId(fixture.approvedLoan("lb", "500", travelNo));
            fixture.travelService.disburse(TENANT, FINANCE, loanA, disburse("1000", YESTERDAY));

            long expenseApp = fixture.submitExpense("e1", travelNo, "800");
            assertThat(fixture.variable(expenseApp, OaExpenseHook.VAR_OVER_BUDGET)).isEqualTo(false);
            fixture.approve(SUPERVISOR, expenseApp, "a-e1");
            fixture.approve(FINANCE, expenseApp, "a-e2");
            long expenseId = fixture.expenseId(expenseApp);
            assertThat(fixture.expenseStatus(expenseApp)).isEqualTo(OaExpenseHook.STATUS_PENDING_PAYMENT);

            assertThatThrownBy(() -> fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("600", writeOff(loanA, "100"), writeOff(loanA, "100"))))
                .hasMessageContaining("同一笔借款不能重复核销");
            assertThatThrownBy(() -> fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("0", writeOff(loanA, "900"))))
                .hasMessageContaining("核销合计 900 元超过报销合计 800 元");
            assertThatThrownBy(() -> fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("600", writeOff(loanA, "300"))))
                .hasMessageContaining("实付金额 600 元应等于报销合计 800 元减核销 300 元");
            assertThatThrownBy(() -> fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("700", writeOff(loanB, "100"))))
                .hasMessageContaining("借款 " + loanB + " 不是待还款状态，不能核销");
            assertThatThrownBy(() -> fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("700", writeOff(loanA, "50"), writeOff(loanB, "50"))))
                .hasMessageContaining("不是待还款状态");
            assertThat(fixture.loanBalance(loanA)).isEqualByComparingTo("1000");
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_loan_ledger WHERE action = 'WRITE_OFF'")).isZero();

            OaExpenseResp paid = fixture.expenseService
                .pay(TENANT, FINANCE, expenseId, pay("500", writeOff(loanA, "300")));
            assertThat(paid.writeOffAmount()).isEqualByComparingTo("300");
            assertThat(fixture.loanBalance(loanA)).isEqualByComparingTo("700");
            assertThat(fixture.travelService.loanDetail(TENANT, null, loanA).loan().status())
                .isEqualTo(OaLoanHook.STATUS_OUTSTANDING);

            assertThatThrownBy(() -> fixture.travelService.repay(TENANT, APPLICANT, loanA, repay("r0", "100")))
                .hasMessageContaining("不能为本人的借款登记还款");
            assertThatThrownBy(() -> fixture.travelService.repay(TENANT, FINANCE, loanA, repay("r0", "800")))
                .hasMessageContaining("还款金额 800 元超过借款余额 700 元");
            assertThat(fixture.travelService.repay(TENANT, FINANCE, loanA, repay("r1", "200")).balance())
                .isEqualByComparingTo("500");
            assertThat(fixture.travelService.repay(TENANT, FINANCE, loanA, repay("r1", "200")).balance())
                .isEqualByComparingTo("500");
            assertThatThrownBy(() -> fixture.travelService.repay(TENANT, FINANCE, loanB, repay("r1", "200")))
                .hasMessageContaining("请求号已被使用");
            assertThatThrownBy(() -> fixture.travelService.repay(TENANT, FINANCE, loanB, repay("r4", "100")))
                .hasMessageContaining("借款尚未放款，不能登记还款");
            OaLoanResp settled = fixture.travelService.repay(TENANT, FINANCE, loanA, repay("r2", "500"));
            assertThat(settled.status()).isEqualTo(OaLoanHook.STATUS_SETTLED);
            assertThat(settled.settledTime()).isNotNull();
            assertThatThrownBy(() -> fixture.travelService.repay(TENANT, FINANCE, loanA, repay("r3", "1")))
                .hasMessageContaining("借款已还清");

            OaLoanDetailResp detail = fixture.travelService.loanDetail(TENANT, APPLICANT, loanA);
            assertThat(detail.ledger()).extracting(OaLoanLedgerResp::action)
                .containsExactly("DISBURSE", "WRITE_OFF", "REPAY", "REPAY");
            assertThat(detail.ledger()).extracting(OaLoanLedgerResp::balanceAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1000"), new BigDecimal("700"), new BigDecimal("500"), BigDecimal.ZERO);
            assertThat(detail.ledger().get(2).operatorName()).isEqualTo("Carol");
            assertThatThrownBy(() -> fixture.travelService.loanDetail(TENANT, SUPERVISOR, loanA))
                .hasMessageContaining("借款单不存在");
            assertThat(fixture.travelService.outstandingLoans(TENANT, APPLICANT)).isEmpty();
            assertThat(fixture.travelService.countLoans(TENANT, OaLoanHook.STATUS_PENDING_DISBURSE, null, null))
                .isEqualTo(1);
        });
    }

    @Test
    void concurrentLoansRespectTravelBudget() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long travelApp = fixture.submitTravel("t1", TODAY.plusDays(1), TODAY.plusDays(2), "1000");
            fixture.approve(SUPERVISOR, travelApp, "a-t1");
            String travelNo = fixture.travelNo(travelApp);

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "race-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        fixture.submitLoan(requestId, "600", travelNo);
                        return true;
                    } catch (BusinessException e) {
                        assertThat(e.getMessage()).contains("超出出差预算");
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
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_loan")).isEqualTo(1);
        });
    }

    @Test
    void rejectWithdrawAndTravelValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long rejected = fixture.submitTravel("t1", TODAY.plusDays(1), TODAY.plusDays(2), "500");
            String rejectedNo = fixture.travelNo(rejected);
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(rejected)
                .getId(), new OaApprovalActionReq("r-t1", "不必出差"));
            assertThat(fixture.travelStatus(rejected)).isEqualTo(OaTravelHook.STATUS_CLOSED);
            assertThatThrownBy(() -> fixture.submitLoan("l0", "100", rejectedNo)).hasMessageContaining("尚未审批通过");
            assertThatThrownBy(() -> fixture.submitLoan("l0", "100", "TR000000")).hasMessageContaining("不存在或不属于本人");

            long again = fixture.submitTravel("t2", TODAY.plusDays(1), TODAY.plusDays(2), "500");
            fixture.approve(SUPERVISOR, again, "a-t2");
            String travelNo = fixture.travelNo(again);
            long loanApp = fixture.submitLoan("l1", "200", travelNo);
            fixture.approvalService.withdraw(TENANT, APPLICANT, loanApp, action("w-l1"));
            assertThat(fixture.loanStatus(loanApp)).isEqualTo(OaLoanHook.STATUS_CLOSED);
            assertThatThrownBy(() -> fixture.travelService.disburse(TENANT, FINANCE, fixture
                .loanId(loanApp), disburse("200", YESTERDAY))).hasMessageContaining("借款已关闭，不能放款");
            long full = fixture.submitLoan("l2", "500", travelNo);
            assertThat(fixture.loanStatus(full)).isEqualTo(OaLoanHook.STATUS_APPROVING);

            long plain = fixture.submitLoan("l3", "20000", null);
            fixture.approve(SUPERVISOR, plain, "a-p1");
            fixture.approve(FINANCE, plain, "a-p2");
            assertThat(fixture.task(plain).getTaskDefinitionKey()).isEqualTo("gmApprove");

            assertThatThrownBy(() -> fixture.submitTravel("v1", TODAY.plusDays(10), TODAY.plusDays(9), "1"))
                .hasMessageContaining("结束日期不能早于开始日期");
            assertThatThrownBy(() -> fixture.submitTravel("v2", TODAY.plusDays(10), TODAY.plusDays(100), "1"))
                .hasMessageContaining("单次出差不能超过 90 天");
            assertThatThrownBy(() -> fixture.submitTravel("v3", TODAY.minusDays(31), TODAY.minusDays(30), "1"))
                .hasMessageContaining("开始日期不能早于 30 天前");
            assertThatThrownBy(() -> fixture.submitTravel("v4", TODAY.plusDays(10), TODAY.plusDays(11), "0"))
                .hasMessageContaining("必须大于 0");
            Map<String, Object> pastRepay = loanForm("100", null);
            pastRepay.put(OaLoanHook.FIELD_REPAY_DATE, YESTERDAY.toString());
            assertThatThrownBy(() -> fixture.submit("v5", LOAN_RELEASE, "借款", pastRepay))
                .hasMessageContaining("预计还款日期不能早于今天");
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_travel")).isEqualTo(2);
        });
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private static OaLoanDisburseReq disburse(String amount, LocalDate date) {
        return new OaLoanDisburseReq(new BigDecimal(amount), date, "BANK_TRANSFER", "DB-1", null);
    }

    private static OaLoanRepayReq repay(String requestId, String amount) {
        return new OaLoanRepayReq(requestId, new BigDecimal(amount), YESTERDAY, "BANK_TRANSFER", "RP-1", null);
    }

    private static OaExpenseWriteOffReq writeOff(long loanId, String amount) {
        return new OaExpenseWriteOffReq(loanId, new BigDecimal(amount));
    }

    private static OaExpensePayReq pay(String paidAmount, OaExpenseWriteOffReq... writeOffs) {
        return new OaExpensePayReq(new BigDecimal(paidAmount), YESTERDAY, "BANK_TRANSFER", "TX-1", null, List
            .of(writeOffs));
    }

    private static Map<String, Object> travelForm(LocalDate start, LocalDate end, String budget) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(OaTravelHook.FIELD_DESTINATION, "上海");
        form.put(OaTravelHook.FIELD_START_DATE, start.toString());
        form.put(OaTravelHook.FIELD_END_DATE, end.toString());
        form.put(OaTravelHook.FIELD_BUDGET, new BigDecimal(budget));
        form.put("reason", "客户拜访");
        return form;
    }

    private static Map<String, Object> loanForm(String amount, String travelNo) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(OaLoanHook.FIELD_AMOUNT, new BigDecimal(amount));
        if (travelNo != null) {
            form.put(OaLoanHook.FIELD_TRAVEL_NO, travelNo);
        }
        form.put(OaLoanHook.FIELD_REASON, "差旅备用金");
        return form;
    }

    private static Map<String, Object> expenseForm(String travelNo, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(OaExpenseHook.COL_CATEGORY, "TRAFFIC");
        row.put(OaExpenseHook.COL_AMOUNT, new BigDecimal(amount));
        row.put(OaExpenseHook.COL_DATE, YESTERDAY.toString());
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("reason", "差旅报销");
        form.put(OaExpenseHook.FIELD_TRAVEL_NO, travelNo);
        form.put(OaExpenseHook.FIELD_ITEMS, List.of(row));
        return form;
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-travel-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        OaExpenseServiceTest.createTables(jdbc);
        OaExpenseServiceTest.seedAccounts(jdbc);
        OaExpenseRepository expenseRepository = new OaExpenseRepository(jdbc);
        OaExpenseServiceTest.seedCategories(expenseRepository);
        OaTravelRepository travelRepository = new OaTravelRepository(jdbc);

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-travel")
            .tenantId(Long.toString(TENANT))
            .addString("expense.bpmn20.xml", OaExpenseService.TEMPLATE_BPMN)
            .addString("travel.bpmn20.xml", OaTravelService.TRAVEL_BPMN)
            .addString("loan.bpmn20.xml", OaTravelService.LOAN_BPMN)
            .deploy();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OaExpenseServiceTest.StubStaffDirectory directory = new OaExpenseServiceTest.StubStaffDirectory();

        OaExpenseHook expenseHook = new OaExpenseHook(expenseRepository, travelRepository);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaBusinessHooks hooks = new OaBusinessHooks(List
            .of(expenseHook, new OaTravelHook(travelRepository), new OaLoanHook(travelRepository)), launchRepository);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaExpenseService expenseService = new OaExpenseService(expenseRepository, travelRepository, expenseHook, null, null, outboxService, objectMapper, transactionManager);
        OaTravelService travelService = new OaTravelService(travelRepository, expenseRepository, null, null, outboxService, objectMapper, transactionManager);

        seedRelease(jdbc, repositoryService, 1, EXPENSE_RELEASE, OaExpenseService.TEMPLATE_MODEL_KEY, OaExpenseHook.BUSINESS_TYPE, expenseService
            .templateSchema(expenseService.categories(TENANT, true)));
        seedRelease(jdbc, repositoryService, 2, TRAVEL_RELEASE, OaTravelService.TRAVEL_MODEL_KEY, OaTravelHook.BUSINESS_TYPE, travelService
            .travelSchema());
        seedRelease(jdbc, repositoryService, 3, LOAN_RELEASE, OaTravelService.LOAN_MODEL_KEY, OaLoanHook.BUSINESS_TYPE, travelService
            .loanSchema());

        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, context
            .getBean(org.flowable.engine.HistoryService.class), repositoryService);
        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, hooks, expenseService, travelService, launchService, approvalService, runtimeService, taskService);
    }

    private static void seedRelease(JdbcTemplate jdbc,
                                    RepositoryService repositoryService,
                                    long modelId,
                                    long releaseId,
                                    String processKey,
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
            VALUES (?, 101, ?, ?, 'finance', '说明', 'PUBLISHED', ?, ?, 1, ?, ?, 0, 7, CURRENT_TIMESTAMP, 0)
            """, modelId, processKey, processKey, businessType, releaseId, formId, versionId);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (?, 101, ?, 1, ?, ?, ?, ?, 1, ?, ?, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, releaseId, modelId, switch (processKey) {
            case OaTravelService.TRAVEL_MODEL_KEY -> OaTravelService.TRAVEL_BPMN;
            case OaTravelService.LOAN_MODEL_KEY -> OaTravelService.LOAN_BPMN;
            default -> OaExpenseService.TEMPLATE_BPMN;
        }, "a".repeat(64), definitionId, processKey, formId, versionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (?, 101, ?, ?, 'PUBLISHED', 0)", formId, processKey, processKey);
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (?, 101, ?, 1, ?, ?, 4, 7, CURRENT_TIMESTAMP)
            """, versionId, formId, schema, "b".repeat(64));
    }

    private record Fixture(JdbcTemplate jdbc, OaBusinessHooks hooks, OaExpenseService expenseService,
                           OaTravelService travelService, OaLaunchService launchService,
                           OaApprovalService approvalService, RuntimeService runtimeService, TaskService taskService) {

        private long submit(String requestId, long releaseId, String title, Map<String, Object> form) {
            return launchService.submit(TENANT, APPLICANT, releaseId, new OaLaunchSubmitReq(requestId, title, form, List
                .of())).applicationId();
        }

        private long submitTravel(String requestId, LocalDate start, LocalDate end, String budget) {
            return submit(requestId, TRAVEL_RELEASE, "出差", travelForm(start, end, budget));
        }

        private long submitLoan(String requestId, String amount, String travelNo) {
            return submit(requestId, LOAN_RELEASE, "借款", loanForm(amount, travelNo));
        }

        private long submitExpense(String requestId, String travelNo, String amount) {
            return submit(requestId, EXPENSE_RELEASE, "报销", expenseForm(travelNo, amount));
        }

        private long approvedLoan(String requestId, String amount, String travelNo) {
            long applicationId = submitLoan(requestId, amount, travelNo);
            approve(SUPERVISOR, applicationId, requestId + "-a1");
            approve(FINANCE, applicationId, requestId + "-a2");
            return applicationId;
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

        private long travelId(long applicationId) {
            return jdbc.queryForObject("SELECT id FROM oa_travel WHERE application_id = ?", Long.class, applicationId);
        }

        private String travelNo(long applicationId) {
            return jdbc
                .queryForObject("SELECT travel_no FROM oa_travel WHERE application_id = ?", String.class, applicationId);
        }

        private String travelStatus(long applicationId) {
            return jdbc
                .queryForObject("SELECT status FROM oa_travel WHERE application_id = ?", String.class, applicationId);
        }

        private long loanId(long applicationId) {
            return jdbc.queryForObject("SELECT id FROM oa_loan WHERE application_id = ?", Long.class, applicationId);
        }

        private String loanStatus(long applicationId) {
            return jdbc
                .queryForObject("SELECT status FROM oa_loan WHERE application_id = ?", String.class, applicationId);
        }

        private BigDecimal loanBalance(long loanId) {
            return jdbc.queryForObject("SELECT balance FROM oa_loan WHERE id = ?", BigDecimal.class, loanId);
        }

        private long expenseId(long applicationId) {
            return jdbc.queryForObject("SELECT id FROM oa_expense WHERE application_id = ?", Long.class, applicationId);
        }

        private String expenseStatus(long applicationId) {
            return jdbc
                .queryForObject("SELECT status FROM oa_expense WHERE application_id = ?", String.class, applicationId);
        }

        private long count(String sql) {
            return jdbc.queryForObject(sql, Long.class);
        }
    }
}
