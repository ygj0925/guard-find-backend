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

package com.guardfind.backend.oa.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaAttendanceRepository;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 加班申请钩子：时长按分钟折算为小时（一位小数），0.5–12 小时；不得与本人未关闭的加班/外出或有效请假重叠。
 */
@Component
public class OaOvertimeHook extends OaAttendanceHookSupport {

    public static final String BUSINESS_TYPE = "OVERTIME";
    public static final String FIELD_START_TIME = "startTime";
    public static final String FIELD_END_TIME = "endTime";
    public static final String VAR_HOURS = "overtimeHours";
    public static final BigDecimal MIN_HOURS = new BigDecimal("0.5");
    public static final BigDecimal MAX_HOURS = new BigDecimal("12");

    private static final String FORM_NAME = "加班表单";

    public OaOvertimeHook(OaAttendanceRepository repository) {
        super(repository);
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    protected String kind() {
        return OaAttendanceRepository.KIND_OVERTIME;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        Map<String, JsonNode> fields = OaBusinessFields.fields(schemaJson);
        if (fields == null) {
            return List.of(FORM_NAME + " schema 无法解析");
        }
        List<String> errors = new ArrayList<>();
        OaBusinessFields.requireField(fields, FIELD_START_TIME, Set.of("datetime"), true, "开始时间", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_END_TIME, Set.of("datetime"), true, "结束时间", FORM_NAME, errors);
        return errors;
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        LocalDateTime start = OaBusinessFields.dateTime(formData.get(FIELD_START_TIME), "开始时间", true);
        LocalDateTime end = OaBusinessFields.dateTime(formData.get(FIELD_END_TIME), "结束时间", true);
        BigDecimal hours = span(start, end, MAX_HOURS, "加班");
        if (hours.compareTo(MIN_HOURS) < 0) {
            throw new BusinessException("单次加班不能少于 0.5 小时");
        }
        repository.lockApplicant(tenantId, applicantId);
        rejectOverlaps(tenantId, applicantId, start, end, false);
        return new OaBusinessPreparation(Map.of(VAR_HOURS, hours.doubleValue()), new AttendancePlan(start
            .toLocalDate(), start, end, hours, null, null));
    }
}
