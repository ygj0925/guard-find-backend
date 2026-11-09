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
import com.guardfind.backend.oa.repository.OaMeetingRepository;
import com.guardfind.backend.oa.repository.OaMeetingRepository.BookingRow;
import com.guardfind.backend.oa.repository.OaMeetingRepository.BookingValues;
import com.guardfind.backend.oa.repository.OaMeetingRepository.RoomRow;
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
 * 会议室预订钩子：发起时锁住会议室并校验时段冲突，审批中的预订即占用时段；
 * 审批通过前若已过开始时间则视为超时释放，必须驳回。
 */
@Component
public class OaMeetingBookingHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "MEETING_BOOKING";
    public static final String FIELD_ROOM = "roomCode";
    public static final String FIELD_SUBJECT = "subject";
    public static final String FIELD_START = "meetingStart";
    public static final String FIELD_END = "meetingEnd";
    public static final String FIELD_ATTENDEES = "attendees";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_BOOKED = "BOOKED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String EVENT_BOOKED = "MEETING_BOOKED";
    public static final Duration MAX_DURATION = Duration.ofHours(12);
    public static final int MAX_ADVANCE_DAYS = 90;

    private static final String FORM_NAME = "会议室预订表单";

    private final OaMeetingRepository repository;
    private final OaOutboxService outboxService;

    public OaMeetingBookingHook(OaMeetingRepository repository, OaOutboxService outboxService) {
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
        OaBusinessFields.requireField(fields, FIELD_ROOM, Set.of("radio"), true, "会议室", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_SUBJECT, Set
            .of("text", "textarea"), true, "会议主题", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_START, Set.of("datetime"), true, "开始时间", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_END, Set.of("datetime"), true, "结束时间", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_ATTENDEES, Set.of("number"), true, "参会人数", FORM_NAME, errors);
        return errors;
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String code = OaBusinessFields.requireText(formData.get(FIELD_ROOM), "会议室", 32);
        String subject = OaBusinessFields.requireText(formData.get(FIELD_SUBJECT), "会议主题", 200);
        LocalDateTime start = OaBusinessFields.dateTime(formData.get(FIELD_START), "开始时间", true);
        LocalDateTime end = OaBusinessFields.dateTime(formData.get(FIELD_END), "结束时间", true);
        LocalDateTime now = LocalDateTime.now();
        validateWindow(start, end, now);
        RoomRow room = repository.lockRoomByCode(tenantId, code);
        if (room == null) {
            throw new BusinessException("会议室 " + code + " 不存在");
        }
        int attendees = OaBusinessFields.quantity(formData.get(FIELD_ATTENDEES), "参会人数", 1000);
        requireBookable(room, attendees);
        requireFree(repository, tenantId, room, start, end, now);
        return new OaBusinessPreparation(Map.of(), new Plan(room.id(), subject, start, end, attendees));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        Plan plan = (Plan)preparation.state();
        repository.insertBooking(tenantId, new BookingValues(plan.roomId(), applicationId, null, applicantId, plan
            .subject(), plan.start(), plan.end(), plan.attendees(), STATUS_APPROVING), now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        BookingRow booking = repository.lockBookingByApplication(tenantId, applicationId);
        if (booking == null) {
            return;
        }
        if ("APPROVED".equals(status)) {
            if (STATUS_EXPIRED.equals(booking.status()) || STATUS_APPROVING.equals(booking.status()) && !booking.start()
                .isAfter(now)) {
                throw new BusinessException("会议已过开始时间，预订已超时释放，请驳回该申请");
            }
            if (repository.transitionBooking(tenantId, booking.id(), STATUS_APPROVING, STATUS_BOOKED, now) == 1) {
                outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long
                    .toString(applicationId), EVENT_BOOKED, "MEETING_BOOKED:" + booking
                        .id(), applicationId, null, EVENT_BOOKED, "会议室预订成功", "「" + booking.subject() + "」已预订成功", List
                            .of(booking.bookerId()));
            }
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionBooking(tenantId, booking.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    public static void validateWindow(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
            throw new BusinessException("单次预订不能超过 12 小时");
        }
        if (start.isBefore(now.truncatedTo(ChronoUnit.MINUTES))) {
            throw new BusinessException("开始时间不能早于当前时间");
        }
        if (start.isAfter(now.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BusinessException("最多提前 " + MAX_ADVANCE_DAYS + " 天预订");
        }
    }

    public static void requireBookable(RoomRow room, int attendees) {
        if (!"ENABLED".equals(room.status())) {
            throw new BusinessException("会议室「" + room.name() + "」已停用");
        }
        if (attendees > room.capacity()) {
            throw new BusinessException("参会人数超过会议室「" + room.name() + "」容量 " + room.capacity() + " 人");
        }
    }

    /**
     * 调用前必须已锁住会议室，保证同一会议室的冲突判定串行。
     */
    public static void requireFree(OaMeetingRepository repository,
                                   long tenantId,
                                   RoomRow room,
                                   LocalDateTime start,
                                   LocalDateTime end,
                                   LocalDateTime now) {
        if (repository.countOverlaps(tenantId, room.id(), start, end, now) > 0) {
            throw new BusinessException("会议室「" + room.name() + "」该时段已被预订");
        }
    }

    record Plan(long roomId, String subject, LocalDateTime start, LocalDateTime end, int attendees) {
    }
}
