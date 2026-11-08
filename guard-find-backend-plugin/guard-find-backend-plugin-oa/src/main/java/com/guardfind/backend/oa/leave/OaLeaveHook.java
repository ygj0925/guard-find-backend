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

package com.guardfind.backend.oa.leave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.model.OaLeavePreviewResp;
import com.guardfind.backend.oa.model.OaLeaveTypeResp;
import com.guardfind.backend.oa.repository.OaLeaveRepository;
import com.guardfind.backend.oa.repository.OaLeaveRepository.Balance;
import com.guardfind.backend.oa.repository.OaLeaveRepository.LeaveRequestRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请假业务钩子：发起时按工作日历折算天数并锁定年度余额占用，通过时转为已扣减，
 * 驳回/撤回/终止时释放；不扣余额的类型只记录请假单。
 */
@Component
public class OaLeaveHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "LEAVE";
    public static final String FIELD_TYPE = "leaveType";
    public static final String FIELD_START_DATE = "startDate";
    public static final String FIELD_START_HALF = "startHalf";
    public static final String FIELD_END_DATE = "endDate";
    public static final String FIELD_END_HALF = "endHalf";
    public static final String VAR_DAYS = "leaveDays";
    public static final String VAR_TYPE_CODE = "leaveTypeCode";
    public static final String VAR_HR_REVIEWER = "hrReviewer";
    public static final String HR_POSITION = "HR";

    public static final String STATUS_OCCUPIED = "OCCUPIED";
    public static final String STATUS_DEDUCTED = "DEDUCTED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_RECORDED = "RECORDED";

    private static final Set<String> RELEASE_STATUSES = Set.of("REJECTED", "WITHDRAWN", "CANCELLED");

    private final OaLeaveRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OaLeaveHook(OaLeaveRepository repository) {
        this.repository = repository;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(schemaJson == null ? "{}" : schemaJson);
            for (JsonNode field : root.path("fields")) {
                fields.put(field.path("key").asText(""), field);
            }
        } catch (Exception e) {
            return List.of("请假表单 schema 无法解析");
        }
        List<String> errors = new ArrayList<>();
        requireField(fields, FIELD_TYPE, "radio", true, "假期类型", errors);
        requireField(fields, FIELD_START_DATE, "date", true, "开始日期", errors);
        requireField(fields, FIELD_END_DATE, "date", true, "结束日期", errors);
        requireField(fields, FIELD_START_HALF, "radio", false, "开始时段", errors);
        requireField(fields, FIELD_END_HALF, "radio", false, "结束时段", errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_HR_REVIEWER, OaAssigneeSpec.position(HR_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        LeavePlan plan = plan(tenantId, applicantId, formData, true);
        if (plan.balance() != null && plan.balance().available().compareTo(plan.days()) < 0) {
            throw new BusinessException(insufficient(plan));
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_DAYS, plan.days().doubleValue());
        variables.put(VAR_TYPE_CODE, plan.type().code());
        return new OaBusinessPreparation(variables, plan);
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        LeavePlan plan = (LeavePlan)preparation.state();
        Long balanceId = plan.balance() == null ? null : plan.balance().id();
        repository.insertRequest(tenantId, applicationId, applicantId, plan.type(), plan.start(), plan.startHalf(), plan
            .end(), plan.endHalf(), plan.days(), plan.start().getYear(), balanceId, balanceId == null
                ? STATUS_RECORDED
                : STATUS_OCCUPIED, now);
        if (balanceId == null) {
            return;
        }
        if (repository.occupy(tenantId, balanceId, plan.days(), now) != 1) {
            throw new BusinessException(insufficient(plan));
        }
        repository.insertLedger(tenantId, balanceId, applicationId, "OCCUPY", plan.days(), repository
            .balanceById(tenantId, balanceId), applicantId, null, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        LeaveRequestRow request = repository.lockRequest(tenantId, applicationId);
        if (request == null || request.balanceId() == null || !STATUS_OCCUPIED.equals(request.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            settle(tenantId, request, STATUS_DEDUCTED, "DEDUCT", operatorId, now);
        } else if (RELEASE_STATUSES.contains(status)) {
            settle(tenantId, request, STATUS_RELEASED, "RELEASE", operatorId, now);
        }
    }

    public OaLeavePreviewResp preview(long tenantId, long applicantId, Map<String, Object> formData) {
        LeavePlan plan;
        try {
            plan = plan(tenantId, applicantId, formData, false);
        } catch (BusinessException e) {
            return new OaLeavePreviewResp(null, null, false, null, e.getMessage());
        }
        if (plan.balance() == null) {
            return new OaLeavePreviewResp(plan.days(), plan.start().getYear(), false, null, null);
        }
        BigDecimal available = plan.balance().available();
        return new OaLeavePreviewResp(plan.days(), plan.start().getYear(), true, available, available.compareTo(plan
            .days()) < 0 ? insufficient(plan) : null);
    }

    private void settle(long tenantId,
                        LeaveRequestRow request,
                        String targetStatus,
                        String action,
                        long operatorId,
                        LocalDateTime now) {
        if (repository.transitionRequest(tenantId, request.id(), STATUS_OCCUPIED, targetStatus, now) != 1) {
            return;
        }
        int rows = STATUS_DEDUCTED.equals(targetStatus)
            ? repository.deduct(tenantId, request.balanceId(), request.days(), now)
            : repository.release(tenantId, request.balanceId(), request.days(), now);
        if (rows != 1) {
            throw new IllegalStateException("假期余额占用与请假单不一致: application=" + request.applicationId());
        }
        repository.insertLedger(tenantId, request.balanceId(), request.applicationId(), action, request
            .days(), repository.balanceById(tenantId, request.balanceId()), operatorId, null, now);
    }

    private LeavePlan plan(long tenantId, long applicantId, Map<String, Object> formData, boolean lock) {
        String code = text(formData.get(FIELD_TYPE));
        if (code == null) {
            throw new BusinessException("请选择假期类型");
        }
        OaLeaveTypeResp type = repository.enabledTypeByCode(tenantId, code);
        if (type == null) {
            throw new BusinessException("假期类型不存在或已停用: " + code);
        }
        LocalDate start = date(formData.get(FIELD_START_DATE), "开始日期");
        LocalDate end = date(formData.get(FIELD_END_DATE), "结束日期");
        String startHalf = half(formData.get(FIELD_START_HALF), OaLeaveDayCalculator.AM);
        String endHalf = half(formData.get(FIELD_END_HALF), OaLeaveDayCalculator.PM);
        BigDecimal days;
        try {
            OaLeaveDayCalculator.requireRange(start, startHalf, end, endHalf);
            days = OaLeaveDayCalculator.days(start, startHalf, end, endHalf, repository
                .calendarOverrides(tenantId, start, end));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        if (days.signum() <= 0) {
            throw new BusinessException("所选时间段没有工作日，无需请假");
        }
        if (type.maxDaysPerRequest() != null && days.compareTo(type.maxDaysPerRequest()) > 0) {
            throw new BusinessException(type.name() + "单次最多 " + type.maxDaysPerRequest()
                .stripTrailingZeros()
                .toPlainString() + " 天，本次 " + plain(days) + " 天");
        }
        Balance balance = null;
        if (type.balanceRequired()) {
            int year = start.getYear();
            balance = lock
                ? repository.lockBalance(tenantId, applicantId, type.id(), year)
                : repository.balance(tenantId, applicantId, type.id(), year);
            if (balance == null) {
                throw new BusinessException("尚未发放 " + year + " 年度" + type.name() + "额度，请联系人事");
            }
        }
        return new LeavePlan(type, start, startHalf, end, endHalf, days, balance);
    }

    private static void requireField(Map<String, JsonNode> fields,
                                     String key,
                                     String type,
                                     boolean required,
                                     String label,
                                     List<String> errors) {
        JsonNode field = fields.get(key);
        if (field == null) {
            if (required) {
                errors.add("请假表单缺少字段 " + key + "（" + label + "，类型 " + type + "）");
            }
            return;
        }
        if (!type.equals(field.path("type").asText(""))) {
            errors.add("请假表单字段 " + key + " 必须是 " + type + " 类型");
        }
        if (field.path("hidden").asBoolean(false) || field.path("readonly").asBoolean(false)) {
            errors.add("请假表单字段 " + key + " 不能隐藏或只读");
        }
    }

    private static String insufficient(LeavePlan plan) {
        return plan.type().name() + "余额不足：可用 " + plain(plan.balance().available()) + " 天，本次需 " + plain(plan
            .days()) + " 天";
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static LocalDate date(Object value, String label) {
        String text = text(value);
        if (text == null) {
            throw new BusinessException("请选择" + label);
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new BusinessException(label + "格式无效");
        }
    }

    private static String half(Object value, String fallback) {
        String text = text(value);
        return text == null ? fallback : text;
    }

    public record LeavePlan(OaLeaveTypeResp type, LocalDate start, String startHalf, LocalDate end, String endHalf,
                            BigDecimal days, Balance balance) {
    }
}
