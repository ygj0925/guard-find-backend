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

package com.guardfind.backend.oa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaLoanDetailResp;
import com.guardfind.backend.oa.model.OaLoanDisburseReq;
import com.guardfind.backend.oa.model.OaLoanRepayReq;
import com.guardfind.backend.oa.model.OaLoanResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaTravelDetailResp;
import com.guardfind.backend.oa.model.OaTravelResp;
import com.guardfind.backend.oa.model.OaTravelTemplateResp;
import com.guardfind.backend.oa.repository.OaExpenseRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository.LoanRow;
import com.guardfind.backend.oa.travel.OaLoanHook;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出差借款管理：出差台账、借款台账、财务放款与还款登记、我的出差借款与模板初始化。
 */
@Service
public class OaTravelService {

    public static final String TRAVEL_FORM_KEY = "travel_request";
    public static final String TRAVEL_MODEL_KEY = "travel_pilot";
    public static final String LOAN_FORM_KEY = "loan_request";
    public static final String LOAN_MODEL_KEY = "loan_pilot";
    public static final String EVENT_LOAN_DISBURSED = "LOAN_DISBURSED";
    public static final String LEDGER_DISBURSE = "DISBURSE";
    public static final String LEDGER_REPAY = "REPAY";

    private static final Set<String> TRAVEL_STATUSES = Set
        .of(OaTravelHook.STATUS_APPROVING, OaTravelHook.STATUS_APPROVED, OaTravelHook.STATUS_CLOSED);
    private static final Set<String> LOAN_STATUSES = Set
        .of(OaLoanHook.STATUS_APPROVING, OaLoanHook.STATUS_PENDING_DISBURSE, OaLoanHook.STATUS_OUTSTANDING, OaLoanHook.STATUS_SETTLED, OaLoanHook.STATUS_CLOSED);

