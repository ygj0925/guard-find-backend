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
import com.guardfind.backend.oa.repository.OaAttendanceRepository.RecordRow;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.RequestRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 补卡申请钩子：补卡时间不能晚于当前、不早于 30 天前；同日同类型只能有一张未关闭的补卡，每月最多 5 次；
 * 通过后把补卡时间写入当日考勤记录对应一侧（来源 CORRECTION，之后的导入不会覆盖）。
 */
@Component
public class OaCorrectionHook extends OaAttendanceHookSupport {

    public static final String BUSINESS_TYPE = "ATTENDANCE_CORRECTION";
    public static final String FIELD_CHECK_TYPE = "checkType";
    public static final String FIELD_CHECK_TIME = "checkTime";
    public static final String VAR_CORRECTION_TYPE = "correctionType";
    public static final String CHECK_IN = "IN";
    public static final String CHECK_OUT = "OUT";
    public static final String SOURCE_CORRECTION = "CORRECTION";
    public static final int MAX_PER_MONTH = 5;

    private static final String FORM_NAME = "补卡表单";

    public OaCorrectionHook(OaAttendanceRepository repository) {
        super(repository);
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    protected String kind() {
        return OaAttendanceRepository.KIND_CORRECTION;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        Map<String, JsonNode> fields = OaBusinessFields.fields(schemaJson);
        if (fields == null) {
            return List.of(FORM_NAME + " schema 无法解析");
        }
        List<String> errors = new ArrayList<>();
        OaBusinessFields.requireField(fields, FIELD_CHECK_TYPE, Set.of("radio"), true, "补卡类型", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_CHECK_TIME, Set.of("datetime"), true, "补卡时间", FORM_NAME, errors);
        return errors;
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String checkType = OaBusinessFields.text(formData.get(FIELD_CHECK_TYPE));
        if (!CHECK_IN.equals(checkType) && !CHECK_OUT.equals(checkType)) {
            throw new BusinessException("补卡类型只能是上班（IN）或下班（OUT）");
        }
        LocalDateTime checkTime = OaBusinessFields.dateTime(formData.get(FIELD_CHECK_TIME), "补卡时间", true);
        if (checkTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException("补卡时间不能晚于当前时间");
        }
        LocalDate workDate = checkTime.toLocalDate();
        if (workDate.isBefore(LocalDate.now().minusDays(MAX_BACKDATE_DAYS))) {
            throw new BusinessException("只能补 " + MAX_BACKDATE_DAYS + " 天内的卡");
        }
        if (repository.scheduleRows(tenantId, List.of(applicantId), workDate, workDate)
            .stream()
            .noneMatch(schedule -> schedule.shiftId() != null)) {
            throw new BusinessException(workDate + " 未排班或为休息日，无需补卡");
        }
        repository.lockApplicant(tenantId, applicantId);
        YearMonth month = YearMonth.from(workDate);
        List<RequestRow> active = repository.activeCorrections(tenantId, applicantId, month.atDay(1), month
            .atEndOfMonth());
        for (RequestRow row : active) {
            if (workDate.equals(row.workDate()) && checkType.equals(row.checkType())) {
                throw new BusinessException(workDate + " 已有" + (CHECK_IN.equals(checkType) ? "上班" : "下班") + "补卡申请");
            }
        }
        if (active.size() >= MAX_PER_MONTH) {
            throw new BusinessException(month + " 补卡已达 " + MAX_PER_MONTH + " 次上限");
        }
        return new OaBusinessPreparation(Map
            .of(VAR_CORRECTION_TYPE, checkType), new AttendancePlan(workDate, checkTime, checkTime, BigDecimal.ZERO, checkType, null));
    }

    @Override
    protected void approved(long tenantId, RequestRow request, LocalDateTime now) {
        boolean in = CHECK_IN.equals(request.checkType());
        RecordRow record = repository.lockRecord(tenantId, request.applicantId(), request.workDate());
        if (record == null) {
            try {
                repository.insertRecord(tenantId, request.applicantId(), request.workDate(), in
                    ? request.start()
                    : null, in ? null : request.start(), in ? SOURCE_CORRECTION : null, in
                        ? null
                        : SOURCE_CORRECTION, null, now);
                return;
            } catch (DuplicateKeyException e) {
                record = repository.lockRecord(tenantId, request.applicantId(), request.workDate());
            }
        }
        repository.updateRecord(tenantId, record.id(), in ? request.start() : record.checkIn(), in
            ? record.checkOut()
            : request.start(), in ? SOURCE_CORRECTION : record.checkInSource(), in
                ? record.checkOutSource()
                : SOURCE_CORRECTION, null, now);
    }
}
