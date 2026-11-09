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

package com.guardfind.backend.oa.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.model.OaPurchaseItemResp;
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.repository.OaAssetRepository;
import com.guardfind.backend.oa.repository.OaPurchaseRepository;
import com.guardfind.backend.oa.repository.OaPurchaseRepository.BudgetRow;
import com.guardfind.backend.oa.repository.OaPurchaseRepository.PurchaseRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 采购申请钩子：服务端按明细重算金额，发起时锁定部门当年预算并占用，通过后占用转已用进入待下单，
 * 驳回/撤回/终止时释放占用并关闭。审批通过不产生库存或资产，只有验收入库才会。
 */
@Component
public class OaPurchaseHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "PURCHASE";
    public static final String FIELD_ITEMS = "purchaseItems";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_EXPECTED_DATE = "expectedDate";
    public static final String COL_NAME = "itemName";
    public static final String COL_CATEGORY = "category";
    public static final String COL_SUPPLY_CODE = "supplyCode";
    public static final String COL_SPEC = "spec";
    public static final String COL_QUANTITY = "quantity";
    public static final String COL_PRICE = "unitPrice";
    public static final String VAR_AMOUNT = "purchaseAmount";
    public static final String VAR_ITEM_COUNT = "purchaseItemCount";
    public static final String VAR_FINANCE_REVIEWER = "financeReviewer";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String FINANCE_POSITION = "FINANCE";
    public static final String GM_POSITION = "GM";

    public static final String CATEGORY_SUPPLY = "SUPPLY";
    public static final String CATEGORY_ASSET = "ASSET";
    public static final String CATEGORY_OTHER = "OTHER";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_ORDER = "PENDING_ORDER";
    public static final String STATUS_ORDERED = "ORDERED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String LEDGER_GRANT = "GRANT";
    public static final String LEDGER_ADJUST = "ADJUST";
    public static final String LEDGER_OCCUPY = "OCCUPY";
    public static final String LEDGER_RELEASE = "RELEASE";
    public static final String LEDGER_USE = "USE";

    private static final String FORM_NAME = "采购表单";
    private static final String ITEMS_LABEL = "采购明细";
    private static final Set<String> CATEGORIES = Set.of(CATEGORY_SUPPLY, CATEGORY_ASSET, CATEGORY_OTHER);
    private static final int MAX_ROWS = 50;
    private static final int MAX_QUANTITY = 9999;
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final OaPurchaseRepository repository;
    private final OaAssetRepository assetRepository;
    private final OaStaffDirectory directory;

    public OaPurchaseHook(OaPurchaseRepository repository,
                          OaAssetRepository assetRepository,
                          OaStaffDirectory directory) {
        this.repository = repository;
        this.assetRepository = assetRepository;
        this.directory = directory;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        Map<String, JsonNode> fields = OaBusinessFields.fields(schemaJson);
        if (fields == null) {
            return List.of(FORM_NAME + " schema 无法解析");
        }
        List<String> errors = new ArrayList<>();
        Map<String, JsonNode> columns = OaBusinessFields
            .tableColumns(fields, FIELD_ITEMS, ITEMS_LABEL, FORM_NAME, errors);
        if (columns != null) {
            OaBusinessFields.requireColumn(columns, COL_NAME, Set.of("text"), true, "物品名称", ITEMS_LABEL, errors);
            OaBusinessFields.requireColumn(columns, COL_CATEGORY, Set.of("radio"), true, "类别", ITEMS_LABEL, errors);
            OaBusinessFields.requireColumn(columns, COL_SUPPLY_CODE, Set
                .of("text"), false, "用品编码", ITEMS_LABEL, errors);
            OaBusinessFields.requireColumn(columns, COL_SPEC, Set.of("text"), false, "规格", ITEMS_LABEL, errors);
            OaBusinessFields.requireColumn(columns, COL_QUANTITY, Set.of("number"), true, "数量", ITEMS_LABEL, errors);
            OaBusinessFields.requireColumn(columns, COL_PRICE, Set.of("number"), true, "单价", ITEMS_LABEL, errors);
        }
        OaBusinessFields.requireField(fields, FIELD_REASON, Set
            .of("text", "textarea"), true, "采购事由", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_EXPECTED_DATE, Set.of("date"), false, "期望到货日期", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINANCE_REVIEWER, OaAssigneeSpec
            .position(FINANCE_POSITION), VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        List<Map<?, ?>> rows = OaBusinessFields.rows(formData.get(FIELD_ITEMS), ITEMS_LABEL);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ITEMS_LABEL + "不能超过 " + MAX_ROWS + " 行");
        }
        List<OaPurchaseItemResp> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<?, ?> cells : rows) {
            OaPurchaseItemResp item = item(tenantId, items.size() + 1, cells);
            items.add(item);
            total = total.add(item.amount());
        }
        if (total.compareTo(OaBusinessFields.MAX_AMOUNT) > 0) {
            throw new BusinessException("采购合计超出范围");
        }
        OaBusinessFields.requireText(formData.get(FIELD_REASON), "采购事由", 500);
        LocalDate expectedDate = OaBusinessFields.date(formData.get(FIELD_EXPECTED_DATE), "期望到货日期", false);
        if (expectedDate != null && expectedDate.isBefore(LocalDate.now())) {
            throw new BusinessException("期望到货日期不能早于今天");
        }
        Long deptId = directory.departmentId(tenantId, applicantId);
        if (deptId == null) {
            throw new BusinessException("申请人未设置部门，无法占用采购预算");
        }
        int year = LocalDate.now().getYear();
        BudgetRow budget = repository.lockBudget(tenantId, deptId, year);
        if (budget == null) {
            throw new BusinessException("本部门 " + year + " 年度采购预算未设置");
        }
        if (budget.available().compareTo(total) < 0) {
            throw new BusinessException("部门采购预算不足：可用 " + OaBusinessFields.plain(budget
                .available()) + " 元，本单 " + OaBusinessFields.plain(total) + " 元");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_AMOUNT, total.doubleValue());
        variables.put(VAR_ITEM_COUNT, items.size());
        return new OaBusinessPreparation(variables, new PurchasePlan(List.copyOf(items), total, deptId, budget
            .id(), expectedDate));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        PurchasePlan plan = (PurchasePlan)preparation.state();
        long purchaseId = repository.insertPurchase(tenantId, applicationId, applicantId, plan.deptId(), plan
            .budgetId(), plan.total(), plan.items().size(), plan.expectedDate(), STATUS_APPROVING, now);
        repository.assignPurchaseNo(tenantId, purchaseId, "PO" + now.format(NO_DATE) + String
            .format("%04d", purchaseId));
        for (OaPurchaseItemResp item : plan.items()) {
            repository.insertItem(tenantId, purchaseId, item);
        }
        if (repository.occupy(tenantId, plan.budgetId(), plan.total(), now) != 1) {
            throw new BusinessException("部门采购预算不足，请刷新后重试");
        }
        repository.insertBudgetLedger(tenantId, plan.budgetId(), LEDGER_OCCUPY, plan
            .total(), purchaseId, applicantId, null, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        PurchaseRow purchase = repository.lockPurchaseByApplication(tenantId, applicationId);
        if (purchase == null || !STATUS_APPROVING.equals(purchase.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionPurchase(tenantId, purchase.id(), STATUS_APPROVING, STATUS_PENDING_ORDER, now);
            if (repository.consume(tenantId, purchase.budgetId(), purchase.total(), now) != 1) {
                throw new IllegalStateException("采购预算占用与采购单不一致: " + purchase.purchaseNo());
            }
            repository.insertBudgetLedger(tenantId, purchase.budgetId(), LEDGER_USE, purchase.total(), purchase
                .id(), operatorId, null, now);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionPurchase(tenantId, purchase.id(), STATUS_APPROVING, STATUS_CLOSED, now);
            if (repository.release(tenantId, purchase.budgetId(), purchase.total(), now) != 1) {
                throw new IllegalStateException("采购预算占用与采购单不一致: " + purchase.purchaseNo());
            }
            repository.insertBudgetLedger(tenantId, purchase.budgetId(), LEDGER_RELEASE, purchase.total(), purchase
                .id(), operatorId, null, now);
        }
    }

    private OaPurchaseItemResp item(long tenantId, int line, Map<?, ?> cells) {
        String prefix = "第 " + line + " 行";
        String name = OaBusinessFields.requireText(cells.get(COL_NAME), prefix + "物品名称", 100);
        String category = OaBusinessFields.text(cells.get(COL_CATEGORY));
        category = category == null ? null : category.toUpperCase(Locale.ROOT);
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BusinessException(prefix + "类别必须是用品、资产或其他");
        }
        String supplyCode = null;
        if (CATEGORY_SUPPLY.equals(category)) {
            supplyCode = OaBusinessFields.text(cells.get(COL_SUPPLY_CODE));
            if (supplyCode == null) {
                throw new BusinessException(prefix + "用品类采购必须填写用品编码");
            }
            OaSupplyItemResp supply = assetRepository.supplyItemByCode(tenantId, supplyCode, false);
            if (supply == null || !"ENABLED".equals(supply.status())) {
                throw new BusinessException(prefix + "用品编码 " + supplyCode + " 不存在或已停用");
            }
            supplyCode = supply.code();
        }
        String spec = OaBusinessFields.text(cells.get(COL_SPEC));
        if (spec != null && spec.length() > 200) {
            throw new BusinessException(prefix + "规格不能超过 200 个字符");
        }
        int quantity = OaBusinessFields.quantity(cells.get(COL_QUANTITY), prefix + "数量", MAX_QUANTITY);
        BigDecimal unitPrice = OaBusinessFields.amount(cells.get(COL_PRICE), prefix + "单价");
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2);
        if (amount.compareTo(OaBusinessFields.MAX_AMOUNT) > 0) {
            throw new BusinessException(prefix + "金额超出范围");
        }
        return new OaPurchaseItemResp(0, line, name, category, supplyCode, spec, quantity, unitPrice, amount, 0);
    }

    public record PurchasePlan(List<OaPurchaseItemResp> items, BigDecimal total, long deptId, long budgetId,
                               LocalDate expectedDate) {
    }
}
