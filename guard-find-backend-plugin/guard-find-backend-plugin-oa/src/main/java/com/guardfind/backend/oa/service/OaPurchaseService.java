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
import com.guardfind.backend.oa.asset.OaAssetStatus;
import com.guardfind.backend.oa.model.OaDeptOptionResp;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetLedgerResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetReq;
import com.guardfind.backend.oa.model.OaPurchaseBudgetResp;
import com.guardfind.backend.oa.model.OaPurchaseDetailResp;
import com.guardfind.backend.oa.model.OaPurchaseItemResp;
import com.guardfind.backend.oa.model.OaPurchaseOrderReq;
import com.guardfind.backend.oa.model.OaPurchaseReceiveLine;
import com.guardfind.backend.oa.model.OaPurchaseReceiveReq;
import com.guardfind.backend.oa.model.OaPurchaseResp;
import com.guardfind.backend.oa.model.OaPurchaseTemplateResp;
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.purchase.OaPurchaseHook;
import com.guardfind.backend.oa.repository.OaAssetRepository;
import com.guardfind.backend.oa.repository.OaPurchaseRepository;
import com.guardfind.backend.oa.repository.OaPurchaseRepository.BudgetRow;
import com.guardfind.backend.oa.repository.OaPurchaseRepository.PurchaseRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 采购管理：部门年度预算、采购台账、下单与验收登记（与审批分离），验收时用品入库、资产建卡。
 */
@Service
public class OaPurchaseService {

    public static final String FORM_KEY = "purchase_request";
    public static final String MODEL_KEY = "purchase_pilot";
    public static final String EVENT_RECEIVED = "PURCHASE_RECEIVED";
    public static final String REF_PURCHASE = "PURCHASE";

