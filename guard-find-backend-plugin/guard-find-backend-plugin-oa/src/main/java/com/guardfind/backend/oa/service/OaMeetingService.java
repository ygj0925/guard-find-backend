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
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaMeetingBookReq;
import com.guardfind.backend.oa.model.OaMeetingBookingResp;
import com.guardfind.backend.oa.model.OaMeetingRoomReq;
import com.guardfind.backend.oa.model.OaMeetingRoomResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaResourceCancelReq;
import com.guardfind.backend.oa.repository.OaMeetingRepository;
import com.guardfind.backend.oa.repository.OaMeetingRepository.BookingFilter;
import com.guardfind.backend.oa.repository.OaMeetingRepository.BookingRow;
import com.guardfind.backend.oa.repository.OaMeetingRepository.BookingValues;
import com.guardfind.backend.oa.repository.OaMeetingRepository.ExpiringBooking;
import com.guardfind.backend.oa.repository.OaMeetingRepository.RoomRow;
import com.guardfind.backend.oa.repository.OaMeetingRepository.RoomValues;
import com.guardfind.backend.oa.resource.OaMeetingBookingHook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.guardfind.backend.oa.service.OaContractService.withOptions;
import static com.guardfind.backend.oa.service.OaPurchaseService.blankToNull;
import static com.guardfind.backend.oa.service.OaPurchaseService.field;
import static com.guardfind.backend.oa.service.OaPurchaseService.offset;
import static com.guardfind.backend.oa.service.OaPurchaseService.option;
import static com.guardfind.backend.oa.service.OaPurchaseService.safeSize;

/**
 * 会议室：档案维护、免审批会议室直接预订、需审批会议室走 OA 申请；同一会议室的冲突判定在锁住会议室后串行执行。
 */
@Service
public class OaMeetingService {

    public static final String FORM_KEY = "meeting_booking";
    public static final String MODEL_KEY = "meeting_pilot";
    public static final String AGGREGATE_BOOKING = "MEETING_BOOKING";
    public static final String EVENT_CANCELLED = "MEETING_CANCELLED";
    public static final String EVENT_EXPIRED = "MEETING_EXPIRED";
    private static final int EXPIRY_SCAN_LIMIT = 2000;
    private static final Set<String> ROOM_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> BOOKING_STATUSES = Set
        .of(OaMeetingBookingHook.STATUS_APPROVING, OaMeetingBookingHook.STATUS_BOOKED, OaMeetingBookingHook.STATUS_CANCELLED, OaMeetingBookingHook.STATUS_CLOSED, OaMeetingBookingHook.STATUS_EXPIRED);

