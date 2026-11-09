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

package com.guardfind.backend.oa.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository.TravelRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出差申请钩子：发起时校验行程与预算并拒绝与本人未关闭出差的时间重叠，
 * 提交后生成出差单号；通过后可被借款与报销引用，驳回/撤回/终止时关闭。
 */
@Component
public class OaTravelHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "TRAVEL";
    public static final String FIELD_DESTINATION = "destination";
    public static final String FIELD_START_DATE = "startDate";
    public static final String FIELD_END_DATE = "endDate";
    public static final String FIELD_BUDGET = "budgetAmount";
    public static final String VAR_BUDGET = "travelBudget";
    public static final String VAR_DAYS = "travelDays";
    public static final String VAR_FINANCE_REVIEWER = "financeReviewer";
    public static final String FINANCE_POSITION = "FINANCE";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final int MAX_DAYS = 90;
    public static final int MAX_BACKDATE_DAYS = 30;

    public static final Set<String> CLOSE_STATUSES = Set.of("REJECTED", "WITHDRAWN", "CANCELLED");
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String FORM_NAME = "出差表单";

    private final OaTravelRepository repository;

    public OaTravelHook(OaTravelRepository repository) {
        this.repository = repository;
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
        OaBusinessFields.requireField(fields, FIELD_DESTINATION, Set.of("text"), true, "目的地", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_START_DATE, Set.of("date"), true, "开始日期", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_END_DATE, Set.of("date"), true, "结束日期", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_BUDGET, Set.of("number"), true, "预算金额", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINANCE_REVIEWER, OaAssigneeSpec.position(FINANCE_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String destination = OaBusinessFields.requireText(formData.get(FIELD_DESTINATION), "目的地", 100);
        LocalDate start = OaBusinessFields.date(formData.get(FIELD_START_DATE), "开始日期", true);
        LocalDate end = OaBusinessFields.date(formData.get(FIELD_END_DATE), "结束日期", true);
        if (end.isBefore(start)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (start.isBefore(LocalDate.now().minusDays(MAX_BACKDATE_DAYS))) {
            throw new BusinessException("开始日期不能早于 " + MAX_BACKDATE_DAYS + " 天前");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw new BusinessException("单次出差不能超过 " + MAX_DAYS + " 天");
        }
        BigDecimal budget = OaBusinessFields.amount(formData.get(FIELD_BUDGET), "预算金额");
        repository.lockApplicant(tenantId, applicantId);
        List<String> overlaps = repository.overlappingTravels(tenantId, applicantId, start, end);
        if (!overlaps.isEmpty()) {
            String no = overlaps.get(0) == null ? "（审批中）" : overlaps.get(0);
            throw new BusinessException("与出差 " + no + " 时间重叠");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_BUDGET, budget.doubleValue());
        variables.put(VAR_DAYS, (int)days);
        return new OaBusinessPreparation(variables, new TravelPlan(destination, start, end, (int)days, budget));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        TravelPlan plan = (TravelPlan)preparation.state();
        long id = repository.insertTravel(tenantId, applicationId, applicantId, plan.destination(), plan.start(), plan
            .end(), plan.days(), plan.budget(), STATUS_APPROVING, now);
        repository.assignTravelNo(tenantId, id, "TR" + now.format(NO_DATE) + String.format("%04d", id));
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        TravelRow travel = repository.lockTravelByApplication(tenantId, applicationId);
        if (travel == null || !STATUS_APPROVING.equals(travel.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionTravel(tenantId, travel.id(), STATUS_APPROVING, STATUS_APPROVED, now);
        } else if (CLOSE_STATUSES.contains(status)) {
            repository.transitionTravel(tenantId, travel.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    public record TravelPlan(String destination, LocalDate start, LocalDate end, int days, BigDecimal budget) {
    }
}