    private static final Set<String> STATUSES = Set
        .of(OaPurchaseHook.STATUS_APPROVING, OaPurchaseHook.STATUS_PENDING_ORDER, OaPurchaseHook.STATUS_ORDERED, OaPurchaseHook.STATUS_PARTIAL, OaPurchaseHook.STATUS_RECEIVED, OaPurchaseHook.STATUS_CLOSED);
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final OaPurchaseRepository repository;
    private final OaAssetRepository assetRepository;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaPurchaseService(OaPurchaseRepository repository,
                             OaAssetRepository assetRepository,
                             OaFormService formService,
                             OaProcessModelService modelService,
                             OaOutboxService outboxService,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.formService = formService;
        this.modelService = modelService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaDeptOptionResp> depts(long tenantId) {
        return repository.depts(tenantId);
    }

    public List<OaPurchaseBudgetResp> budgets(long tenantId, Integer year) {
        List<OaPurchaseBudgetResp> budgets = repository.budgets(tenantId, year);
        if (budgets.isEmpty()) {
            return budgets;
        }
        Map<Long, String> paths = new HashMap<>();
        repository.depts(tenantId).forEach(dept -> paths.put(dept.id(), dept.name()));
        return budgets.stream()
            .map(b -> new OaPurchaseBudgetResp(b.id(), b.deptId(), paths.getOrDefault(b.deptId(), b.deptName()), b
                .budgetYear(), b.amount(), b.usedAmount(), b.occupiedAmount(), b.availableAmount(), b.remark(), b
                    .version(), b.updateTime()))
            .toList();
    }

    public List<OaPurchaseBudgetLedgerResp> budgetLedger(long tenantId, long budgetId) {
        if (repository.budget(tenantId, budgetId) == null) {
            throw new BusinessException("预算不存在");
        }
        return repository.budgetLedger(tenantId, budgetId);
    }

    /**
     * 新建或调整部门年度预算：调整需带版本号，额度不能低于已用与占用合计，差额写入预算台账。
     */
    public OaPurchaseBudgetResp saveBudget(long tenantId, long userId, OaPurchaseBudgetReq req) {
        if (repository.deptName(tenantId, req.deptId()) == null) {
            throw new BusinessException("部门不存在");
        }
        BigDecimal amount = req.amount().setScale(2);
        String remark = blankToNull(req.remark());
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            BudgetRow existing = repository.lockBudget(tenantId, req.deptId(), req.budgetYear());
            if (existing == null) {
                long id;
                try {
                    id = repository.insertBudget(tenantId, req.deptId(), req.budgetYear(), amount, remark, userId, now);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException("该部门年度预算已存在，请刷新后编辑");
                }
                repository
                    .insertBudgetLedger(tenantId, id, OaPurchaseHook.LEDGER_GRANT, amount, null, userId, remark, now);
                return repository.budget(tenantId, id);
            }
            if (req.version() == null || req.version() != existing.version()) {
                throw new BusinessException("预算已被修改，请刷新后重试");
            }
            if (amount.compareTo(existing.used().add(existing.occupied())) < 0) {
                throw new BusinessException("预算不能低于已用与占用合计 " + plain(existing.used().add(existing.occupied())) + " 元");
            }
            if (repository.updateBudget(tenantId, existing.id(), existing
                .version(), amount, remark, userId, now) != 1) {
                throw new BusinessException("预算已被修改，请刷新后重试");
            }
            BigDecimal delta = amount.subtract(existing.amount());
            if (delta.signum() != 0) {
                repository.insertBudgetLedger(tenantId, existing
                    .id(), OaPurchaseHook.LEDGER_ADJUST, delta, null, userId, remark, now);
            }
            return repository.budget(tenantId, existing.id());
        });
    }

    public List<OaPurchaseResp> purchases(long tenantId,
                                          String status,
                                          String keyword,
                                          Long applicantId,
                                          int page,
                                          int size) {
        int safeSize = safeSize(size);
        return repository.purchases(tenantId, checked(status), keyword, applicantId, offset(page, safeSize), safeSize);
    }

    public long countPurchases(long tenantId, String status, String keyword, Long applicantId) {
        return repository.countPurchases(tenantId, checked(status), keyword, applicantId);
    }

    public OaPurchaseDetailResp detail(long tenantId, Long viewerId, long id) {
        OaPurchaseResp purchase = repository.purchase(tenantId, id);
        if (purchase == null || viewerId != null && purchase.applicantId() != viewerId) {
            throw new BusinessException("采购单不存在");
        }
        return new OaPurchaseDetailResp(purchase, repository.items(tenantId, id, false), repository
            .receipts(tenantId, id), assetRepository.assets(tenantId, null, null, null, id, 0, 500));
    }

    /**
     * 采购登记下单：只能对待下单的采购执行，经办人不能是申请人。
     */
    public OaPurchaseResp order(long tenantId, long operatorId, long id, OaPurchaseOrderReq req) {
        requireNotFuture(req.orderDate(), "下单日期");
        return transactionTemplate.execute(status -> {
            PurchaseRow purchase = requirePurchase(tenantId, id);
            if (purchase.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的采购登记下单");
            }
            if (!OaPurchaseHook.STATUS_PENDING_ORDER.equals(purchase.status())) {
                throw new BusinessException(switch (purchase.status()) {
                    case OaPurchaseHook.STATUS_APPROVING -> "采购尚未审批通过，不能下单";
                    case OaPurchaseHook.STATUS_CLOSED -> "采购已关闭，不能下单";
                    default -> "该采购已登记下单";
                });
            }
            if (repository.markOrdered(tenantId, id, req.supplier().trim(), blankToNull(req.orderNo()), req
                .orderDate(), operatorId, LocalDateTime.now()) != 1) {
                throw new BusinessException("采购状态已变化，请刷新后重试");
            }
            return repository.purchase(tenantId, id);
        });
    }

    /**
     * 采购验收：按请求号幂等，可分批验收；用品类明细入库并记库存台账，资产类明细逐件建卡。
     */
    public OaPurchaseDetailResp receive(long tenantId, long operatorId, long id, OaPurchaseReceiveReq req) {
        requireNotFuture(req.receiveDate(), "验收日期");
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            // 先锁采购单再查请求号：并发重放等锁后能看到已提交的验收记录，按重放返回
            PurchaseRow purchase = requirePurchase(tenantId, id);
            Long replayed = repository.purchaseIdByReceiptRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            if (purchase.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的采购登记验收");
            }
            if (!OaPurchaseHook.STATUS_ORDERED.equals(purchase.status()) && !OaPurchaseHook.STATUS_PARTIAL
                .equals(purchase.status())) {
                throw new BusinessException(OaPurchaseHook.STATUS_RECEIVED.equals(purchase.status())
                    ? "采购已全部验收"
                    : "采购尚未下单，不能验收");
            }
            Map<Long, OaPurchaseItemResp> items = new LinkedHashMap<>();
            for (OaPurchaseItemResp item : repository.items(tenantId, id, true)) {
                items.put(item.id(), item);
            }
            Map<Long, Integer> received = new LinkedHashMap<>();
            int line = 0;
            for (OaPurchaseReceiveLine receiveLine : req.lines()) {
                line++;
                OaPurchaseItemResp item = items.get(receiveLine.itemId());
                if (item == null) {
                    throw new BusinessException("第 " + line + " 行明细不属于该采购");
                }
                if (received.containsKey(item.id())) {
                    throw new BusinessException("第 " + line + " 行明细重复");
                }
                if (receiveLine.quantity() > item.quantity() - item.receivedQty()) {
                    throw new BusinessException("第 " + line + " 行验收数量超过未验收数量 " + (item.quantity() - item
                        .receivedQty()));
                }
                received.put(item.id(), receiveLine.quantity());
            }
            LocalDateTime now = LocalDateTime.now();
            long receiptId;
            try {
                receiptId = repository.insertReceipt(tenantId, id, requestId, req
                    .receiveDate(), operatorId, blankToNull(req.remark()), now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            Map<Long, Integer> stockIn = new TreeMap<>();
            Map<Long, String> stockInCodes = new LinkedHashMap<>();
            String location = blankToNull(req.location());
            for (Map.Entry<Long, Integer> entry : received.entrySet()) {
                OaPurchaseItemResp item = items.get(entry.getKey());
                int quantity = entry.getValue();
                repository.insertReceiptLine(tenantId, receiptId, item.id(), quantity);
                if (repository.receiveItem(tenantId, item.id(), quantity) != 1) {
                    throw new BusinessException("采购明细已变化，请刷新后重试");
                }
                if (OaPurchaseHook.CATEGORY_SUPPLY.equals(item.category())) {
                    OaSupplyItemResp supply = assetRepository.supplyItemByCode(tenantId, item.supplyCode(), false);
                    if (supply == null) {
                        throw new BusinessException(item.itemName() + " 对应的用品编码 " + item.supplyCode() + " 不存在");
                    }
                    stockIn.merge(supply.id(), quantity, Integer::sum);
                    stockInCodes.put(supply.id(), supply.code());
                } else if (OaPurchaseHook.CATEGORY_ASSET.equals(item.category())) {
                    for (int i = 0; i < quantity; i++) {
                        long assetId = assetRepository.insertAsset(tenantId, item.itemName(), null, item
                            .spec(), OaAssetStatus.IN_STOCK, location, item.unitPrice(), id, req
                                .receiveDate(), operatorId, now);
                        assetRepository.assignAssetNo(tenantId, assetId, assetNo(now, assetId));
                        assetRepository
                            .insertAssetLog(tenantId, assetId, "IN", null, OaAssetStatus.IN_STOCK, null, null, location, REF_PURCHASE, id, null, operatorId, "采购 " + purchase
                                .purchaseNo() + " 验收入库", now);
                    }
                }
            }
            for (Map.Entry<Long, Integer> entry : stockIn.entrySet()) {
                if (assetRepository.changeStock(tenantId, entry.getKey(), entry.getValue(), now) != 1) {
                    throw new BusinessException("用品 " + stockInCodes.get(entry.getKey()) + " 入库失败，请刷新后重试");
                }
                OaSupplyItemResp supply = assetRepository.supplyItem(tenantId, entry.getKey(), false);
                assetRepository.insertSupplyLedger(tenantId, supply.id(), "IN", entry.getValue(), supply
                    .stock(), REF_PURCHASE, id, null, operatorId, "采购 " + purchase.purchaseNo() + " 验收入库", now);
            }
            boolean complete = true;
            for (OaPurchaseItemResp item : items.values()) {
                if (item.receivedQty() + received.getOrDefault(item.id(), 0) < item.quantity()) {
                    complete = false;
                    break;
                }
            }
            String target = complete ? OaPurchaseHook.STATUS_RECEIVED : OaPurchaseHook.STATUS_PARTIAL;
            if (repository.markReceived(tenantId, id, purchase.status(), target, now) != 1) {
                throw new BusinessException("采购状态已变化，请刷新后重试");
            }
            OaPurchaseResp current = repository.purchase(tenantId, id);
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(purchase
                .applicationId()), EVENT_RECEIVED, "RECEIVED:" + receiptId, purchase
                    .applicationId(), null, "RECEIVED", complete ? "采购已全部验收" : "采购已部分验收", "您的采购「" + current
                        .title() + "」" + (complete ? "已全部验收入库" : "已部分验收入库"), List.of(purchase.applicantId()));
        });
        return detail(tenantId, null, id);
    }

    /**
     * 初始化采购试点：已发布表单与 PURCHASE 业务类型的草稿流程模型。
     */
    public OaPurchaseTemplateResp template(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            OaFormResp form = formService
                .create(tenantId, userId, new OaFormCreateReq(FORM_KEY, "采购申请", "采购申请试点模板，明细字段 key 不可修改", templateSchema()));
            OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
            if (!published.valid()) {
                throw new BusinessException("采购表单发布失败：" + String.join("；", published.errors()));
            }
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(MODEL_KEY, "采购申请", "purchase", "直属上级 → 财务岗位审核；合计超过 20000 元再由总经理岗位审批；审批通过占用转已用，再由采购经办下单与验收", BPMN, OaPurchaseHook.BUSINESS_TYPE));
            return new OaPurchaseTemplateResp(form.id(), model.id(), model.modelKey());
        });
    }

    public String templateSchema() {
        Map<String, Object> items = field(OaPurchaseHook.FIELD_ITEMS, "采购明细", "table", true);
        items.put("maxRows", 50);
        items.put("columns", List
            .of(column(OaPurchaseHook.COL_NAME, "物品名称", "text", true, null), column(OaPurchaseHook.COL_CATEGORY, "类别", "radio", true, List
                .of(option("用品（入库）", OaPurchaseHook.CATEGORY_SUPPLY), option("资产（建卡）", OaPurchaseHook.CATEGORY_ASSET), option("其他", OaPurchaseHook.CATEGORY_OTHER))), column(OaPurchaseHook.COL_SUPPLY_CODE, "用品编码", "text", false, null), column(OaPurchaseHook.COL_SPEC, "规格", "text", false, null), column(OaPurchaseHook.COL_QUANTITY, "数量", "number", true, null), column(OaPurchaseHook.COL_PRICE, "单价（元）", "number", true, null)));
        List<Map<String, Object>> fields = List
            .of(field(OaPurchaseHook.FIELD_REASON, "采购事由", "textarea", true), items, field(OaPurchaseHook.FIELD_EXPECTED_DATE, "期望到货日期", "date", false));
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("采购模板 schema 序列化失败", e);
        }
    }

    static String assetNo(LocalDateTime now, long id) {
        return "AS" + now.format(NO_DATE) + String.format("%04d", id);
    }

    static Map<String, Object> field(String key, String label, String type, boolean required) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", "f_" + key);
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        return field;
    }

    static Map<String, Object> column(String key,
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

    static Map<String, Object> option(String label, String value) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("label", label);
        option.put("value", value);
        return option;
    }

    private PurchaseRow requirePurchase(long tenantId, long id) {
        PurchaseRow purchase = repository.lockPurchase(tenantId, id);
        if (purchase == null) {
            throw new BusinessException("采购单不存在");
        }
        return purchase;
    }

    static void requireNotFuture(LocalDate date, String label) {
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException(label + "不能晚于今天");
        }
    }

    private static String checked(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!STATUSES.contains(status)) {
            throw new BusinessException("采购单状态无效: " + status);
        }
        return status;
    }

    static int safeSize(int size) {
        return Math.min(100, Math.max(1, size));
    }

    static int offset(int page, int size) {
        return (Math.max(1, page) - 1) * size;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_purchase" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="purchase_pilot" name="采购申请" isExecutable="true">
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
            <bpmn:exclusiveGateway id="amountGateway" name="超过 20000 元？" default="flowSmall">
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
            <bpmn:sequenceFlow id="flowLarge" name="大于 20000 元" sourceRef="amountGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${purchaseAmount &gt; 20000}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="20000 元及以内" sourceRef="amountGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_purchase">
            <bpmndi:BPMNPlane id="BPMNPlane_purchase" bpmnElement="purchase_pilot">
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
