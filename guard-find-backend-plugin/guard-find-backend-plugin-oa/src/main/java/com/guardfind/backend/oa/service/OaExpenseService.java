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
import com.guardfind.backend.oa.expense.OaExpenseHook;
import com.guardfind.backend.oa.model.OaExpenseCategoryReq;
import com.guardfind.backend.oa.model.OaExpenseCategoryResp;
import com.guardfind.backend.oa.model.OaExpenseDetailResp;
import com.guardfind.backend.oa.model.OaExpensePayReq;
import com.guardfind.backend.oa.model.OaExpensePreviewResp;
import com.guardfind.backend.oa.model.OaExpenseResp;
import com.guardfind.backend.oa.model.OaExpenseTemplateResp;
import com.guardfind.backend.oa.model.OaExpenseWriteOffReq;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.repository.OaExpenseRepository;
import com.guardfind.backend.oa.repository.OaExpenseRepository.ExpenseRow;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository.LoanRow;
import com.guardfind.backend.oa.travel.OaLoanHook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报销管理：费用类型、报销台账、财务支付登记、我的报销与报销模板初始化。
 */
@Service
public class OaExpenseService {

    public static final String TEMPLATE_FORM_KEY = "expense_claim";
    public static final String TEMPLATE_MODEL_KEY = "expense_pilot";
    public static final String EVENT_EXPENSE_PAID = "EXPENSE_PAID";
    public static final String LEDGER_WRITE_OFF = "WRITE_OFF";
    private static final Set<String> STATUSES = Set
        .of(OaExpenseHook.STATUS_APPROVING, OaExpenseHook.STATUS_PENDING_PAYMENT, OaExpenseHook.STATUS_PAID, OaExpenseHook.STATUS_CLOSED);