    private final OaTravelRepository repository;
    private final OaExpenseRepository expenseRepository;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaTravelService(OaTravelRepository repository,
                           OaExpenseRepository expenseRepository,
                           OaFormService formService,
                           OaProcessModelService modelService,
                           OaOutboxService outboxService,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.expenseRepository = expenseRepository;
        this.formService = formService;
        this.modelService = modelService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaTravelResp> travels(long tenantId,
                                      String status,
                                      String keyword,
                                      Long applicantId,
                                      int page,
                                      int size) {
        int safeSize = safeSize(size);
        return repository
            .travels(tenantId, checked(status, TRAVEL_STATUSES, "出差单"), keyword, applicantId, offset(page, safeSize), safeSize);
    }

    public long countTravels(long tenantId, String status, String keyword, Long applicantId) {
        return repository.countTravels(tenantId, checked(status, TRAVEL_STATUSES, "出差单"), keyword, applicantId);
    }

    public OaTravelDetailResp travelDetail(long tenantId, Long viewerId, long id) {
        OaTravelResp travel = repository.travel(tenantId, id);
        if (travel == null || viewerId != null && travel.applicantId() != viewerId) {
            throw new BusinessException("出差单不存在");
        }
        return new OaTravelDetailResp(travel, repository
            .loans(tenantId, null, null, null, id, 0, 100), expenseRepository.recordsByTravel(tenantId, id));
    }

    public List<OaLoanResp> loans(long tenantId, String status, String keyword, Long applicantId, int page, int size) {
        int safeSize = safeSize(size);
        return repository
            .loans(tenantId, checked(status, LOAN_STATUSES, "借款单"), keyword, applicantId, null, offset(page, safeSize), safeSize);
    }

    public long countLoans(long tenantId, String status, String keyword, Long applicantId) {
        return repository.countLoans(tenantId, checked(status, LOAN_STATUSES, "借款单"), keyword, applicantId, null);
    }

    public OaLoanDetailResp loanDetail(long tenantId, Long viewerId, long id) {
        OaLoanResp loan = repository.loan(tenantId, id);
        if (loan == null || viewerId != null && loan.applicantId() != viewerId) {
            throw new BusinessException("借款单不存在");
        }
        return new OaLoanDetailResp(loan, repository.ledger(tenantId, id));
    }

    /**
     * 申请人名下待还款的借款，供财务登记报销支付时选择冲抵。
     */
    public List<OaLoanResp> outstandingLoans(long tenantId, long applicantId) {
        return repository.loans(tenantId, OaLoanHook.STATUS_OUTSTANDING, null, applicantId, null, 0, 100);
    }

    /**
     * 财务登记放款：放款金额必须等于借款金额，不能为本人借款放款；放款后余额等于借款金额。
     */
    public OaLoanResp disburse(long tenantId, long operatorId, long id, OaLoanDisburseReq req) {
        requireNotFuture(req.disburseDate(), "放款日期");
        return transactionTemplate.execute(status -> {
            LoanRow loan = requireLoan(tenantId, id);
            if (loan.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的借款登记放款");
            }
            if (!OaLoanHook.STATUS_PENDING_DISBURSE.equals(loan.status())) {
                throw new BusinessException(switch (loan.status()) {
                    case OaLoanHook.STATUS_APPROVING -> "借款尚未审批通过，不能放款";
                    case OaLoanHook.STATUS_CLOSED -> "借款已关闭，不能放款";
                    default -> "该借款已登记放款";
                });
            }
            if (req.amount().compareTo(loan.amount()) != 0) {
                throw new BusinessException("放款金额 " + plain(req.amount()) + " 元与借款金额 " + plain(loan
                    .amount()) + " 元不一致");
            }
            LocalDateTime now = LocalDateTime.now();
            String reference = blankToNull(req.reference());
            if (repository.markDisbursed(tenantId, id, loan.amount(), req.disburseDate(), req
                .method(), reference, operatorId, now) != 1) {
                throw new BusinessException("借款状态已变化，请刷新后重试");
            }
            repository.insertLedger(tenantId, id, LEDGER_DISBURSE, loan.amount(), loan.amount(), null, null, req
                .disburseDate(), req.method(), reference, operatorId, blankToNull(req.remark()), now);
            OaLoanResp disbursed = repository.loan(tenantId, id);
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(loan
                .applicationId()), EVENT_LOAN_DISBURSED, "DISBURSED:" + loan.id(), loan
                    .applicationId(), null, "DISBURSED", "借款已放款", "您的借款「" + disbursed.title() + "」已放款 " + plain(loan
                        .amount()) + " 元", List.of(loan.applicantId()));
            return disbursed;
        });
    }

