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

package com.guardfind.backend.oa.expense;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.model.OaExpenseCategoryResp;
import com.guardfind.backend.oa.model.OaExpenseItemResp;
import com.guardfind.backend.oa.model.OaExpensePreviewResp;
import com.guardfind.backend.oa.repository.OaExpenseRepository;
import com.guardfind.backend.oa.repository.OaExpenseRepository.ExpenseRow;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository.TravelRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.travel.OaLoanHook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 报销业务钩子：发起时由服务端按明细重算合计、校验费用类型规则与发票号唯一，
 * 通过后进入待支付（支付由财务单独登记），驳回/撤回/终止时关闭并释放发票号。
 */
@Component
public class OaExpenseHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "EXPENSE";
    public static final String FIELD_ITEMS = "expenseItems";
    public static final String COL_CATEGORY = "category";
    public static final String COL_AMOUNT = "amount";
    public static final String COL_DATE = "expenseDate";
    public static final String COL_INVOICE = "invoiceNo";
    public static final String COL_DESCRIPTION = "description";
    public static final String FIELD_TRAVEL_NO = "travelNo";
    public static final String VAR_AMOUNT = "expenseAmount";
    public static final String VAR_OVER_BUDGET = "overBudget";
    public static final String VAR_ITEM_COUNT = "expenseItemCount";
    public static final String VAR_FINANCE_REVIEWER = "financeReviewer";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String FINANCE_POSITION = "FINANCE";
    public static final String GM_POSITION = "GM";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Set<String> CLOSE_STATUSES = Set.of("REJECTED", "WITHDRAWN", "CANCELLED");
    private static final BigDecimal MAX_ITEM_AMOUNT = new BigDecimal("9999999999.99");
    private static final BigDecimal MAX_TOTAL_AMOUNT = new BigDecimal("999999999999.99");
    private static final Pattern INVOICE_PATTERN = Pattern.compile("^[A-Z0-9-]{1,64}$");
    private static final int MAX_DESCRIPTION = 500;

    private final OaExpenseRepository repository;
    private final OaTravelRepository travelRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OaExpenseHook(OaExpenseRepository repository, OaTravelRepository travelRepository) {
        this.repository = repository;
        this.travelRepository = travelRepository;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        JsonNode items = null;
        try {
            JsonNode root = objectMapper.readTree(schemaJson == null ? "{}" : schemaJson);
            for (JsonNode field : root.path("fields")) {
                if (FIELD_ITEMS.equals(field.path("key").asText(""))) {
                    items = field;
                }
            }
        } catch (Exception e) {
            return List.of("报销表单 schema 无法解析");
        }
        if (items == null) {
            return List.of("报销表单缺少字段 " + FIELD_ITEMS + "（报销明细，类型 table）");
        }
        List<String> errors = new ArrayList<>();
        if (!"table".equals(items.path("type").asText(""))) {
            errors.add("报销表单字段 " + FIELD_ITEMS + " 必须是 table 类型");
            return errors;
        }
        if (items.path("hidden").asBoolean(false) || items.path("readonly").asBoolean(false)) {
            errors.add("报销表单字段 " + FIELD_ITEMS + " 不能隐藏或只读");
        }
        if (!items.path("required").asBoolean(false)) {
            errors.add("报销表单字段 " + FIELD_ITEMS + " 必须设为必填");
        }
        Map<String, JsonNode> columns = new LinkedHashMap<>();
        for (JsonNode column : items.path("columns")) {
            columns.put(column.path("key").asText(""), column);
        }
        requireColumn(columns, COL_CATEGORY, Set.of("radio"), true, "费用类型", errors);
        requireColumn(columns, COL_AMOUNT, Set.of("number"), true, "金额", errors);
        requireColumn(columns, COL_DATE, Set.of("date"), false, "发生日期", errors);
        requireColumn(columns, COL_INVOICE, Set.of("text"), false, "发票号", errors);
        requireColumn(columns, COL_DESCRIPTION, Set.of("text", "textarea"), false, "说明", errors);
        Map<String, JsonNode> fields = OaBusinessFields.fields(schemaJson);
        if (fields != null) {
            OaBusinessFields.requireField(fields, FIELD_TRAVEL_NO, Set.of("text"), false, "关联出差单号", "报销表单", errors);
        }
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINANCE_REVIEWER, OaAssigneeSpec
            .position(FINANCE_POSITION), VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        ExpensePlan plan = plan(tenantId, applicantId, formData, true);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_AMOUNT, plan.total().doubleValue());
        variables.put(VAR_ITEM_COUNT, plan.items().size());
        variables.put(VAR_OVER_BUDGET, plan.overBudget());
        return new OaBusinessPreparation(variables, plan);
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        ExpensePlan plan = (ExpensePlan)preparation.state();
        long expenseId = repository.insertExpense(tenantId, applicationId, applicantId, plan.total(), plan.items()
            .size(), plan.travelId(), plan.overBudget(), STATUS_APPROVING, now);
        for (OaExpenseItemResp item : plan.items()) {
            repository.insertItem(tenantId, expenseId, applicationId, item);
        }
        for (String invoiceNo : plan.invoiceNos()) {
            try {
                repository.insertInvoice(tenantId, invoiceNo, applicationId, now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("发票号 " + invoiceNo + " 已被其他报销使用");
            }
        }
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        ExpenseRow expense = repository.lockByApplication(tenantId, applicationId);
        if (expense == null || !STATUS_APPROVING.equals(expense.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transition(tenantId, expense.id(), STATUS_APPROVING, STATUS_PENDING_PAYMENT, now);
        } else if (CLOSE_STATUSES.contains(status) && repository.transition(tenantId, expense
            .id(), STATUS_APPROVING, STATUS_CLOSED, now) == 1) {
            repository.releaseInvoices(tenantId, applicationId);
        }
    }

    public OaExpensePreviewResp preview(long tenantId, long applicantId, Map<String, Object> formData) {
        try {
            ExpensePlan plan = plan(tenantId, applicantId, formData, false);
            return new OaExpensePreviewResp(plan.total(), plan.items().size(), null, plan.overBudget(), plan
                .budgetHint());
        } catch (BusinessException e) {
            return new OaExpensePreviewResp(null, 0, e.getMessage(), false, null);
        }
    }

    private ExpensePlan plan(long tenantId, long applicantId, Map<String, Object> formData, boolean lock) {
        Object raw = formData.get(FIELD_ITEMS);
        if (raw != null && !(raw instanceof Collection<?>)) {
            throw new BusinessException("报销明细格式非法");
        }
        Map<String, OaExpenseCategoryResp> categories = new LinkedHashMap<>();
        for (OaExpenseCategoryResp category : repository.categories(tenantId, true)) {
            categories.put(category.code(), category);
        }
        List<OaExpenseItemResp> items = new ArrayList<>();
        Set<String> invoiceNos = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        int line = 0;
        for (Object row : raw == null ? List.of() : (Collection<?>)raw) {
            if (!(row instanceof Map<?, ?> cells)) {
                throw new BusinessException("报销明细第 " + (line + 1) + " 行格式非法");
            }
            if (blankRow(cells)) {
                continue;
            }
            line++;
            OaExpenseItemResp item = item(line, cells, categories);
            if (item.invoiceNo() != null && !invoiceNos.add(item.invoiceNo())) {
                throw new BusinessException("第 " + line + " 行发票号 " + item.invoiceNo() + " 在本次报销中重复");
            }
            items.add(item);
            total = total.add(item.amount());
        }
        if (items.isEmpty()) {
            throw new BusinessException("请至少填写一条报销明细");
        }
        if (total.compareTo(MAX_TOTAL_AMOUNT) > 0) {
            throw new BusinessException("报销合计超出范围");
        }
        List<String> used = repository.usedInvoices(tenantId, invoiceNos);
        if (!used.isEmpty()) {
            throw new BusinessException("发票号 " + String.join("、", used) + " 已被其他报销使用");
        }
        Long travelId = null;
        boolean overBudget = false;
        String budgetHint = null;
        String travelNo = text(formData.get(FIELD_TRAVEL_NO));
        if (travelNo != null) {
            TravelRow travel = OaLoanHook.approvedTravel(travelRepository, tenantId, applicantId, travelNo, lock);
            BigDecimal claimed = travelRepository.activeExpenseTotal(tenantId, travel.id(), lock).add(total);
            travelId = travel.id();
            overBudget = claimed.compareTo(travel.budget()) > 0;
            budgetHint = "出差 " + travel.travelNo() + " 预算 " + plain(travel
                .budget()) + " 元，含本单累计报销 " + plain(claimed) + " 元" + (overBudget ? "，已超预算，将追加总经理复核" : "");
        }
        return new ExpensePlan(List.copyOf(items), total, List.copyOf(invoiceNos), travelId, overBudget, budgetHint);
    }

    private static OaExpenseItemResp item(int line, Map<?, ?> cells, Map<String, OaExpenseCategoryResp> categories) {
        String prefix = "第 " + line + " 行";
        String code = text(cells.get(COL_CATEGORY));
        if (code == null) {
            throw new BusinessException(prefix + "请选择费用类型");
        }
        OaExpenseCategoryResp category = categories.get(code);
        if (category == null) {
            throw new BusinessException(prefix + "费用类型不存在或已停用: " + code);
        }
        BigDecimal amount = amount(cells.get(COL_AMOUNT), prefix);
        if (category.perItemLimit() != null && amount.compareTo(category.perItemLimit()) > 0) {
            throw new BusinessException(prefix + category.name() + "单笔不能超过 " + plain(category
                .perItemLimit()) + " 元，本行 " + plain(amount) + " 元");
        }
        String invoiceNo = invoice(cells.get(COL_INVOICE), prefix);
        if (invoiceNo == null && category.invoiceRequired()) {
            throw new BusinessException(prefix + category.name() + "必须填写发票号");
        }
        LocalDate expenseDate = expenseDate(cells.get(COL_DATE), prefix);
        String description = text(cells.get(COL_DESCRIPTION));
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new BusinessException(prefix + "说明不能超过 " + MAX_DESCRIPTION + " 个字符");
        }
        return new OaExpenseItemResp(line, category.code(), category
            .name(), amount, expenseDate, invoiceNo, description);
    }

    private static BigDecimal amount(Object value, String prefix) {
        BigDecimal amount;
        if (value instanceof BigDecimal decimal) {
            amount = decimal;
        } else if (value instanceof Number number) {
            amount = new BigDecimal(number.toString());
        } else {
            String text = text(value);
            if (text == null) {
                throw new BusinessException(prefix + "请填写金额");
            }
            try {
                amount = new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new BusinessException(prefix + "金额格式无效");
            }
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(prefix + "金额必须大于 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(prefix + "金额最多两位小数");
        }
        if (amount.compareTo(MAX_ITEM_AMOUNT) > 0) {
            throw new BusinessException(prefix + "金额超出范围");
        }
        return amount.setScale(2);
    }

    private static String invoice(Object value, String prefix) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!INVOICE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(prefix + "发票号只能包含字母、数字和短横线，且不超过 64 位");
        }
        return normalized;
    }

    private static LocalDate expenseDate(Object value, String prefix) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            throw new BusinessException(prefix + "发生日期格式无效");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException(prefix + "发生日期不能晚于今天");
        }
        return date;
    }

    private static boolean blankRow(Map<?, ?> cells) {
        for (Object value : cells.values()) {
            if (text(value) != null) {
                return false;
            }
        }
        return true;
    }

    private static void requireColumn(Map<String, JsonNode> columns,
                                      String key,
                                      Set<String> types,
                                      boolean required,
                                      String label,
                                      List<String> errors) {
        JsonNode column = columns.get(key);
        if (column == null) {
            if (required) {
                errors.add("报销明细缺少列 " + key + "（" + label + "，类型 " + String.join("/", types) + "）");
            }
            return;
        }
        if (!types.contains(column.path("type").asText(""))) {
            errors.add("报销明细列 " + key + " 必须是 " + String.join("/", types) + " 类型");
        }
        if (required && !column.path("required").asBoolean(false)) {
            errors.add("报销明细列 " + key + " 必须设为必填");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public record ExpensePlan(List<OaExpenseItemResp> items, BigDecimal total, List<String> invoiceNos, Long travelId,
                              boolean overBudget, String budgetHint) {
    }
}
