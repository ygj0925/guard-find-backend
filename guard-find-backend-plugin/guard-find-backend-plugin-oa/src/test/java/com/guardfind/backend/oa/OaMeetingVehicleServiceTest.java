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

package com.guardfind.backend.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.business.OaBusinessHooks;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaDriverReq;
import com.guardfind.backend.oa.model.OaDriverResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaMeetingBookReq;
import com.guardfind.backend.oa.model.OaMeetingBookingResp;
import com.guardfind.backend.oa.model.OaMeetingRoomReq;
import com.guardfind.backend.oa.model.OaMeetingRoomResp;
import com.guardfind.backend.oa.model.OaResourceCancelReq;
import com.guardfind.backend.oa.model.OaVehicleCandidatesResp;
import com.guardfind.backend.oa.model.OaVehicleDepartReq;
import com.guardfind.backend.oa.model.OaVehicleDispatchReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceCompleteReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceResp;
import com.guardfind.backend.oa.model.OaVehicleReq;
import com.guardfind.backend.oa.model.OaVehicleRequestResp;
import com.guardfind.backend.oa.model.OaVehicleResp;
import com.guardfind.backend.oa.model.OaVehicleReturnReq;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaMeetingRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.repository.OaVehicleRepository;
import com.guardfind.backend.oa.resource.OaMeetingBookingHook;
import com.guardfind.backend.oa.resource.OaVehicleRequestHook;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaMeetingService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.service.OaVehicleService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.starter.core.exception.BusinessException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 15.1 会议室与车辆：锁住资源后判定区间冲突，审批中的预订占用时段并超时释放，派车/出车/归还按请求号幂等。
 */
class OaMeetingVehicleServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long DRIVER_USER = 103;
    private static final long ADMIN = 104;
    private static final long MEETING_RELEASE = 51;
    private static final long VEHICLE_RELEASE = 52;
    private static final DateTimeFormatter FORM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final LocalDateTime DAY = LocalDate.now().plusDays(2).atStartOfDay();

    @Test
    void templatesPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture.meetingService.template(TENANT, ADMIN))
                .hasMessageContaining("没有需审批的启用会议室");
            fixture.room("A101", 10, false);
            fixture.room("B201", 20, true);
            List<OaMeetingRoomResp> approvalRooms = fixture.meetingService.rooms(TENANT, "ENABLED")
                .stream()
                .filter(OaMeetingRoomResp::needApproval)
                .toList();
            String meetingSchema = fixture.meetingService.schema(approvalRooms);
            String vehicleSchema = fixture.vehicleService.schema();
            for (String schema : List.of(meetingSchema, vehicleSchema)) {
                assertThat(new OaFormSchemaValidator().validate(schema, true).valid()).isTrue();
            }
            assertThat(meetingSchema).contains("B201").doesNotContain("A101");
            assertThat(fixture.hooks.validateForm(OaMeetingBookingHook.BUSINESS_TYPE, meetingSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaVehicleRequestHook.BUSINESS_TYPE, vehicleSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaMeetingBookingHook.BUSINESS_TYPE, vehicleSchema)).isNotEmpty();
            assertThat(fixture.hooks.validateForm(OaVehicleRequestHook.BUSINESS_TYPE, meetingSchema)).isNotEmpty();

            for (String bpmn : List.of(OaMeetingService.BPMN, OaVehicleService.BPMN)) {
                OaBpmnValidationResult result = new OaBpmnValidator().validate(bpmn.getBytes(StandardCharsets.UTF_8));
                assertThat(result.errors()).isEmpty();
                assertThat(result.assigneeVariables()).containsExactly("supervisor");
            }
            assertThat(new OaBpmnValidator().validate(OaMeetingService.BPMN.getBytes(StandardCharsets.UTF_8))
                .processKey()).isEqualTo(OaMeetingService.MODEL_KEY);
            assertThat(new OaBpmnValidator().validate(OaVehicleService.BPMN.getBytes(StandardCharsets.UTF_8))
                .processKey()).isEqualTo(OaVehicleService.MODEL_KEY);
        });
    }

    @Test
    void directBookingConflictsCancelAndIdempotency() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaMeetingRoomResp room = fixture.room("A101", 10, false);
            OaMeetingRoomResp approvalRoom = fixture.room("B201", 20, true);
            assertThatThrownBy(() -> fixture.room("A101", 8, false)).hasMessageContaining("会议室编码已存在");
            assertThatThrownBy(() -> fixture.meetingService.updateRoom(TENANT, ADMIN, room
                .id(), roomReq("A101", 12, false, "ENABLED", null))).hasMessageContaining("缺少版本号");
            assertThatThrownBy(() -> fixture.meetingService.updateRoom(TENANT, ADMIN, room
                .id(), roomReq("A101", 12, false, "ENABLED", 9))).hasMessageContaining("会议室已被修改");

            OaMeetingBookingResp first = fixture.book(APPLICANT, "b1", room.id(), at(9, 0), at(10, 0), 5);
            assertThat(first.status()).isEqualTo(OaMeetingBookingHook.STATUS_BOOKED);
            assertThat(first.bookerName()).isEqualTo("Alice");
            assertThat(fixture.book(APPLICANT, "b1", room.id(), at(9, 0), at(10, 0), 5).id()).isEqualTo(first.id());
            assertThatThrownBy(() -> fixture.book(SUPERVISOR, "b1", room.id(), at(13, 0), at(14, 0), 5))
                .hasMessageContaining("请求号已被使用");
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b1", room.id(), at(13, 0), at(14, 0), 5))
                .hasMessageContaining("请求号已被使用");
            assertThatThrownBy(() -> fixture.book(SUPERVISOR, "b2", room.id(), at(9, 30), at(10, 30), 5))
                .hasMessageContaining("该时段已被预订");
            assertThatThrownBy(() -> fixture.book(SUPERVISOR, "b2", room.id(), at(8, 0), at(11, 0), 5))
                .hasMessageContaining("该时段已被预订");
            OaMeetingBookingResp adjacent = fixture.book(SUPERVISOR, "b2", room.id(), at(10, 0), at(11, 0), 5);
            assertThat(adjacent.status()).isEqualTo(OaMeetingBookingHook.STATUS_BOOKED);

            assertThatThrownBy(() -> fixture.book(APPLICANT, "b3", room.id(), at(14, 0), at(15, 0), 11))
                .hasMessageContaining("超过会议室");
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b3", approvalRoom.id(), at(14, 0), at(15, 0), 5))
                .hasMessageContaining("该会议室需审批");
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b3", room.id(), at(15, 0), at(15, 0), 5))
                .hasMessageContaining("结束时间必须晚于开始时间");
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b3", room.id(), at(6, 0), at(19, 0), 5))
                .hasMessageContaining("不能超过 12 小时");
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b3", room.id(), LocalDateTime.now()
                .minusHours(2), LocalDateTime.now().minusHours(1), 5)).hasMessageContaining("开始时间不能早于当前时间");
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b3", 9999L, at(14, 0), at(15, 0), 5))
                .hasMessageContaining("会议室不存在");

            assertThat(fixture.meetingService.schedule(TENANT, DAY.toLocalDate())).extracting(OaMeetingBookingResp::id)
                .containsExactlyInAnyOrder(first.id(), adjacent.id());
            assertThat(fixture.meetingService.schedule(TENANT, DAY.toLocalDate().plusDays(1))).isEmpty();

            assertThatThrownBy(() -> fixture.meetingService.cancelMine(TENANT, SUPERVISOR, first
                .id(), new OaResourceCancelReq(null))).hasMessageContaining("预订不存在或无权操作");
            OaMeetingBookingResp cancelled = fixture.meetingService.cancelMine(TENANT, APPLICANT, first
                .id(), new OaResourceCancelReq("改期"));
            assertThat(cancelled.status()).isEqualTo(OaMeetingBookingHook.STATUS_CANCELLED);
            assertThat(cancelled.cancelReason()).isEqualTo("改期");
            assertThat(fixture.meetingService.cancelMine(TENANT, APPLICANT, first.id(), null).status())
                .isEqualTo(OaMeetingBookingHook.STATUS_CANCELLED);
            assertThat(fixture.outbox(OaMeetingService.EVENT_CANCELLED)).isZero();
            OaMeetingBookingResp rebooked = fixture.book(ADMIN, "b4", room.id(), at(9, 0), at(10, 0), 3);
            assertThat(rebooked.status()).isEqualTo(OaMeetingBookingHook.STATUS_BOOKED);

            assertThatThrownBy(() -> fixture.meetingService.cancelByAdmin(TENANT, ADMIN, adjacent
                .id(), new OaResourceCancelReq(" "))).hasMessageContaining("请填写取消原因");
            assertThat(fixture.meetingService.cancelByAdmin(TENANT, ADMIN, adjacent.id(), new OaResourceCancelReq("检修"))
                .cancelByName()).isEqualTo("Dave");
            fixture.meetingService.cancelByAdmin(TENANT, ADMIN, adjacent.id(), new OaResourceCancelReq("检修"));
            assertThat(fixture.outbox(OaMeetingService.EVENT_CANCELLED)).isEqualTo(1);

            OaMeetingRoomResp disabled = fixture.meetingService.updateRoom(TENANT, ADMIN, room
                .id(), roomReq("A101", 10, false, "DISABLED", room.version()));
            assertThat(disabled.version()).isEqualTo(room.version() + 1);
            assertThatThrownBy(() -> fixture.book(APPLICANT, "b5", room.id(), at(16, 0), at(17, 0), 5))
                .hasMessageContaining("已停用");

            assertThat(fixture.meetingService.countBookings(TENANT, "BOOKED", null, null, null)).isEqualTo(1);
            assertThat(fixture.meetingService.countBookings(TENANT, null, null, APPLICANT, null)).isEqualTo(1);
            assertThat(fixture.meetingService.bookings(TENANT, null, room.id(), null, DAY.toLocalDate(), 1, 10))
                .hasSize(3);
            assertThat(fixture.meetingService.countBookings(102, null, null, null, null)).isZero();
            assertThat(fixture.meetingService.rooms(102, null)).isEmpty();
            assertThatThrownBy(() -> fixture.meetingService.bookings(TENANT, "BOGUS", null, null, null, 1, 10))
                .hasMessageContaining("预订状态无效");
        });
    }

    @Test
    void concurrentDirectBookingsOnlyOneWins() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaMeetingRoomResp room = fixture.room("A101", 10, false);
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    long userId = 101 + i;
                    String requestId = "race-" + i;
                    LocalDateTime start = at(9, i * 10);
                    Callable<String> task = () -> {
                        ready.countDown();
                        go.await();
                        try {
                            fixture.book(userId, requestId, room.id(), start, start.plusHours(1), 4);
                            return "OK";
                        } catch (BusinessException e) {
                            return e.getMessage();
                        }
                    };
                    results.add(pool.submit(task));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                List<String> outcomes = new ArrayList<>();
                for (Future<String> result : results) {
                    outcomes.add(result.get(30, TimeUnit.SECONDS));
                }
                assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
                assertThat(outcomes).filteredOn(outcome -> !"OK".equals(outcome))
                    .hasSize(threads - 1)
                    .allSatisfy(outcome -> assertThat(outcome).contains("该时段已被预订"));
            } finally {
                pool.shutdownNow();
            }
            assertThat(fixture.meetingService.countBookings(TENANT, "BOOKED", room.id(), null, null)).isEqualTo(1);
        });
    }

    @Test
    void approvalBookingOccupiesSlotAndExpires() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaMeetingRoomResp room = fixture.room("B201", 20, true);
            fixture.seedMeetingRelease();

            assertThatThrownBy(() -> fixture.submitMeeting("m0", "NOPE", at(9, 0), at(10, 0), 5))
                .hasMessageContaining("请选择有效选项");
            assertThatThrownBy(() -> fixture.submitMeeting("m0", "B201", at(9, 0), at(10, 0), 30))
                .hasMessageContaining("超过会议室");
            long approved = fixture.submitMeeting("m1", "B201", at(9, 0), at(10, 0), 5);
            OaMeetingBookingResp approving = fixture.bookingOf(approved);
            assertThat(approving.status()).isEqualTo(OaMeetingBookingHook.STATUS_APPROVING);
            assertThat(approving.roomId()).isEqualTo(room.id());
            assertThatThrownBy(() -> fixture.submitMeeting("m2", "B201", at(9, 30), at(11, 0), 5))
                .hasMessageContaining("该时段已被预订");
            assertThat(fixture.meetingService.schedule(TENANT, DAY.toLocalDate())).hasSize(1);
            assertThatThrownBy(() -> fixture.meetingService.cancelMine(TENANT, APPLICANT, approving.id(), null))
                .hasMessageContaining("审批中的预订请撤回申请");
            assertThatThrownBy(() -> fixture.meetingService.cancelByAdmin(TENANT, ADMIN, approving
                .id(), new OaResourceCancelReq("检修"))).hasMessageContaining("审批中的预订请在审批中驳回");

            fixture.approve(SUPERVISOR, approved, "a1");
            assertThat(fixture.applicationStatus(approved)).isEqualTo("APPROVED");
            assertThat(fixture.bookingOf(approved).status()).isEqualTo(OaMeetingBookingHook.STATUS_BOOKED);
            assertThat(fixture.outbox(OaMeetingBookingHook.EVENT_BOOKED)).isEqualTo(1);

            long rejected = fixture.submitMeeting("m3", "B201", at(13, 0), at(14, 0), 5);
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(rejected)
                .getId(), new OaApprovalActionReq("r1", "时间冲突"));
            assertThat(fixture.bookingOf(rejected).status()).isEqualTo(OaMeetingBookingHook.STATUS_CLOSED);
            long withdrawn = fixture.submitMeeting("m4", "B201", at(13, 0), at(14, 0), 5);
            fixture.approvalService.withdraw(TENANT, APPLICANT, withdrawn, new OaApprovalActionReq("w1", null));
            assertThat(fixture.bookingOf(withdrawn).status()).isEqualTo(OaMeetingBookingHook.STATUS_CLOSED);

            long expiring = fixture.submitMeeting("m5", "B201", at(13, 0), at(14, 0), 5);
            long expiringId = fixture.bookingOf(expiring).id();
            assertThat(fixture.meetingService.expireApprovals(LocalDateTime.now())).isZero();
            assertThat(fixture.meetingService.expireApprovals(at(13, 0))).isEqualTo(1);
            assertThat(fixture.meetingService.expireApprovals(at(13, 0))).isZero();
            assertThat(fixture.bookingOf(expiring).status()).isEqualTo(OaMeetingBookingHook.STATUS_EXPIRED);
            assertThat(fixture.outbox(OaMeetingService.EVENT_EXPIRED)).isEqualTo(1);
            assertThatThrownBy(() -> fixture.approve(SUPERVISOR, expiring, "a2")).hasMessageContaining("预订已超时释放");
            assertThat(fixture.bookingOf(expiring).status()).isEqualTo(OaMeetingBookingHook.STATUS_EXPIRED);
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(expiring)
                .getId(), new OaApprovalActionReq("r2", "已过期"));
            assertThat(fixture.bookingOf(expiring).status()).isEqualTo(OaMeetingBookingHook.STATUS_EXPIRED);
            assertThat(fixture.meetingService.bookings(TENANT, "EXPIRED", null, null, null, 1, 10))
                .extracting(OaMeetingBookingResp::id)
                .containsExactly(expiringId);

            long replacement = fixture.submitMeeting("m6", "B201", at(13, 0), at(14, 0), 5);
            assertThat(fixture.bookingOf(replacement).status()).isEqualTo(OaMeetingBookingHook.STATUS_APPROVING);
        });
    }

    @Test
    void vehicleDispatchDepartReturnAndConflicts() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaVehicleResp sedan = fixture.vehicle("沪A10001", 5, 1000);
            OaVehicleResp van = fixture.vehicle("沪A10002", 7, 2000);
            assertThatThrownBy(() -> fixture.vehicle("沪A10001", 5, 0)).hasMessageContaining("车牌号已存在");
            OaDriverResp driver = fixture.vehicleService
                .createDriver(TENANT, ADMIN, new OaDriverReq("老王", "13800000000", DRIVER_USER, "ENABLED", null, null));
            assertThat(driver.userName()).isEqualTo("Carol");
            assertThatThrownBy(() -> fixture.vehicleService
                .createDriver(TENANT, ADMIN, new OaDriverReq("老王2", null, DRIVER_USER, "ENABLED", null, null)))
                .hasMessageContaining("该员工已登记为司机");
            assertThatThrownBy(() -> fixture.vehicleService
                .createDriver(TENANT, ADMIN, new OaDriverReq("外人", null, 999L, "ENABLED", null, null)))
                .hasMessageContaining("关联账号不是在职员工");
            OaDriverResp spare = fixture.vehicleService
                .createDriver(TENANT, ADMIN, new OaDriverReq("小李", null, null, "ENABLED", null, null));
            fixture.seedVehicleRelease();

            assertThatThrownBy(() -> fixture.submitVehicle("v0", 51, "Y", at(9, 0), at(12, 0)))
                .hasMessageContaining("乘车人数");
            long app1 = fixture.submitVehicle("v1", 4, "Y", at(9, 0), at(12, 0));
            long r1 = fixture.requestOf(app1);
            assertThat(fixture.vehicleService.request(TENANT, APPLICANT, r1).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_APPROVING);
            assertThatThrownBy(() -> fixture.vehicleService.candidates(TENANT, r1)).hasMessageContaining("只有待调度的申请");
            assertThatThrownBy(() -> fixture.dispatch(r1, "d0", sedan.id(), driver.id()))
                .hasMessageContaining("用车申请当前状态为审批中");
            fixture.approve(SUPERVISOR, app1, "a1");
            assertThat(fixture.vehicleService.request(TENANT, null, r1).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_PENDING_DISPATCH);
            assertThat(fixture.outbox(OaVehicleRequestHook.EVENT_APPROVED)).isEqualTo(1);

            OaVehicleCandidatesResp candidates = fixture.vehicleService.candidates(TENANT, r1);
            assertThat(candidates.vehicles()).extracting(OaVehicleResp::id).containsExactly(sedan.id(), van.id());
            assertThat(candidates.drivers()).extracting(OaDriverResp::id).containsExactly(driver.id(), spare.id());
            assertThatThrownBy(() -> fixture.dispatch(r1, "d1", sedan.id(), null)).hasMessageContaining("该申请需要司机");

            OaVehicleMaintenanceResp maintenance = fixture.vehicleService
                .createMaintenance(TENANT, ADMIN, new OaVehicleMaintenanceReq("mt1", van
                    .id(), "REPAIR", at(8, 0), at(18, 0), "换轮胎"));
            assertThat(maintenance.status()).isEqualTo("ACTIVE");
            assertThat(fixture.vehicleService.createMaintenance(TENANT, ADMIN, new OaVehicleMaintenanceReq("mt1", van
                .id(), "REPAIR", at(8, 0), at(18, 0), null)).id()).isEqualTo(maintenance.id());
            assertThatThrownBy(() -> fixture.vehicleService
                .createMaintenance(TENANT, ADMIN, new OaVehicleMaintenanceReq("mt1", van
                    .id(), "REPAIR", at(19, 0), at(20, 0), null))).hasMessageContaining("请求号已被使用");
            assertThatThrownBy(() -> fixture.vehicleService
                .createMaintenance(TENANT, ADMIN, new OaVehicleMaintenanceReq("mt2", van
                    .id(), "INSPECTION", at(17, 0), at(19, 0), null))).hasMessageContaining("已有维保登记");
            assertThat(fixture.vehicleService.candidates(TENANT, r1).vehicles()).extracting(OaVehicleResp::id)
                .containsExactly(sedan.id());
            assertThatThrownBy(() -> fixture.dispatch(r1, "d1", van.id(), driver.id())).hasMessageContaining("该时段在维保中");

            OaVehicleRequestResp dispatched = fixture.dispatch(r1, "d1", sedan.id(), driver.id());
            assertThat(dispatched.status()).isEqualTo(OaVehicleRequestHook.STATUS_DISPATCHED);
            assertThat(dispatched.plateNo()).isEqualTo("沪A10001");
            assertThat(dispatched.driverName()).isEqualTo("老王");
            assertThat(fixture.dispatch(r1, "d1", sedan.id(), driver.id()).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_DISPATCHED);
            assertThat(fixture.outboxRecipients(OaVehicleService.EVENT_DISPATCHED)).contains("101", "103");
            assertThatThrownBy(() -> fixture.vehicleService
                .createMaintenance(TENANT, ADMIN, new OaVehicleMaintenanceReq("mt3", sedan
                    .id(), "MAINTENANCE", at(11, 0), at(13, 0), null))).hasMessageContaining("已有调度行程");

            long app2 = fixture.submitVehicle("v2", 2, "N", at(10, 0), at(13, 0));
            long r2 = fixture.requestOf(app2);
            assertThatThrownBy(() -> fixture.dispatch(r2, "d1", sedan.id(), null)).hasMessageContaining("请求号已被使用");
            fixture.approve(SUPERVISOR, app2, "a2");
            OaVehicleCandidatesResp busy = fixture.vehicleService.candidates(TENANT, r2);
            assertThat(busy.vehicles()).isEmpty();
            assertThat(busy.drivers()).extracting(OaDriverResp::id).containsExactly(spare.id());
            assertThatThrownBy(() -> fixture.dispatch(r2, "d2", sedan.id(), null)).hasMessageContaining("该时段已被调度");

            OaVehicleMaintenanceResp completed = fixture.vehicleService.completeMaintenance(TENANT, ADMIN, maintenance
                .id(), new OaVehicleMaintenanceCompleteReq(new BigDecimal("320.50"), "提前完工"));
            assertThat(completed.status()).isEqualTo("DONE");
            assertThat(completed.endTime()).isEqualTo(completed.startTime());
            assertThat(completed.cost()).isEqualByComparingTo("320.50");
            assertThat(fixture.vehicleService.completeMaintenance(TENANT, ADMIN, maintenance.id(), null).cost())
                .isEqualByComparingTo("320.50");
            assertThat(fixture.vehicleService.candidates(TENANT, r2).vehicles()).extracting(OaVehicleResp::id)
                .containsExactly(van.id());
            assertThatThrownBy(() -> fixture.dispatch(r2, "d2", van.id(), driver.id()))
                .hasMessageContaining("司机「老王」该时段已有出车任务");
            assertThat(fixture.dispatch(r2, "d2", van.id(), null).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_DISPATCHED);

            long app3 = fixture.submitVehicle("v3", 1, "N", at(14, 0), at(15, 0));
            long r3 = fixture.requestOf(app3);
            fixture.approve(SUPERVISOR, app3, "a3");
            fixture.dispatch(r3, "d3", sedan.id(), null);

            assertThatThrownBy(() -> fixture.vehicleService
                .depart(TENANT, ADMIN, r1, new OaVehicleDepartReq("dp1", 900))).hasMessageContaining("不能小于车辆当前里程 1000");
            OaVehicleRequestResp inUse = fixture.vehicleService
                .depart(TENANT, ADMIN, r1, new OaVehicleDepartReq("dp1", 1000));
            assertThat(inUse.status()).isEqualTo(OaVehicleRequestHook.STATUS_IN_USE);
            assertThat(inUse.startMileage()).isEqualTo(1000);
            assertThat(fixture.vehicleService.depart(TENANT, ADMIN, r1, new OaVehicleDepartReq("dp1", 1000)).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_IN_USE);
            assertThatThrownBy(() -> fixture.vehicleService
                .depart(TENANT, ADMIN, r3, new OaVehicleDepartReq("dp3", 1000))).hasMessageContaining("仍在出车中");
            assertThatThrownBy(() -> fixture.vehicleService.cancelMine(TENANT, APPLICANT, r1, null))
                .hasMessageContaining("用车申请当前状态为出车中，不能取消");

            assertThatThrownBy(() -> fixture.vehicleService.returnVehicle(TENANT, ADMIN, r1, returnReq("rt1", 999)))
                .hasMessageContaining("归还里程不能小于出车里程 1000");
            OaVehicleRequestResp returned = fixture.vehicleService
                .returnVehicle(TENANT, ADMIN, r1, returnReq("rt1", 1180));
            assertThat(returned.status()).isEqualTo(OaVehicleRequestHook.STATUS_RETURNED);
            assertThat(returned.endMileage()).isEqualTo(1180);
            assertThat(returned.fuelCost()).isEqualByComparingTo("88.00");
            assertThat(fixture.vehicleService.returnVehicle(TENANT, ADMIN, r1, returnReq("rt1", 1180)).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_RETURNED);
            assertThat(fixture.vehicleService.vehicles(TENANT, null)).filteredOn(v -> v.id() == sedan.id())
                .singleElement()
                .satisfies(v -> assertThat(v.mileage()).isEqualTo(1180));
            fixture.vehicleService.updateVehicle(TENANT, ADMIN, sedan
                .id(), new OaVehicleReq("沪A10001", "别克", 5, 1100, "ENABLED", null, sedan.version()));
            assertThat(fixture.vehicleService.vehicles(TENANT, null)).filteredOn(v -> v.id() == sedan.id())
                .singleElement()
                .satisfies(v -> assertThat(v.mileage()).isEqualTo(1180));
            assertThat(fixture.vehicleService.depart(TENANT, ADMIN, r3, new OaVehicleDepartReq("dp3", 1180)).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_IN_USE);

            assertThatThrownBy(() -> fixture.vehicleService
                .cancelByDispatcher(TENANT, ADMIN, r2, new OaResourceCancelReq(null))).hasMessageContaining("请填写取消原因");
            assertThatThrownBy(() -> fixture.vehicleService.cancelMine(TENANT, SUPERVISOR, r2, null))
                .hasMessageContaining("用车申请不存在或无权操作");
            OaVehicleRequestResp cancelled = fixture.vehicleService
                .cancelByDispatcher(TENANT, ADMIN, r2, new OaResourceCancelReq("车辆故障"));
            assertThat(cancelled.status()).isEqualTo(OaVehicleRequestHook.STATUS_CANCELLED);
            assertThat(cancelled.cancelReason()).isEqualTo("车辆故障");
            assertThat(fixture.vehicleService.cancelByDispatcher(TENANT, ADMIN, r2, new OaResourceCancelReq("再次"))
                .cancelReason()).isEqualTo("车辆故障");
            assertThat(fixture.outbox(OaVehicleService.EVENT_CANCELLED)).isEqualTo(1);

            assertThatThrownBy(() -> fixture.vehicleService.request(TENANT, SUPERVISOR, r1))
                .hasMessageContaining("用车申请不存在或无权查看");
            assertThatThrownBy(() -> fixture.vehicleService.request(102, null, r1))
                .hasMessageContaining("用车申请不存在或无权查看");
            assertThat(fixture.vehicleService.vehicles(102, null)).isEmpty();
            assertThat(fixture.vehicleService.countRequests(102, null, null, null)).isZero();
            assertThat(fixture.vehicleService.countRequests(TENANT, null, APPLICANT, null)).isEqualTo(3);
            assertThat(fixture.vehicleService.requests(TENANT, null, null, sedan.id(), 1, 10)).hasSize(2);
            assertThat(fixture.vehicleService.maintenances(TENANT, van.id(), "DONE")).hasSize(1);
            assertThatThrownBy(() -> fixture.vehicleService.requests(TENANT, "BOGUS", null, null, 1, 10))
                .hasMessageContaining("用车申请状态无效");
        });
    }

    @Test
    void vehicleLateApprovalExpiryAndApplicantCancel() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaVehicleResp sedan = fixture.vehicle("沪A10001", 5, 0);
            fixture.seedVehicleRelease();

            long late = fixture.submitVehicle("v1", 2, "N", at(9, 0), at(10, 0));
            fixture.jdbc
                .update("UPDATE oa_vehicle_request SET start_time = ?, end_time = ? WHERE application_id = ?", LocalDateTime
                    .now()
                    .minusHours(3), LocalDateTime.now().minusHours(1), late);
            assertThatThrownBy(() -> fixture.approve(SUPERVISOR, late, "a1")).hasMessageContaining("用车时段已过");
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(late)
                .getId(), new OaApprovalActionReq("r1", "已过期"));
            assertThat(fixture.vehicleService.request(TENANT, null, fixture.requestOf(late)).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_CLOSED);

            long withdrawn = fixture.submitVehicle("v2", 2, "N", at(9, 0), at(10, 0));
            fixture.approvalService.withdraw(TENANT, APPLICANT, withdrawn, new OaApprovalActionReq("w1", null));
            assertThat(fixture.vehicleService.request(TENANT, null, fixture.requestOf(withdrawn)).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_CLOSED);

            long pending = fixture.submitVehicle("v3", 2, "N", at(9, 0), at(10, 0));
            long pendingId = fixture.requestOf(pending);
            assertThatThrownBy(() -> fixture.vehicleService.cancelMine(TENANT, APPLICANT, pendingId, null))
                .hasMessageContaining("审批中的申请请撤回");
            fixture.approve(SUPERVISOR, pending, "a3");
            OaVehicleRequestResp mine = fixture.vehicleService
                .cancelMine(TENANT, APPLICANT, pendingId, new OaResourceCancelReq("行程取消"));
            assertThat(mine.status()).isEqualTo(OaVehicleRequestHook.STATUS_CANCELLED);
            assertThat(fixture.vehicleService.cancelMine(TENANT, APPLICANT, pendingId, null).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_CANCELLED);
            assertThat(fixture.outbox(OaVehicleService.EVENT_CANCELLED)).isZero();

            long dispatchedApp = fixture.submitVehicle("v4", 2, "N", at(11, 0), at(12, 0));
            long dispatchedId = fixture.requestOf(dispatchedApp);
            fixture.approve(SUPERVISOR, dispatchedApp, "a4");
            fixture.dispatch(dispatchedId, "d4", sedan.id(), null);
            long waitingApp = fixture.submitVehicle("v5", 2, "N", at(13, 0), at(14, 0));
            long waitingId = fixture.requestOf(waitingApp);
            fixture.approve(SUPERVISOR, waitingApp, "a5");

            assertThat(fixture.vehicleService.expireDispatches(LocalDateTime.now())).isZero();
            assertThat(fixture.vehicleService.expireDispatches(at(12, 0))).isEqualTo(1);
            assertThat(fixture.vehicleService.request(TENANT, null, dispatchedId).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_EXPIRED);
            assertThat(fixture.vehicleService.request(TENANT, null, waitingId).status())
                .isEqualTo(OaVehicleRequestHook.STATUS_PENDING_DISPATCH);
            assertThat(fixture.vehicleService.expireDispatches(at(14, 0))).isEqualTo(1);
            assertThat(fixture.vehicleService.expireDispatches(at(14, 0))).isZero();
            assertThat(fixture.outbox(OaVehicleService.EVENT_EXPIRED)).isEqualTo(2);
            assertThatThrownBy(() -> fixture.vehicleService
                .depart(TENANT, ADMIN, dispatchedId, new OaVehicleDepartReq("dp4", 10)))
                .hasMessageContaining("用车申请当前状态为已过期");
            assertThatThrownBy(() -> fixture.dispatch(waitingId, "d5", sedan.id(), null))
                .hasMessageContaining("用车申请当前状态为已过期");
        });
    }

    private static LocalDateTime at(int hour, int minute) {
        return DAY.withHour(hour).withMinute(minute);
    }

    private static OaMeetingRoomReq roomReq(String code,
                                            int capacity,
                                            boolean needApproval,
                                            String status,
                                            Integer version) {
        return new OaMeetingRoomReq(code, "会议室" + code, "3 楼", capacity, needApproval, status, null, version);
    }

    private static OaVehicleReturnReq returnReq(String requestId, int endMileage) {
        return new OaVehicleReturnReq(requestId, endMileage, new BigDecimal("88.00"), new BigDecimal("15.00"), null, "正常归还");
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-resource-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        OaExpenseServiceTest.createTables(jdbc);
        OaExpenseServiceTest.seedAccounts(jdbc);
        OaContractDocumentServiceTest.createContractTables(jdbc);
        createResourceTables(jdbc);
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (10, 101, '研发部', 0)");
        for (long userId = 101; userId <= 104; userId++) {
            jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, employment_status, deleted) VALUES (?, 101, ?, 'ACTIVE', 0)", userId, userId);
        }

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-resource")
            .tenantId(Long.toString(TENANT))
            .addString("meeting.bpmn20.xml", OaMeetingService.BPMN)
            .addString("vehicle.bpmn20.xml", OaVehicleService.BPMN)
            .deploy();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OaContractDocumentServiceTest.StubStaffDirectory directory = new OaContractDocumentServiceTest.StubStaffDirectory();

        OaMeetingRepository meetingRepository = new OaMeetingRepository(jdbc);
        OaVehicleRepository vehicleRepository = new OaVehicleRepository(jdbc);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaBusinessHooks hooks = new OaBusinessHooks(List
            .of(new OaMeetingBookingHook(meetingRepository, outboxService), new OaVehicleRequestHook(vehicleRepository, outboxService)), launchRepository);
        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, context
            .getBean(org.flowable.engine.HistoryService.class), repositoryService);
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaMeetingService meetingService = new OaMeetingService(meetingRepository, null, null, outboxService, objectMapper, transactionManager);
        OaVehicleService vehicleService = new OaVehicleService(vehicleRepository, directory, null, null, outboxService, objectMapper, transactionManager);

        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, repositoryService, hooks, meetingService, vehicleService, launchService, approvalService, taskService);
    }

    private static void seedRelease(JdbcTemplate jdbc,
                                    RepositoryService repositoryService,
                                    long modelId,
                                    long releaseId,
                                    String processKey,
                                    String bpmn,
                                    String businessType,
                                    String schema) {
        String definitionId = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(processKey)
            .singleResult()
            .getId();
        long formId = modelId + 19;
        long versionId = modelId + 29;
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, business_type, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (?, 101, ?, ?, 'resource', '说明', 'PUBLISHED', ?, ?, 1, ?, ?, 0, 7, CURRENT_TIMESTAMP, 0)
            """, modelId, processKey, processKey, businessType, releaseId, formId, versionId);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (?, 101, ?, 1, ?, ?, ?, ?, 1, ?, ?, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, releaseId, modelId, bpmn, "a".repeat(64), definitionId, processKey, formId, versionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (?, 101, ?, ?, 'PUBLISHED', 0)", formId, processKey, processKey);
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (?, 101, ?, 1, ?, ?, 5, 7, CURRENT_TIMESTAMP)
            """, versionId, formId, schema, "b".repeat(64));
    }

    static void createResourceTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_meeting_room (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(32) NOT NULL,
              name VARCHAR(100) NOT NULL, location VARCHAR(200), capacity INT NOT NULL,
              need_approval BOOLEAN NOT NULL DEFAULT FALSE, status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
              remark VARCHAR(500), version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_meeting_room_code UNIQUE (tenant_id, code)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_meeting_booking (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, room_id BIGINT NOT NULL,
              application_id BIGINT, request_id VARCHAR(64), booker_id BIGINT NOT NULL, subject VARCHAR(200) NOT NULL,
              start_time TIMESTAMP NOT NULL, end_time TIMESTAMP NOT NULL, attendees INT NOT NULL,
              status VARCHAR(20) NOT NULL, cancel_by BIGINT, cancel_time TIMESTAMP, cancel_reason VARCHAR(500),
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_meeting_booking_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_meeting_booking_request UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_vehicle (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              plate_no VARCHAR(20) NOT NULL, model VARCHAR(100) NOT NULL, seats INT NOT NULL,
              mileage INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', remark VARCHAR(500),
              version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_vehicle_plate UNIQUE (tenant_id, plate_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_vehicle_driver (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(64) NOT NULL,
              phone VARCHAR(32), user_id BIGINT, status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', remark VARCHAR(500),
              version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_vehicle_driver_user UNIQUE (tenant_id, user_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_vehicle_maintenance (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, vehicle_id BIGINT NOT NULL,
              request_id VARCHAR(64) NOT NULL, maint_type VARCHAR(20) NOT NULL, start_time TIMESTAMP NOT NULL,
              end_time TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL, cost DECIMAL(12,2), remark VARCHAR(500),
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, complete_user BIGINT, complete_time TIMESTAMP,
              CONSTRAINT uk_vehicle_maintenance_request UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_vehicle_request (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, purpose VARCHAR(500) NOT NULL,
              destination VARCHAR(200) NOT NULL, passengers INT NOT NULL, need_driver BOOLEAN NOT NULL DEFAULT FALSE,
              start_time TIMESTAMP NOT NULL, end_time TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL,
              vehicle_id BIGINT, driver_id BIGINT, dispatch_request_id VARCHAR(64), dispatch_by BIGINT,
              dispatch_time TIMESTAMP, dispatch_remark VARCHAR(500), depart_request_id VARCHAR(64),
              depart_time TIMESTAMP, start_mileage INT, return_request_id VARCHAR(64), return_time TIMESTAMP,
              end_mileage INT, fuel_cost DECIMAL(12,2), toll_cost DECIMAL(12,2), other_cost DECIMAL(12,2),
              return_remark VARCHAR(500), exec_by BIGINT, cancel_by BIGINT, cancel_time TIMESTAMP,
              cancel_reason VARCHAR(500), create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_vehicle_request_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_vehicle_request_dispatch UNIQUE (tenant_id, dispatch_request_id),
              CONSTRAINT uk_vehicle_request_depart UNIQUE (tenant_id, depart_request_id),
              CONSTRAINT uk_vehicle_request_return UNIQUE (tenant_id, return_request_id)
            )
            """);
    }

    private record Fixture(JdbcTemplate jdbc, RepositoryService repositoryService, OaBusinessHooks hooks,
                           OaMeetingService meetingService, OaVehicleService vehicleService,
                           OaLaunchService launchService, OaApprovalService approvalService, TaskService taskService) {

        private OaMeetingRoomResp room(String code, int capacity, boolean needApproval) {
            return meetingService.createRoom(TENANT, ADMIN, roomReq(code, capacity, needApproval, "ENABLED", null));
        }

        private OaMeetingBookingResp book(long userId,
                                          String requestId,
                                          long roomId,
                                          LocalDateTime start,
                                          LocalDateTime end,
                                          int attendees) {
            return meetingService
                .book(TENANT, userId, new OaMeetingBookReq(requestId, roomId, "周会", start, end, attendees));
        }

        private OaVehicleResp vehicle(String plateNo, int seats, int mileage) {
            return vehicleService
                .createVehicle(TENANT, ADMIN, new OaVehicleReq(plateNo, "大众", seats, mileage, "ENABLED", null, null));
        }

        private OaVehicleRequestResp dispatch(long id, String requestId, long vehicleId, Long driverId) {
            return vehicleService
                .dispatch(TENANT, ADMIN, id, new OaVehicleDispatchReq(requestId, vehicleId, driverId, null));
        }

        private void seedMeetingRelease() {
            seedRelease(jdbc, repositoryService, 1, MEETING_RELEASE, OaMeetingService.MODEL_KEY, OaMeetingService.BPMN, OaMeetingBookingHook.BUSINESS_TYPE, meetingService
                .schema(meetingService.rooms(TENANT, "ENABLED")
                    .stream()
                    .filter(OaMeetingRoomResp::needApproval)
                    .toList()));
        }

        private void seedVehicleRelease() {
            seedRelease(jdbc, repositoryService, 2, VEHICLE_RELEASE, OaVehicleService.MODEL_KEY, OaVehicleService.BPMN, OaVehicleRequestHook.BUSINESS_TYPE, vehicleService
                .schema());
        }

        private long submit(String requestId, long releaseId, String title, Map<String, Object> form) {
            return launchService.submit(TENANT, APPLICANT, releaseId, new OaLaunchSubmitReq(requestId, title, form, List
                .of())).applicationId();
        }

        private long submitMeeting(String requestId,
                                   String roomCode,
                                   LocalDateTime start,
                                   LocalDateTime end,
                                   int attendees) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaMeetingBookingHook.FIELD_ROOM, roomCode);
            form.put(OaMeetingBookingHook.FIELD_SUBJECT, "季度评审");
            form.put(OaMeetingBookingHook.FIELD_START, start.format(FORM_TIME));
            form.put(OaMeetingBookingHook.FIELD_END, end.format(FORM_TIME));
            form.put(OaMeetingBookingHook.FIELD_ATTENDEES, attendees);
            return submit(requestId, MEETING_RELEASE, "会议室预订", form);
        }

        private long submitVehicle(String requestId,
                                   int passengers,
                                   String needDriver,
                                   LocalDateTime start,
                                   LocalDateTime end) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaVehicleRequestHook.FIELD_PURPOSE, "拜访客户");
            form.put(OaVehicleRequestHook.FIELD_DESTINATION, "浦东新区");
            form.put(OaVehicleRequestHook.FIELD_PASSENGERS, passengers);
            form.put(OaVehicleRequestHook.FIELD_START, start.format(FORM_TIME));
            form.put(OaVehicleRequestHook.FIELD_END, end.format(FORM_TIME));
            form.put(OaVehicleRequestHook.FIELD_NEED_DRIVER, needDriver);
            return submit(requestId, VEHICLE_RELEASE, "用车申请", form);
        }

        private void approve(long userId, long applicationId, String requestId) {
            approvalService.approve(TENANT, userId, task(applicationId)
                .getId(), new OaApprovalActionReq(requestId, null));
        }

        private Task task(long applicationId) {
            String instanceId = jdbc
                .queryForObject("SELECT process_instance_id FROM oa_application WHERE id = ?", String.class, applicationId);
            Task task = taskService.createTaskQuery().processInstanceId(instanceId).singleResult();
            assertThat(task).isNotNull();
            return task;
        }

        private String applicationStatus(long applicationId) {
            return jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private OaMeetingBookingResp bookingOf(long applicationId) {
            long id = jdbc
                .queryForObject("SELECT id FROM oa_meeting_booking WHERE application_id = ?", Long.class, applicationId);
            return meetingService.bookings(TENANT, null, null, null, null, 1, 100)
                .stream()
                .filter(booking -> booking.id() == id)
                .findFirst()
                .orElseThrow();
        }

        private long requestOf(long applicationId) {
            return jdbc
                .queryForObject("SELECT id FROM oa_vehicle_request WHERE application_id = ?", Long.class, applicationId);
        }

        private long outbox(String eventType) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM oa_outbox WHERE event_type = ?", Long.class, eventType);
        }

        private String outboxRecipients(String eventType) {
            return String.join(";", jdbc
                .queryForList("SELECT payload FROM oa_outbox WHERE event_type = ?", String.class, eventType));
        }
    }
}