    /**
     * 财务登记还款：按请求号幂等，还款金额不能超过余额，余额归零时借款转为已还清。
     */
    public OaLoanResp repay(long tenantId, long operatorId, long id, OaLoanRepayReq req) {
        requireNotFuture(req.repayDate(), "还款日期");
        String requestId = req.requestId().trim();
        return transactionTemplate.execute(status -> {
            Long replayed = repository.loanIdByRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return repository.loan(tenantId, id);
            }
            LoanRow loan = requireLoan(tenantId, id);
            if (loan.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的借款登记还款");
            }
            if (!OaLoanHook.STATUS_OUTSTANDING.equals(loan.status())) {
                throw new BusinessException(OaLoanHook.STATUS_SETTLED.equals(loan.status())
                    ? "借款已还清"
                    : "借款尚未放款，不能登记还款");
            }
            BigDecimal amount = req.amount().setScale(2);
            if (amount.compareTo(loan.balance()) > 0) {
                throw new BusinessException("还款金额 " + plain(amount) + " 元超过借款余额 " + plain(loan.balance()) + " 元");
            }
            BigDecimal balanceAfter = loan.balance().subtract(amount);
            LocalDateTime now = LocalDateTime.now();
            try {
                repository.insertLedger(tenantId, id, LEDGER_REPAY, amount, balanceAfter, null, requestId, req
                    .repayDate(), req.method(), blankToNull(req.reference()), operatorId, blankToNull(req
                        .remark()), now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            if (repository.changeBalance(tenantId, id, loan.balance(), balanceAfter, now) != 1) {
                throw new BusinessException("借款余额已变化，请刷新后重试");
            }
            return repository.loan(tenantId, id);
        });
    }

    /**
     * 初始化出差与借款试点：两张已发布表单与 TRAVEL/LOAN 业务类型的草稿流程模型。
     */
    public OaTravelTemplateResp template(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            long travelFormId = publishForm(tenantId, userId, TRAVEL_FORM_KEY, "出差申请", travelSchema());
            OaProcessModelResp travelModel = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(TRAVEL_MODEL_KEY, "出差申请", "travel", "直属上级审批；预算超过 3000 元再由财务岗位审核；通过后可关联借款与报销", TRAVEL_BPMN, OaTravelHook.BUSINESS_TYPE));
            long loanFormId = publishForm(tenantId, userId, LOAN_FORM_KEY, "借款申请", loanSchema());
            OaProcessModelResp loanModel = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(LOAN_MODEL_KEY, "借款申请", "finance", "直属上级 → 财务岗位审核；超过 10000 元再由总经理岗位审批；通过后由财务登记放款", LOAN_BPMN, OaLoanHook.BUSINESS_TYPE));
            return new OaTravelTemplateResp(travelFormId, travelModel.id(), travelModel
                .modelKey(), loanFormId, loanModel.id(), loanModel.modelKey());
        });
    }

    public String travelSchema() {
        return schema(List
            .of(field(OaTravelHook.FIELD_DESTINATION, "目的地", "text", true), field(OaTravelHook.FIELD_START_DATE, "开始日期", "date", true), field(OaTravelHook.FIELD_END_DATE, "结束日期", "date", true), field(OaTravelHook.FIELD_BUDGET, "预算金额（元）", "number", true), field("reason", "出差事由", "textarea", true)));
    }

    public String loanSchema() {
        return schema(List
            .of(field(OaLoanHook.FIELD_AMOUNT, "借款金额（元）", "number", true), field(OaLoanHook.FIELD_TRAVEL_NO, "关联出差单号", "text", false), field(OaLoanHook.FIELD_REASON, "借款事由", "textarea", true), field(OaLoanHook.FIELD_REPAY_DATE, "预计还款日期", "date", false)));
    }

    private long publishForm(long tenantId, long userId, String key, String name, String schema) {
        OaFormResp form = formService
            .create(tenantId, userId, new OaFormCreateReq(key, name, name + "试点模板，业务字段 key 不可修改", schema));
        OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
        if (!published.valid()) {
            throw new BusinessException(name + "表单发布失败：" + String.join("；", published.errors()));
        }
        return form.id();
    }

    private String schema(List<Map<String, Object>> fields) {
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("模板 schema 序列化失败", e);
        }
    }

    private static Map<String, Object> field(String key, String label, String type, boolean required) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", "f_" + key);
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        return field;
    }

    private LoanRow requireLoan(long tenantId, long id) {
        LoanRow loan = repository.lockLoan(tenantId, id);
        if (loan == null) {
            throw new BusinessException("借款单不存在");
        }
        return loan;
    }

    private static void requireNotFuture(LocalDate date, String label) {
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException(label + "不能晚于今天");
        }
    }

    private static String checked(String status, Set<String> allowed, String label) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!allowed.contains(status)) {
            throw new BusinessException(label + "状态无效: " + status);
        }
        return status;
    }

    private static int safeSize(int size) {
        return Math.min(100, Math.max(1, size));
    }

    private static int offset(int page, int size) {
        return (Math.max(1, page) - 1) * size;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public static final String TRAVEL_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_travel" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="travel_pilot" name="出差申请" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="budgetGateway" name="预算超过 3000 元？" default="flowSmall">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowLarge</bpmn:outgoing>
              <bpmn:outgoing>flowSmall</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="financeApprove" name="财务审核" flowable:candidateUsers="${financeReviewer}">
              <bpmn:incoming>flowLarge</bpmn:incoming>
              <bpmn:outgoing>flowFinanceDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowFinanceDone</bpmn:incoming>
              <bpmn:incoming>flowSmall</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="supervisorApprove" targetRef="budgetGateway"/>
            <bpmn:sequenceFlow id="flowLarge" name="大于 3000 元" sourceRef="budgetGateway" targetRef="financeApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${travelBudget &gt; 3000}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="3000 元及以内" sourceRef="budgetGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowFinanceDone" sourceRef="financeApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_travel">
            <bpmndi:BPMNPlane id="BPMNPlane_travel" bpmnElement="travel_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="budgetGateway_di" bpmnElement="budgetGateway" isMarkerVisible="true">
                <dc:Bounds x="395" y="95" width="50" height="50"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="financeApprove_di" bpmnElement="financeApprove">
                <dc:Bounds x="500" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="662" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToGateway_di" bpmnElement="flowToGateway">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="395" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowLarge_di" bpmnElement="flowLarge">
                <di:waypoint x="445" y="120"/>
                <di:waypoint x="500" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowSmall_di" bpmnElement="flowSmall">
                <di:waypoint x="420" y="145"/>
                <di:waypoint x="420" y="220"/>
                <di:waypoint x="680" y="220"/>
                <di:waypoint x="680" y="138"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowFinanceDone_di" bpmnElement="flowFinanceDone">
                <di:waypoint x="600" y="120"/>
                <di:waypoint x="662" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;

    public static final String LOAN_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_loan" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="loan_pilot" name="借款申请" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToFinance</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:userTask id="financeApprove" name="财务审核" flowable:candidateUsers="${financeReviewer}">
              <bpmn:incoming>flowToFinance</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="amountGateway" name="超过 10000 元？" default="flowSmall">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowLarge</bpmn:outgoing>
              <bpmn:outgoing>flowSmall</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="gmApprove" name="总经理审批" flowable:candidateUsers="${finalApprover}">
              <bpmn:incoming>flowLarge</bpmn:incoming>
              <bpmn:outgoing>flowGmDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowGmDone</bpmn:incoming>
              <bpmn:incoming>flowSmall</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToFinance" sourceRef="supervisorApprove" targetRef="financeApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="financeApprove" targetRef="amountGateway"/>
            <bpmn:sequenceFlow id="flowLarge" name="大于 10000 元" sourceRef="amountGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${loanAmount &gt; 10000}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="10000 元及以内" sourceRef="amountGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_loan">
            <bpmndi:BPMNPlane id="BPMNPlane_loan" bpmnElement="loan_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="financeApprove_di" bpmnElement="financeApprove">
                <dc:Bounds x="390" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="amountGateway_di" bpmnElement="amountGateway" isMarkerVisible="true">
                <dc:Bounds x="545" y="95" width="50" height="50"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="gmApprove_di" bpmnElement="gmApprove">
                <dc:Bounds x="650" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="812" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToFinance_di" bpmnElement="flowToFinance">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="390" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToGateway_di" bpmnElement="flowToGateway">
                <di:waypoint x="490" y="120"/>
                <di:waypoint x="545" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowLarge_di" bpmnElement="flowLarge">
                <di:waypoint x="595" y="120"/>
                <di:waypoint x="650" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowSmall_di" bpmnElement="flowSmall">
                <di:waypoint x="570" y="145"/>
                <di:waypoint x="570" y="220"/>
                <di:waypoint x="830" y="220"/>
                <di:waypoint x="830" y="138"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowGmDone_di" bpmnElement="flowGmDone">
                <di:waypoint x="750" y="120"/>
                <di:waypoint x="812" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;
}