    private final OaExpenseRepository repository;
    private final OaTravelRepository travelRepository;
    private final OaExpenseHook hook;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaExpenseService(OaExpenseRepository repository,
                            OaTravelRepository travelRepository,
                            OaExpenseHook hook,
                            OaFormService formService,
                            OaProcessModelService modelService,
                            OaOutboxService outboxService,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.travelRepository = travelRepository;
        this.hook = hook;
        this.formService = formService;
        this.modelService = modelService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaExpenseCategoryResp> categories(long tenantId, boolean enabledOnly) {
        return repository.categories(tenantId, enabledOnly);
    }

    public OaExpenseCategoryResp createCategory(long tenantId, long userId, OaExpenseCategoryReq req) {
        String code = req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) {
            throw new BusinessException("类型编码不能为空");
        }
        if (repository.categoryCodeExists(tenantId, code)) {
            throw new BusinessException("类型编码已存在");
        }
        try {
            long id = repository.insertCategory(tenantId, code, req.name().trim(), req.perItemLimit(), req
                .invoiceRequired(), status(req.status()), sort(req.sort()), blankToNull(req
                    .remark()), userId, LocalDateTime.now());
            return requireCategory(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("类型编码已存在");
        }
    }

    public OaExpenseCategoryResp updateCategory(long tenantId, long userId, long id, OaExpenseCategoryReq req) {
        OaExpenseCategoryResp current = requireCategory(tenantId, id);
        int version = req.version() == null ? current.version() : req.version();
        int rows = repository.updateCategory(tenantId, id, req.name().trim(), req.perItemLimit(), req
            .invoiceRequired(), status(req.status()), sort(req.sort()), blankToNull(req.remark()), userId, LocalDateTime
                .now(), version);
        if (rows == 0) {
            throw new BusinessException("费用类型已被其他人修改，请刷新后重试");
        }
        return requireCategory(tenantId, id);
    }

    public List<OaExpenseResp> records(long tenantId,
                                       String status,
                                       String keyword,
                                       Long applicantId,
                                       int page,
                                       int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * safeSize;
        return repository.records(tenantId, checkedStatus(status), keyword, applicantId, offset, safeSize);
    }

    public long countRecords(long tenantId, String status, String keyword, Long applicantId) {
        return repository.countRecords(tenantId, checkedStatus(status), keyword, applicantId);
    }

    public OaExpenseDetailResp detail(long tenantId, long id) {
        OaExpenseResp expense = repository.record(tenantId, id);
        if (expense == null) {
            throw new BusinessException("报销单不存在");
        }
        return new OaExpenseDetailResp(expense, repository.items(tenantId, id), travelRepository
            .writeOffs(tenantId, id));
    }

    public OaExpenseDetailResp myDetail(long tenantId, long userId, long id) {
        OaExpenseDetailResp detail = detail(tenantId, id);
        if (detail.expense().applicantId() != userId) {
            throw new BusinessException("报销单不存在");
        }
        return detail;
    }

    /**
     * 财务登记支付：只允许待支付状态、非本人报销；可用报销冲抵申请人未还借款，
     * 实付金额必须等于合计减去核销金额。与审批通过分离，不回写流程。
     */
    public OaExpenseResp pay(long tenantId, long operatorId, long id, OaExpensePayReq req) {
        if (req.payDate().isAfter(LocalDate.now())) {
            throw new BusinessException("支付日期不能晚于今天");
        }
        return transactionTemplate.execute(status -> {
            ExpenseRow expense = repository.lock(tenantId, id);
            if (expense == null) {
                throw new BusinessException("报销单不存在");
            }
            if (OaExpenseHook.STATUS_PAID.equals(expense.status())) {
                throw new BusinessException("该报销已登记支付");
            }
            if (OaExpenseHook.STATUS_CLOSED.equals(expense.status())) {
                throw new BusinessException("报销单已关闭，不能登记支付");
            }
            if (!OaExpenseHook.STATUS_PENDING_PAYMENT.equals(expense.status())) {
                throw new BusinessException("报销单尚未审批通过，不能登记支付");
            }
            if (expense.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的报销登记支付");
            }
            List<OaExpenseWriteOffReq> writeOffs = req.writeOffs() == null ? List.of() : req.writeOffs();
            BigDecimal writeOffTotal = BigDecimal.ZERO;
            Set<Long> loanIds = new HashSet<>();
            for (OaExpenseWriteOffReq writeOff : writeOffs) {
                if (!loanIds.add(writeOff.loanId())) {
                    throw new BusinessException("同一笔借款不能重复核销");
                }
                if (writeOff.amount().stripTrailingZeros().scale() > 2) {
                    throw new BusinessException("核销金额最多两位小数");
                }
                writeOffTotal = writeOffTotal.add(writeOff.amount());
            }
            if (writeOffTotal.compareTo(expense.totalAmount()) > 0) {
                throw new BusinessException("核销合计 " + plain(writeOffTotal) + " 元超过报销合计 " + plain(expense
                    .totalAmount()) + " 元");
            }
            BigDecimal payable = expense.totalAmount().subtract(writeOffTotal);
            if (req.paidAmount().compareTo(payable) != 0) {
                throw new BusinessException(writeOffs.isEmpty()
                    ? "实付金额 " + plain(req.paidAmount()) + " 元与报销合计 " + plain(expense.totalAmount()) + " 元不一致"
                    : "实付金额 " + plain(req.paidAmount()) + " 元应等于报销合计 " + plain(expense
                        .totalAmount()) + " 元减核销 " + plain(writeOffTotal) + " 元");
            }
            LocalDateTime now = LocalDateTime.now();
            List<OaExpenseWriteOffReq> ordered = new ArrayList<>(writeOffs);
            ordered.sort(Comparator.comparing(OaExpenseWriteOffReq::loanId));
            for (OaExpenseWriteOffReq writeOff : ordered) {
                writeOff(tenantId, operatorId, expense, writeOff, req.payDate(), now);
            }
            if (repository.markPaid(tenantId, id, req.paidAmount(), writeOffTotal.setScale(2), req.payDate(), req
                .payMethod(), blankToNull(req.payReference()), blankToNull(req.remark()), operatorId, now) != 1) {
                throw new BusinessException("报销单状态已变化，请刷新后重试");
            }
            OaExpenseResp paid = repository.record(tenantId, id);
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(expense
                .applicationId()), EVENT_EXPENSE_PAID, "PAID:" + expense.id(), expense
                    .applicationId(), null, "PAID", "报销已支付", "您的报销「" + paid.title() + "」已支付 " + plain(req
                        .paidAmount()) + " 元", List.of(expense.applicantId()));
            return paid;
        });
    }

    public OaExpensePreviewResp preview(long tenantId, long applicantId, Map<String, Object> formData) {
        return hook.preview(tenantId, applicantId, formData == null ? Map.of() : formData);
    }

    private void writeOff(long tenantId,
                          long operatorId,
                          ExpenseRow expense,
                          OaExpenseWriteOffReq req,
                          LocalDate bizDate,
                          LocalDateTime now) {
        LoanRow loan = travelRepository.lockLoan(tenantId, req.loanId());
        if (loan == null || loan.applicantId() != expense.applicantId()) {
            throw new BusinessException("借款 " + req.loanId() + " 不存在或不属于报销申请人");
        }
        if (!OaLoanHook.STATUS_OUTSTANDING.equals(loan.status())) {
            throw new BusinessException("借款 " + req.loanId() + " 不是待还款状态，不能核销");
        }
        BigDecimal amount = req.amount().setScale(2);
        if (amount.compareTo(loan.balance()) > 0) {
            throw new BusinessException("借款 " + req.loanId() + " 余额 " + plain(loan
                .balance()) + " 元，不足以核销 " + plain(amount) + " 元");
        }
        BigDecimal balanceAfter = loan.balance().subtract(amount);
        try {
            travelRepository.insertLedger(tenantId, loan.id(), LEDGER_WRITE_OFF, amount, balanceAfter, expense
                .id(), null, bizDate, null, null, operatorId, "报销冲抵借款", now);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该报销已核销过借款 " + req.loanId());
        }
        if (travelRepository.changeBalance(tenantId, loan.id(), loan.balance(), balanceAfter, now) != 1) {
            throw new BusinessException("借款余额已变化，请刷新后重试");
        }
    }