    private final OaMeetingRepository repository;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaMeetingService(OaMeetingRepository repository,
                            OaFormService formService,
                            OaProcessModelService modelService,
                            OaOutboxService outboxService,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.formService = formService;
        this.modelService = modelService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaMeetingRoomResp> rooms(long tenantId, String status) {
        return repository.rooms(tenantId, checked(status, ROOM_STATUSES, "会议室"));
    }

    public OaMeetingRoomResp createRoom(long tenantId, long userId, OaMeetingRoomReq req) {
        try {
            return repository.room(tenantId, repository.insertRoom(tenantId, values(req), userId, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("会议室编码已存在");
        }
    }

    public OaMeetingRoomResp updateRoom(long tenantId, long userId, long id, OaMeetingRoomReq req) {
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        if (repository.room(tenantId, id) == null) {
            throw new BusinessException("会议室不存在");
        }
        try {
            if (repository.updateRoom(tenantId, id, req.version(), values(req), userId, LocalDateTime.now()) != 1) {
                throw new BusinessException("会议室已被修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("会议室编码已存在");
        }
        return repository.room(tenantId, id);
    }

    public List<OaMeetingBookingResp> bookings(long tenantId,
                                               String status,
                                               Long roomId,
                                               Long bookerId,
                                               LocalDate date,
                                               int page,
                                               int size) {
        int limit = safeSize(size);
        return repository.bookings(tenantId, filter(status, roomId, bookerId, date), offset(page, limit), limit);
    }

    public long countBookings(long tenantId, String status, Long roomId, Long bookerId, LocalDate date) {
        return repository.countBookings(tenantId, filter(status, roomId, bookerId, date));
    }

    /**
     * 某天所有启用会议室的占用时段（已预订与仍在有效期内的审批中预订），供预订前查看空闲。
     */
    public List<OaMeetingBookingResp> schedule(long tenantId, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        return repository.schedule(tenantId, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), LocalDateTime.now());
    }

    public OaMeetingBookingResp book(long tenantId, long userId, OaMeetingBookReq req) {
        String requestId = req.requestId().trim();
        LocalDateTime start = req.startTime().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime end = req.endTime().truncatedTo(ChronoUnit.MINUTES);
        Long id = transactionTemplate.execute(status -> {
            RoomRow room = repository.lockRoom(tenantId, req.roomId());
            if (room == null) {
                throw new BusinessException("会议室不存在");
            }
            BookingRow replayed = repository.bookingByRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed.bookerId() != userId || replayed.roomId() != room.id() || !replayed.start()
                    .equals(start) || !replayed.end().equals(end)) {
                    throw new BusinessException("请求号已被使用");
                }
                return replayed.id();
            }
            if (room.needApproval()) {
                throw new BusinessException("该会议室需审批，请通过 OA 申请预订");
            }
            LocalDateTime now = LocalDateTime.now();
            OaMeetingBookingHook.validateWindow(start, end, now);
            OaMeetingBookingHook.requireBookable(room, req.attendees());
            OaMeetingBookingHook.requireFree(repository, tenantId, room, start, end, now);
            try {
                return repository.insertBooking(tenantId, new BookingValues(room.id(), null, requestId, userId, req
                    .subject()
                    .trim(), start, end, req.attendees(), OaMeetingBookingHook.STATUS_BOOKED), now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
        });
        return repository.booking(tenantId, id);
    }

    /**
     * 预订人取消本人已生效的预订；审批中的预订须撤回 OA 申请。
     */
    public OaMeetingBookingResp cancelMine(long tenantId, long userId, long id, OaResourceCancelReq req) {
        transactionTemplate.executeWithoutResult(status -> {
            BookingRow booking = repository.lockBooking(tenantId, id);
            if (booking == null || booking.bookerId() != userId) {
                throw new BusinessException("预订不存在或无权操作");
            }
            if (OaMeetingBookingHook.STATUS_CANCELLED.equals(booking.status())) {
                return;
            }
            if (OaMeetingBookingHook.STATUS_APPROVING.equals(booking.status())) {
                throw new BusinessException("审批中的预订请撤回申请");
            }
            cancelBooked(tenantId, booking, userId, blankToNull(req == null ? null : req.reason()));
        });
        return repository.booking(tenantId, id);
    }

    /**
     * 行政取消已生效的预订（如会议室临时检修），必须填写原因并通知预订人。
     */
    public OaMeetingBookingResp cancelByAdmin(long tenantId, long operatorId, long id, OaResourceCancelReq req) {
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException("请填写取消原因");
        }
        transactionTemplate.executeWithoutResult(status -> {
            BookingRow booking = repository.lockBooking(tenantId, id);
            if (booking == null) {
                throw new BusinessException("预订不存在");
            }
            if (OaMeetingBookingHook.STATUS_CANCELLED.equals(booking.status())) {
                return;
            }
            if (OaMeetingBookingHook.STATUS_APPROVING.equals(booking.status())) {
                throw new BusinessException("审批中的预订请在审批中驳回");
            }
            cancelBooked(tenantId, booking, operatorId, reason);
            if (booking.bookerId() != operatorId) {
                outboxService.recordNotification(tenantId, AGGREGATE_BOOKING, Long.toString(booking
                    .id()), EVENT_CANCELLED, "MEETING_CANCELLED:" + booking.id(), booking
                        .applicationId(), null, EVENT_CANCELLED, "会议室预订已取消", "「" + booking
                            .subject() + "」的会议室预订已被取消，原因：" + reason, List.of(booking.bookerId()));
            }
        });
        return repository.booking(tenantId, id);
    }

    /**
     * 审批中的预订到开始时间仍未通过即超时释放时段；之后审批通过会被钩子拒绝。
     */
    public int expireApprovals(LocalDateTime now) {
        int expired = 0;
        for (ExpiringBooking booking : repository.expiredApprovals(now, EXPIRY_SCAN_LIMIT)) {
            Boolean done = transactionTemplate.execute(status -> {
                if (repository.transitionBooking(booking.tenantId(), booking
                    .id(), OaMeetingBookingHook.STATUS_APPROVING, OaMeetingBookingHook.STATUS_EXPIRED, now) != 1) {
                    return false;
                }
                outboxService.recordNotification(booking.tenantId(), AGGREGATE_BOOKING, Long.toString(booking
                    .id()), EVENT_EXPIRED, "MEETING_EXPIRED:" + booking.id(), booking
                        .applicationId(), null, EVENT_EXPIRED, "会议室预订已超时释放", "「" + booking
                            .subject() + "」在会议开始前未完成审批，会议室「" + booking.roomName() + "」的时段已释放", List.of(booking
                                .bookerId()));
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    public OaContractTemplateResp template(long tenantId, long userId) {
        List<OaMeetingRoomResp> rooms = repository.rooms(tenantId, "ENABLED")
            .stream()
            .filter(OaMeetingRoomResp::needApproval)
            .toList();
        if (rooms.isEmpty()) {
            throw new BusinessException("没有需审批的启用会议室，请先在会议室档案中设置");
        }
        return transactionTemplate.execute(status -> {
            long formId = publishForm(tenantId, userId, "会议室预订", schema(rooms));
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(MODEL_KEY, "会议室预订", "resource", "需审批会议室的预订：直属上级审批；审批期间占用时段，会议开始前未通过则自动释放", BPMN, OaMeetingBookingHook.BUSINESS_TYPE));
            return new OaContractTemplateResp(formId, model.id(), model.modelKey());
        });
    }

    public String schema(List<OaMeetingRoomResp> rooms) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (OaMeetingRoomResp room : rooms.subList(0, Math.min(rooms.size(), OaFormSchemaValidator.MAX_OPTIONS))) {
            options.add(option(room.name() + "（" + room.capacity() + " 人）", room.code()));
        }
        try {
            return objectMapper.writeValueAsString(Map.of("fields", List
                .of(withOptions(field(OaMeetingBookingHook.FIELD_ROOM, "会议室", "radio", true), options), field(OaMeetingBookingHook.FIELD_SUBJECT, "会议主题", "text", true), field(OaMeetingBookingHook.FIELD_START, "开始时间", "datetime", true), field(OaMeetingBookingHook.FIELD_END, "结束时间", "datetime", true), field(OaMeetingBookingHook.FIELD_ATTENDEES, "参会人数", "number", true))));
        } catch (Exception e) {
            throw new IllegalStateException("模板 schema 序列化失败", e);
        }
    }

    private void cancelBooked(long tenantId, BookingRow booking, long operatorId, String reason) {
        if (!OaMeetingBookingHook.STATUS_BOOKED.equals(booking.status())) {
            throw new BusinessException("预订当前状态不能取消");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!booking.end().isAfter(now)) {
            throw new BusinessException("会议已结束，不能取消");
        }
        if (repository.cancelBooking(tenantId, booking
            .id(), OaMeetingBookingHook.STATUS_BOOKED, operatorId, reason, now) != 1) {
            throw new BusinessException("预订状态已变化，请刷新后重试");
        }
    }

    private long publishForm(long tenantId, long userId, String name, String schema) {
        OaFormResp form = formService
            .create(tenantId, userId, new OaFormCreateReq(FORM_KEY, name, name + "试点模板，业务字段 key 不可修改", schema));
        OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
        if (!published.valid()) {
            throw new BusinessException(name + "表单发布失败：" + String.join("；", published.errors()));
        }
        return form.id();
    }

    private static RoomValues values(OaMeetingRoomReq req) {
        return new RoomValues(req.code().trim(), req.name().trim(), blankToNull(req.location()), req
            .capacity(), Boolean.TRUE.equals(req.needApproval()), req.status() == null
                ? "ENABLED"
                : req.status(), blankToNull(req.remark()));
    }

    private static BookingFilter filter(String status, Long roomId, Long bookerId, LocalDate date) {
        return new BookingFilter(checked(status, BOOKING_STATUSES, "预订"), roomId, bookerId, date == null
            ? null
            : date.atStartOfDay(), date == null ? null : date.plusDays(1).atStartOfDay());
    }

    static String checked(String status, Set<String> allowed, String label) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!allowed.contains(status)) {
            throw new BusinessException(label + "状态无效: " + status);
        }
        return status;
    }

    public static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          id="Definitions_meeting" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="meeting_pilot" name="会议室预订" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToEnd</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowToEnd</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToEnd" sourceRef="supervisorApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_meeting">
            <bpmndi:BPMNPlane id="BPMNPlane_meeting" bpmnElement="meeting_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="392" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToEnd_di" bpmnElement="flowToEnd">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="392" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;
}
