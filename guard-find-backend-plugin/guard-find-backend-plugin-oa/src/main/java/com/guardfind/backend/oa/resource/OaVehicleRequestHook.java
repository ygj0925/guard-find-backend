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

package com.guardfind.backend.oa.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaVehicleRepository;
import com.guardfind.backend.oa.repository.OaVehicleRepository.RequestRow;
import com.guardfind.backend.oa.repository.OaVehicleRepository.RequestValues;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用车申请钩子：审批只决定能否用车，通过后进入待调度，由调度员指派车辆与司机；
 * 审批通过时若用车时段已结束则必须驳回。
 */
@Component
public class OaVehicleRequestHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "VEHICLE_USE";
    public static final String FIELD_PURPOSE = "purpose";
    public static final String FIELD_DESTINATION = "destination";
    public static final String FIELD_PASSENGERS = "passengers";
    public static final String FIELD_START = "useStart";
    public static final String FIELD_END = "useEnd";
    public static final String FIELD_NEED_DRIVER = "needDriver";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_DISPATCH = "PENDING_DISPATCH";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_IN_USE = "IN_USE";
    public static final String STATUS_RETURNED = "RETURNED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String EVENT_APPROVED = "VEHICLE_APPROVED";
    public static final Duration MAX_DURATION = Duration.ofDays(7);
    public static final int MAX_ADVANCE_DAYS = 90;
    public static final int MAX_PASSENGERS = 50;

    private static final String FORM_NAME = "用车申请表单";

    private final OaVehicleRepository repository;
    private final OaOutboxService outboxService;

    public OaVehicleRequestHook(OaVehicleRepository repository, OaOutboxService outboxService) {
        this.repository = repository;
        this.outboxService = outboxService;
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
        OaBusinessFields.requireField(fields, FIELD_PURPOSE, Set
            .of("text", "textarea"), true, "用车事由", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_DESTINATION, Set.of("text"), true, "目的地", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_PASSENGERS, Set.of("number"), true, "乘车人数", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_START, Set.of("datetime"), true, "用车开始", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_END, Set.of("datetime"), true, "用车结束", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_NEED_DRIVER, Set.of("radio"), true, "是否需要司机", FORM_NAME, errors);
        return errors;
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String purpose = OaBusinessFields.requireText(formData.get(FIELD_PURPOSE), "用车事由", 500);
        String destination = OaBusinessFields.requireText(formData.get(FIELD_DESTINATION), "目的地", 200);
        int passengers = OaBusinessFields.quantity(formData.get(FIELD_PASSENGERS), "乘车人数", MAX_PASSENGERS);
        LocalDateTime start = OaBusinessFields.dateTime(formData.get(FIELD_START), "用车开始", true);
        LocalDateTime end = OaBusinessFields.dateTime(formData.get(FIELD_END), "用车结束", true);
        boolean needDriver = "Y".equals(OaBusinessFields.requireText(formData.get(FIELD_NEED_DRIVER), "是否需要司机", 1));
        validateWindow(start, end, LocalDateTime.now());
        return new OaBusinessPreparation(Map
            .of(), new RequestValues(purpose, destination, passengers, needDriver, start, end));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        repository.insertRequest(tenantId, applicationId, applicantId, (RequestValues)preparation.state(), now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        RequestRow request = repository.lockRequestByApplication(tenantId, applicationId);
        if (request == null) {
            return;
        }
        if ("APPROVED".equals(status)) {
            if (STATUS_APPROVING.equals(request.status()) && !request.end().isAfter(now)) {
                throw new BusinessException("用车时段已过，请驳回该申请");
            }
            if (repository.transitionRequest(tenantId, request
                .id(), STATUS_APPROVING, STATUS_PENDING_DISPATCH, now) == 1) {
                outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long
                    .toString(applicationId), EVENT_APPROVED, "VEHICLE_APPROVED:" + request
                        .id(), applicationId, null, EVENT_APPROVED, "用车申请已通过", "您的用车申请已审批通过，等待调度派车", List.of(request
                            .applicantId()));
            }
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    public static void validateWindow(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (!end.isAfter(start)) {
            throw new BusinessException("用车结束必须晚于用车开始");
        }
        if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
            throw new BusinessException("单次用车不能超过 7 天");
        }
        if (start.isBefore(now.truncatedTo(ChronoUnit.MINUTES))) {
            throw new BusinessException("用车开始不能早于当前时间");
        }
        if (start.isAfter(now.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BusinessException("最多提前 " + MAX_ADVANCE_DAYS + " 天申请用车");
        }
    }
}