    /**
     * 初始化报销试点：缺省费用类型、已发布报销表单与 EXPENSE 业务类型的草稿流程模型。
     */
    public OaExpenseTemplateResp template(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            if (repository.categories(tenantId, false).isEmpty()) {
                repository.insertCategory(tenantId, "TRAFFIC", "交通", null, false, "ENABLED", 10, null, userId, now);
                repository
                    .insertCategory(tenantId, "MEAL", "餐饮", new BigDecimal("500.00"), true, "ENABLED", 20, null, userId, now);
                repository
                    .insertCategory(tenantId, "LODGING", "住宿", new BigDecimal("1000.00"), true, "ENABLED", 30, null, userId, now);
                repository.insertCategory(tenantId, "OFFICE", "办公用品", null, true, "ENABLED", 40, null, userId, now);
                repository.insertCategory(tenantId, "OTHER", "其他", null, false, "ENABLED", 90, null, userId, now);
            }
            List<OaExpenseCategoryResp> enabled = repository.categories(tenantId, true);
            if (enabled.isEmpty()) {
                throw new BusinessException("没有启用的费用类型，请先启用至少一个类型");
            }
            OaFormResp form = formService
                .create(tenantId, userId, new OaFormCreateReq(TEMPLATE_FORM_KEY, "报销单", "报销试点模板，明细字段 key 不可修改", templateSchema(enabled)));
            OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
            if (!published.valid()) {
                throw new BusinessException("报销表单发布失败：" + String.join("；", published.errors()));
            }
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(TEMPLATE_MODEL_KEY, "费用报销", "finance", "直属上级 → 财务岗位审核；合计超过 5000 元或超出关联出差预算再由总经理岗位审批；通过后由财务登记支付并可冲抵借款", TEMPLATE_BPMN, OaExpenseHook.BUSINESS_TYPE));
            return new OaExpenseTemplateResp(form.id(), published.versionId(), model.id(), model.modelKey());
        });
    }

    public String templateSchema(List<OaExpenseCategoryResp> categories) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (OaExpenseCategoryResp category : categories) {
            options.add(option(category.name(), category.code()));
        }
        Map<String, Object> items = field(OaExpenseHook.FIELD_ITEMS, "报销明细", "table", true);
        items.put("maxRows", 50);
        items.put("columns", List
            .of(column(OaExpenseHook.COL_CATEGORY, "费用类型", "radio", true, options), column(OaExpenseHook.COL_AMOUNT, "金额（元）", "number", true, null), column(OaExpenseHook.COL_DATE, "发生日期", "date", false, null), column(OaExpenseHook.COL_INVOICE, "发票号", "text", false, null), column(OaExpenseHook.COL_DESCRIPTION, "说明", "text", false, null)));
        List<Map<String, Object>> fields = List
            .of(field("reason", "报销事由", "textarea", true), field(OaExpenseHook.FIELD_TRAVEL_NO, "关联出差单号", "text", false), items, field("invoices", "发票附件", "attachment", false));
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("报销模板 schema 序列化失败", e);
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

    private static Map<String, Object> column(String key,
                                              String label,
                                              String type,
                                              boolean required,
                                              List<Map<String, Object>> options) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("key", key);
        column.put("label", label);
        column.put("type", type);
        column.put("required", required);
        if (options != null) {
            column.put("options", options);
        }
        return column;
    }

    private static Map<String, Object> option(String label, String value) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("label", label);
        option.put("value", value);
        return option;
    }

    private OaExpenseCategoryResp requireCategory(long tenantId, long id) {
        OaExpenseCategoryResp category = repository.category(tenantId, id);
        if (category == null) {
            throw new BusinessException("费用类型不存在");
        }
        return category;
    }

    private static String checkedStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!STATUSES.contains(status)) {
            throw new BusinessException("报销单状态无效: " + status);
        }
        return status;
    }

    private static String status(String status) {
        return status == null || status.isBlank() ? "ENABLED" : status;
    }

    private static int sort(Integer sort) {
        return sort == null ? 0 : sort;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public static final String TEMPLATE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_expense" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="expense_pilot" name="费用报销" isExecutable="true">
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
            <bpmn:exclusiveGateway id="amountGateway" name="超过 5000 元或超预算？" default="flowSmall">
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
            <bpmn:sequenceFlow id="flowLarge" name="大于 5000 元或超预算" sourceRef="amountGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${expenseAmount &gt; 5000 || overBudget}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="5000 元及以内且未超预算" sourceRef="amountGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_expense">
            <bpmndi:BPMNPlane id="BPMNPlane_expense" bpmnElement="expense_pilot">
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
