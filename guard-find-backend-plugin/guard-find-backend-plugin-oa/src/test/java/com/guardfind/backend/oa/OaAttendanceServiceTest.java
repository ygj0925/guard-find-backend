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
import com.guardfind.backend.oa.attendance.OaAttendanceHookSupport;
import com.guardfind.backend.oa.attendance.OaCorrectionHook;
import com.guardfind.backend.oa.attendance.OaOutingHook;
import com.guardfind.backend.oa.attendance.OaOvertimeHook;
import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.business.OaBusinessHooks;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.leave.OaLeaveDayCalculator;
import com.guardfind.backend.oa.leave.OaLeaveHook;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaAttendanceDailyResp;
import com.guardfind.backend.oa.model.OaAttendanceImportReq;
import com.guardfind.backend.oa.model.OaAttendanceImportResp;
import com.guardfind.backend.oa.model.OaAttendanceImportRow;
import com.guardfind.backend.oa.model.OaAttendanceSummaryResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaScheduleSaveReq;
import com.guardfind.backend.oa.model.OaShiftReq;
import com.guardfind.backend.oa.model.OaShiftResp;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaAttendanceRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaLeaveRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaAttendanceService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaLeaveService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.service.OaTravelService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import com.guardfind.backend.oa.travel.OaTravelHook;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 12.3/12.4 假勤：班次排班、考勤导入幂等与补卡保留、补卡/加班/外出与请假出差互斥、日报状态与月度汇总。
 */
class OaAttendanceServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long HR = 103;
    private static final long FINANCE = 104;
    private static final long CORRECTION_RELEASE = 10;
    private static final long OVERTIME_RELEASE = 11;
    private static final long OUTING_RELEASE = 12;
    private static final long LEAVE_RELEASE = 13;
    private static final long TRAVEL_RELEASE = 14;
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Test
    void templatesPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaAttendanceService service = fixture.service;
            Map<String, String> schemas = Map.of(OaCorrectionHook.BUSINESS_TYPE, service
                .correctionSchema(), OaOvertimeHook.BUSINESS_TYPE, service
                    .overtimeSchema(), OaOutingHook.BUSINESS_TYPE, service.outingSchema());
            schemas.forEach((type, schema) -> {
                assertThat(new OaFormSchemaValidator().validate(schema, true).valid()).isTrue();
                assertThat(fixture.hooks.validateForm(type, schema)).isEmpty();
            });
            assertThat(fixture.hooks.validateForm(OaCorrectionHook.BUSINESS_TYPE, service.overtimeSchema()))
                .anyMatch(error -> error.contains("补卡表单缺少字段 checkType"));
            assertThat(fixture.hooks.validateForm(OaOutingHook.BUSINESS_TYPE, service.overtimeSchema()))
                .anyMatch(error -> error.contains("外出表单缺少字段 destination"));

            for (String bpmn : List
                .of(OaAttendanceService.CORRECTION_BPMN, OaAttendanceService.OVERTIME_BPMN, OaAttendanceService.OUTING_BPMN)) {
                OaBpmnValidationResult result = new OaBpmnValidator().validate(bpmn.getBytes(StandardCharsets.UTF_8));
                assertThat(result.errors()).isEmpty();
                assertThat(result.assigneeVariables())
                    .containsExactlyInAnyOrder("supervisor", OaAttendanceHookSupport.VAR_HR_REVIEWER);
            }
            assertThat(new OaBpmnValidator().validate(OaAttendanceService.OVERTIME_BPMN
                .getBytes(StandardCharsets.UTF_8)).processKey()).isEqualTo(OaAttendanceService.OVERTIME_MODEL_KEY);
        });
    }

    @Test
    void shiftAndScheduleRules() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaAttendanceService service = fixture.service;
            OaShiftResp day = fixture.dayShift();
            assertThat(day.startTime()).isEqualTo("09:00");
            assertThat(day.graceMinutes()).isEqualTo(5);
            assertThatThrownBy(() -> service.createShift(TENANT, 1, shift("DAY", "09:00", "18:00", null)))
                .hasMessageContaining("班次编码已存在");
            assertThatThrownBy(() -> service.createShift(TENANT, 1, shift("NIGHT", "22:00", "06:00", null)))
                .hasMessageContaining("暂不支持跨天班次");
            OaShiftResp updated = service.updateShift(TENANT, 1, day
                .id(), new OaShiftReq(null, "白班", "08:30", "17:30", 10, "ENABLED", null, day.version()));
            assertThat(updated.endTime()).isEqualTo("17:30");
            assertThatThrownBy(() -> service.updateShift(TENANT, 1, day
                .id(), new OaShiftReq(null, "冲突", "08:30", "17:30", 10, "ENABLED", null, day.version())))
                .hasMessageContaining("已被他人修改");

            OaShiftResp off = service
                .createShift(TENANT, 1, new OaShiftReq("OFF", "停用班", "09:00", "18:00", 0, "DISABLED", null, null));
            assertThatThrownBy(() -> service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List
                .of(APPLICANT), TODAY, TODAY, null, off.id(), false))).hasMessageContaining("班次不存在或已停用");
            assertThatThrownBy(() -> service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List
                .of(APPLICANT, 105L), TODAY, TODAY, null, day.id(), false))).hasMessageContaining("以下员工不存在或不在职: [105]");
            assertThatThrownBy(() -> service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List
                .of(APPLICANT), TODAY, TODAY.plusDays(62), null, day.id(), false)))
                .hasMessageContaining("日期区间不能超过 62 天");

            LocalDate monday = TODAY.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            assertThat(service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List
                .of(APPLICANT, SUPERVISOR), monday, monday.plusDays(6), List.of(1, 3), day.id(), false))).isEqualTo(4);
            assertThat(service.schedules(TENANT, monday, monday.plusDays(6), APPLICANT)).extracting(row -> row
                .workDate()).containsExactly(monday, monday.plusDays(2));
            assertThat(service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List.of(APPLICANT), monday, monday
                .plusDays(6), null, day.id(), false))).isEqualTo(7);
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_schedule WHERE user_id = " + APPLICANT)).isEqualTo(7);
            assertThat(service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List.of(APPLICANT), monday, monday
                .plusDays(6), List.of(6, 7), null, true))).isEqualTo(2);
            assertThat(service.schedules(TENANT, monday, monday.plusDays(6), null)).hasSize(7);
            assertThatThrownBy(() -> service.shifts(TENANT, "BOGUS")).hasMessageContaining("班次状态无效");
            assertThat(service.shifts(TENANT, "ENABLED")).extracting(OaShiftResp::code).containsExactly("DAY");
        });
    }

    @Test
    void importValidationAndIdempotency() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaAttendanceService service = fixture.service;
            String y = YESTERDAY.toString();
            List<OaAttendanceImportRow> bad = List
                .of(row("", y, "09:00", "18:00"), row("E999", y, "09:00", "18:00"), row("E105", y, "09:00", "18:00"), row("E101", "2026/01/01", "09:00", "18:00"), row("E101", TODAY
                    .plusDays(1)
                    .toString(), "09:00", "18:00"), row("E101", TODAY.minusDays(91)
                        .toString(), "09:00", "18:00"), row("E101", TODAY.minusDays(2)
                            .toString(), "", " "), row("E101", TODAY.minusDays(2)
                                .toString(), "9:00", null), row("E101", TODAY.minusDays(2)
                                    .toString(), "18:00", "09:00"), row("E101", y, "09:00", "18:00"), row("E101", y, "09:05", "18:00"));
            OaAttendanceImportResp preview = service.previewImport(TENANT, new OaAttendanceImportReq(null, null, bad));
            assertThat(preview.valid()).isFalse();
            assertThat(preview.errors())
                .containsExactly("第 1 行：缺少工号", "第 2 行：工号 E999 不存在或不在职", "第 3 行：工号 E105 不存在或不在职", "第 4 行：日期格式必须是 YYYY-MM-DD", "第 5 行：日期不能晚于今天", "第 6 行：只能导入 90 天内的记录", "第 7 行：上班与下班时间至少填一个", "第 8 行：时间格式必须是 HH:mm", "第 9 行：下班时间必须晚于上班时间", "第 11 行：与第 10 行是同一员工同一天");
            OaAttendanceImportResp rejected = service
                .commitImport(TENANT, 1, new OaAttendanceImportReq("bad-1", "bad.csv", bad));
            assertThat(rejected.valid()).isFalse();
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_attendance_import")).isZero();
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_attendance_record")).isZero();
            assertThatThrownBy(() -> service.commitImport(TENANT, 1, new OaAttendanceImportReq(" ", null, bad)))
                .hasMessageContaining("缺少请求号");

            List<OaAttendanceImportRow> good = List
                .of(row("E101", y, "09:00", "18:00"), row(" E102 ", y, "08:55", null));
            OaAttendanceImportResp dryRun = service.previewImport(TENANT, new OaAttendanceImportReq(null, null, good));
            assertThat(dryRun.valid()).isTrue();
            assertThat(dryRun.insertedRows()).isEqualTo(2);
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_attendance_record")).isZero();

            OaAttendanceImportResp first = service
                .commitImport(TENANT, 1, new OaAttendanceImportReq("imp-1", "a.csv", good));
            assertThat(first.valid()).isTrue();
            assertThat(first.replayed()).isFalse();
            assertThat(first.importId()).isNotNull();
            assertThat(first.insertedRows()).isEqualTo(2);
            OaAttendanceImportResp replay = service
                .commitImport(TENANT, 1, new OaAttendanceImportReq("imp-1", "a.csv", List
                    .of(row("E101", y, "10:00", "19:00"))));
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.importId()).isEqualTo(first.importId());
            assertThat(replay.insertedRows()).isEqualTo(2);
            assertThat(fixture.checkIn(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(9, 0));

            OaAttendanceImportResp second = service
                .commitImport(TENANT, 1, new OaAttendanceImportReq("imp-2", null, List
                    .of(row("E101", y, "08:58", null))));
            assertThat(second.insertedRows()).isZero();
            assertThat(second.updatedRows()).isEqualTo(1);
            assertThat(fixture.checkIn(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(8, 58));
            assertThat(fixture.checkOut(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(18, 0));
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_attendance_import")).isEqualTo(2);
            assertThat(fixture.count("SELECT import_id FROM oa_attendance_record WHERE user_id = " + APPLICANT))
                .isEqualTo(second.importId());

            assertThat(service.records(TENANT, YESTERDAY, YESTERDAY, null, 1, 10)).hasSize(2).anySatisfy(record -> {
                assertThat(record.employeeNo()).isEqualTo("E102");
                assertThat(record.userName()).isEqualTo("Bob");
                assertThat(record.checkOut()).isNull();
            });
            assertThat(service.countRecords(TENANT, YESTERDAY, YESTERDAY, SUPERVISOR)).isEqualTo(1);
        });
    }

    @Test
    void correctionApprovalWritesRecordAndImportKeepsIt() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaAttendanceService service = fixture.service;
            OaShiftResp day = fixture.dayShift();
            service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List.of(APPLICANT), YESTERDAY, YESTERDAY, null, day
                .id(), false));
            String y = YESTERDAY.toString();
            service.commitImport(TENANT, 1, new OaAttendanceImportReq("imp-1", null, List
                .of(row("E101", y, "09:20", "18:05"))));
            OaAttendanceDailyResp before = fixture.daily(YESTERDAY);
            assertThat(before.status()).isEqualTo(OaAttendanceService.STATUS_LATE);
            assertThat(before.late()).isTrue();

            long inApp = fixture.submitCorrection("c-in", OaCorrectionHook.CHECK_IN, YESTERDAY.atTime(8, 55));
            assertThat(fixture.requestStatus(inApp)).isEqualTo(OaAttendanceHookSupport.STATUS_APPROVING);
            assertThatThrownBy(() -> fixture.submitCorrection("c-dup", OaCorrectionHook.CHECK_IN, YESTERDAY
                .atTime(8, 50))).hasMessageContaining(y + " 已有上班补卡申请");
            fixture.approve(SUPERVISOR, inApp, "a-in-1");
            assertThat(fixture.checkIn(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(9, 20));
            Task hrTask = fixture.task(inApp);
            assertThat(hrTask.getTaskDefinitionKey()).isEqualTo("hrApprove");
            fixture.approvalService.approve(TENANT, HR, hrTask.getId(), action("a-in-2"));
            assertThat(fixture.applicationStatus(inApp)).isEqualTo("APPROVED");
            assertThat(fixture.requestStatus(inApp)).isEqualTo(OaAttendanceHookSupport.STATUS_APPROVED);
            assertThat(fixture.checkIn(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(8, 55));
            OaAttendanceDailyResp corrected = fixture.daily(YESTERDAY);
            assertThat(corrected.status()).isEqualTo(OaAttendanceService.STATUS_NORMAL);
            assertThat(corrected.checkInSource()).isEqualTo(OaCorrectionHook.SOURCE_CORRECTION);
            fixture.hooks.finished(TENANT, inApp, "APPROVED", HR, LocalDateTime.now());

            OaAttendanceImportResp reimport = service
                .commitImport(TENANT, 1, new OaAttendanceImportReq("imp-2", null, List
                    .of(row("E101", y, "09:30", "17:00"))));
            assertThat(reimport.keptCorrections()).isEqualTo(1);
            assertThat(reimport.updatedRows()).isEqualTo(1);
            assertThat(fixture.checkIn(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(8, 55));
            assertThat(fixture.checkOut(APPLICANT, YESTERDAY)).isEqualTo(YESTERDAY.atTime(17, 0));
            assertThat(fixture.daily(YESTERDAY).status()).isEqualTo(OaAttendanceService.STATUS_EARLY);

            long outApp = fixture.submitCorrection("c-out", OaCorrectionHook.CHECK_OUT, YESTERDAY.atTime(18, 0));
            fixture.approve(SUPERVISOR, outApp, "a-out-1");
            fixture.approve(HR, outApp, "a-out-2");
            OaAttendanceDailyResp fixed = fixture.daily(YESTERDAY);
            assertThat(fixed.status()).isEqualTo(OaAttendanceService.STATUS_NORMAL);
            assertThat(fixed.checkOutSource()).isEqualTo(OaCorrectionHook.SOURCE_CORRECTION);
            OaAttendanceImportResp kept = service.previewImport(TENANT, new OaAttendanceImportReq(null, null, List
                .of(row("E101", y, "09:30", "17:00"))));
            assertThat(kept.keptCorrections()).isEqualTo(2);
            assertThat(kept.updatedRows()).isZero();

            LocalDate other = TODAY.minusDays(2);
            long rejected = fixture.submitCorrection("c-r", OaCorrectionHook.CHECK_IN, other.atTime(9, 0));
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(rejected)
                .getId(), new OaApprovalActionReq("r-1", "无需补卡"));
            assertThat(fixture.requestStatus(rejected)).isEqualTo(OaAttendanceHookSupport.STATUS_CLOSED);
            assertThat(fixture
                .count("SELECT COUNT(*) FROM oa_attendance_record WHERE work_date = DATE '" + other + "'")).isZero();
            long again = fixture.submitCorrection("c-r2", OaCorrectionHook.CHECK_IN, other.atTime(9, 0));
            assertThat(fixture.requestStatus(again)).isEqualTo(OaAttendanceHookSupport.STATUS_APPROVING);

            assertThatThrownBy(() -> fixture.submitCorrection("c-f", OaCorrectionHook.CHECK_IN, LocalDateTime.now()
                .plusHours(2))).hasMessageContaining("补卡时间不能晚于当前时间");
            assertThatThrownBy(() -> fixture.submitCorrection("c-o", OaCorrectionHook.CHECK_IN, TODAY.minusDays(31)
                .atTime(9, 0))).hasMessageContaining("只能补 30 天内的卡");

            assertThat(service.requests(TENANT, OaAttendanceRepository.KIND_CORRECTION, null, null, APPLICANT, 1, 10))
                .hasSize(4);
            assertThat(service.countRequests(TENANT, null, OaAttendanceHookSupport.STATUS_APPROVED, "Alice", null))
                .isEqualTo(2);
            assertThatThrownBy(() -> service.requests(TENANT, "BOGUS", null, null, null, 1, 10))
                .hasMessageContaining("申请类型无效");
        });
    }

    @Test
    void correctionMonthlyLimit() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            LocalDate base = YESTERDAY.getDayOfMonth() >= 3
                ? YESTERDAY
                : YESTERDAY.minusDays(YESTERDAY.getDayOfMonth());
            fixture.submitCorrection("m1", OaCorrectionHook.CHECK_IN, base.atTime(9, 0));
            fixture.submitCorrection("m2", OaCorrectionHook.CHECK_OUT, base.atTime(18, 0));
            fixture.submitCorrection("m3", OaCorrectionHook.CHECK_IN, base.minusDays(1).atTime(9, 0));
            fixture.submitCorrection("m4", OaCorrectionHook.CHECK_OUT, base.minusDays(1).atTime(18, 0));
            fixture.submitCorrection("m5", OaCorrectionHook.CHECK_IN, base.minusDays(2).atTime(9, 0));
            assertThatThrownBy(() -> fixture.submitCorrection("m6", OaCorrectionHook.CHECK_OUT, base.minusDays(2)
                .atTime(18, 0))).hasMessageContaining(YearMonth.from(base) + " 补卡已达 5 次上限");
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_attendance_request")).isEqualTo(5);
        });
    }

    @Test
    void overtimeOutingLeaveAndTravelAreMutuallyExclusive() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            LocalDate mon = TODAY.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            LocalDate tue = mon.plusDays(1);
            LocalDate wed = mon.plusDays(2);
            LocalDate thu = mon.plusDays(3);

            long overtime = fixture.submitOvertime("o1", mon.atTime(19, 0), mon.atTime(21, 0));
            assertThat(fixture.jdbc
                .queryForObject("SELECT hours FROM oa_attendance_request WHERE application_id = ?", BigDecimal.class, overtime))
                .isEqualByComparingTo("2");
            assertThat(fixture.variable(overtime, OaOvertimeHook.VAR_HOURS)).isEqualTo(2.0);
            assertThatThrownBy(() -> fixture.submitOvertime("o2", mon.atTime(20, 0), mon.atTime(22, 0)))
                .hasMessageContaining("与已有加班申请")
                .hasMessageContaining("时间重叠");
            assertThatThrownBy(() -> fixture.submitOuting("w1", mon.atTime(20, 30), mon.atTime(21, 0)))
                .hasMessageContaining("与已有加班申请");
            assertThatThrownBy(() -> fixture.submitOvertime("o3", mon.atTime(21, 0), mon.atTime(21, 20)))
                .hasMessageContaining("单次加班不能少于 0.5 小时");
            assertThatThrownBy(() -> fixture.submitOvertime("o4", mon.atTime(6, 0), mon.atTime(19, 0)))
                .hasMessageContaining("单次加班不能超过 12 小时");
            assertThatThrownBy(() -> fixture.submitOvertime("o5", mon.atTime(23, 0), mon.atTime(22, 0)))
                .hasMessageContaining("结束时间必须晚于开始时间");
            assertThatThrownBy(() -> fixture.submitOvertime("o6", TODAY.plusDays(40).atTime(19, 0), TODAY.plusDays(40)
                .atTime(20, 0))).hasMessageContaining("开始时间不能晚于 30 天后");

            long leave = fixture.submitLeave("l1", tue, OaLeaveDayCalculator.AM, tue, OaLeaveDayCalculator.PM);
            assertThat(fixture.jdbc
                .queryForObject("SELECT status FROM oa_leave_request WHERE application_id = ?", String.class, leave))
                .isEqualTo(OaLeaveHook.STATUS_RECORDED);
            assertThatThrownBy(() -> fixture.submitOuting("w2", tue.atTime(10, 0), tue.atTime(11, 0)))
                .hasMessageContaining("与请假（病假");
            assertThatThrownBy(() -> fixture.submitOvertime("o7", tue.atTime(19, 0), tue.atTime(20, 0)))
                .hasMessageContaining("与请假");
            assertThatThrownBy(() -> fixture
                .submitLeave("l2", mon, OaLeaveDayCalculator.PM, mon, OaLeaveDayCalculator.PM))
                .hasMessageContaining("与已有加班申请（" + mon + "）时间重叠");
            assertThatThrownBy(() -> fixture
                .submitLeave("l3", tue, OaLeaveDayCalculator.PM, wed, OaLeaveDayCalculator.AM))
                .hasMessageContaining("与已有请假");
            fixture.submitLeave("l4", wed, OaLeaveDayCalculator.PM, wed, OaLeaveDayCalculator.PM);
            fixture.submitLeave("l5", mon, OaLeaveDayCalculator.AM, mon, OaLeaveDayCalculator.AM);

            fixture.submitTravel("t1", thu, thu.plusDays(1));
            assertThatThrownBy(() -> fixture.submitOuting("w3", thu.atTime(10, 0), thu.atTime(11, 0)))
                .hasMessageContaining("与出差（上海");
            fixture.submitOvertime("o8", thu.atTime(19, 0), thu.atTime(20, 0));
            long outing = fixture.submitOuting("w4", wed.atTime(9, 0), wed.atTime(11, 30));
            assertThat(fixture.variable(outing, OaOutingHook.VAR_HOURS)).isEqualTo(2.5);

            fixture.approvalService.withdraw(TENANT, APPLICANT, overtime, action("wd-1"));
            assertThat(fixture.requestStatus(overtime)).isEqualTo(OaAttendanceHookSupport.STATUS_CLOSED);
            fixture.submitOvertime("o9", mon.atTime(20, 0), mon.atTime(22, 0));
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_attendance_request WHERE status <> 'CLOSED'"))
                .isEqualTo(3);
        });
    }

    @Test
    void dailyStatusesAndMonthlySummary() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaAttendanceService service = fixture.service;
            YearMonth month = YearMonth.from(TODAY).minusMonths(1);
            OaShiftResp day = fixture.dayShift();
            service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List.of(APPLICANT), month.atDay(1), month
                .atDay(10), null, day.id(), false));
            service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List.of(SUPERVISOR), month.atDay(1), month
                .atDay(1), null, day.id(), false));
            fixture.jdbc
                .update("UPDATE oa_schedule SET shift_id = NULL WHERE user_id = ? AND work_date = ?", APPLICANT, month
                    .atDay(9));
            service.commitImport(TENANT, 1, new OaAttendanceImportReq("imp-m", null, List.of(row("E101", month.atDay(1)
                .toString(), "09:00", "18:00"), row("E101", month.atDay(2)
                    .toString(), "09:10", "18:00"), row("E101", month.atDay(3)
                        .toString(), "09:03", "17:30"), row("E101", month.atDay(4)
                            .toString(), "09:00", null), row("E101", month.atDay(8)
                                .toString(), "09:30", "18:00"), row("E101", month.atDay(10)
                                    .toString(), null, "18:00"), row("E102", month.atDay(1)
                                        .toString(), "08:50", "18:10"))));

            fixture.application(900, OaLeaveHook.BUSINESS_TYPE, "APPROVED");
            long typeId = fixture.jdbc.queryForObject("SELECT id FROM oa_leave_type WHERE code = 'SICK'", Long.class);
            fixture.jdbc.update("""
                INSERT INTO oa_leave_request
                  (tenant_id, application_id, applicant_id, leave_type_id, leave_type_code, leave_type_name, start_date,
                   start_half, end_date, end_half, days, period_year, status, create_time)
                VALUES (101, 900, 101, ?, 'SICK', '病假', ?, 'AM', ?, 'PM', 1, ?, 'RECORDED', CURRENT_TIMESTAMP)
                """, typeId, month.atDay(6), month.atDay(6), month.getYear());
            fixture.application(901, OaTravelHook.BUSINESS_TYPE, "APPROVED");
            fixture.jdbc.update("""
                INSERT INTO oa_travel
                  (tenant_id, application_id, applicant_id, travel_no, destination, start_date, end_date, days,
                   budget_amount, status, create_time)
                VALUES (101, 901, 101, 'TR0001', '上海', ?, ?, 1, 100, 'APPROVED', CURRENT_TIMESTAMP)
                """, month.atDay(7), month.atDay(7));
            fixture.request(902, OaAttendanceRepository.KIND_OUTING, month.atDay(8).atTime(8, 0), month.atDay(8)
                .atTime(10, 0), "2", null, "客户现场", "APPROVED");
            fixture.request(903, OaAttendanceRepository.KIND_OVERTIME, month.atDay(1).atTime(19, 0), month.atDay(1)
                .atTime(21, 0), "2", null, null, "APPROVED");
            fixture.request(904, OaAttendanceRepository.KIND_OVERTIME, month.atDay(2).atTime(19, 0), month.atDay(2)
                .atTime(21, 0), "2", null, null, "CLOSED");
            fixture.request(905, OaAttendanceRepository.KIND_CORRECTION, month.atDay(3).atTime(9, 0), month.atDay(3)
                .atTime(9, 0), "0", "IN", null, "APPROVED");

            List<OaAttendanceDailyResp> daily = service.daily(TENANT, APPLICANT, month.atDay(1), month.atDay(10));
            assertThat(daily).extracting(OaAttendanceDailyResp::status)
                .containsExactly(OaAttendanceService.STATUS_NORMAL, OaAttendanceService.STATUS_LATE, OaAttendanceService.STATUS_EARLY, OaAttendanceService.STATUS_MISSING_OUT, OaAttendanceService.STATUS_ABSENT, OaAttendanceService.STATUS_LEAVE, OaAttendanceService.STATUS_TRAVEL, OaAttendanceService.STATUS_OUTING, OaAttendanceService.STATUS_REST, OaAttendanceService.STATUS_MISSING_IN);
            assertThat(daily.get(0).overtimeHours()).isEqualByComparingTo("2");
            assertThat(daily.get(0).shiftStart()).isEqualTo("09:00");
            assertThat(daily.get(0).userName()).isEqualTo("Alice");
            assertThat(daily.get(1).overtimeHours()).isEqualByComparingTo("0");
            assertThat(daily.get(1).late()).isTrue();
            assertThat(daily.get(2).early()).isTrue();
            assertThat(daily.get(2).late()).isFalse();
            assertThat(daily.get(5).note()).isEqualTo("请假：病假");
            assertThat(daily.get(6).note()).isEqualTo("出差：上海");
            assertThat(daily.get(7).note()).isEqualTo("外出：客户现场");
            assertThat(daily.get(8).shiftName()).isNull();

            service.saveSchedules(TENANT, 1, new OaScheduleSaveReq(List.of(APPLICANT), TODAY, TODAY, null, day
                .id(), false));
            assertThat(service.daily(TENANT, APPLICANT, TODAY, TODAY.plusDays(1)))
                .extracting(OaAttendanceDailyResp::status)
                .containsExactly(OaAttendanceService.STATUS_PENDING, OaAttendanceService.STATUS_UNSCHEDULED);

            List<OaAttendanceSummaryResp> summary = service.summary(TENANT, month, null);
            assertThat(summary).extracting(OaAttendanceSummaryResp::userId).containsExactly(APPLICANT, SUPERVISOR);
            OaAttendanceSummaryResp alice = summary.get(0);
            assertThat(alice.employeeNo()).isEqualTo("E101");
            assertThat(alice.scheduledDays()).isEqualTo(9);
            assertThat(alice.normalDays()).isEqualTo(1);
            assertThat(alice.lateCount()).isEqualTo(1);
            assertThat(alice.earlyCount()).isEqualTo(1);
            assertThat(alice.missingCount()).isEqualTo(2);
            assertThat(alice.absentDays()).isEqualTo(1);
            assertThat(alice.leaveDays()).isEqualTo(1);
            assertThat(alice.travelDays()).isEqualTo(1);
            assertThat(alice.outingCount()).isEqualTo(1);
            assertThat(alice.correctionCount()).isEqualTo(1);
            assertThat(alice.overtimeHours()).isEqualByComparingTo("2");
            OaAttendanceSummaryResp bob = summary.get(1);
            assertThat(bob.scheduledDays()).isEqualTo(1);
            assertThat(bob.normalDays()).isEqualTo(1);
            assertThat(service.summary(TENANT, month, "bob")).extracting(OaAttendanceSummaryResp::userId)
                .containsExactly(SUPERVISOR);
            assertThat(service.summary(TENANT, month.minusMonths(1), null)).isEmpty();
        });
    }

    static void createAttendanceTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_shift (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(32) NOT NULL,
              name VARCHAR(64) NOT NULL, start_time TIME NOT NULL, end_time TIME NOT NULL,
              grace_minutes INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', remark VARCHAR(500),
              version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_shift UNIQUE (tenant_id, code)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_schedule (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
              work_date DATE NOT NULL, shift_id BIGINT, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP,
              CONSTRAINT uk_schedule UNIQUE (tenant_id, user_id, work_date)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_attendance_import (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              request_id VARCHAR(64) NOT NULL, file_name VARCHAR(200), total_rows INT NOT NULL,
              inserted_rows INT NOT NULL, updated_rows INT NOT NULL, kept_corrections INT NOT NULL DEFAULT 0,
              operator_id BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_attendance_import UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_attendance_record (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
              work_date DATE NOT NULL, check_in TIMESTAMP, check_out TIMESTAMP, check_in_source VARCHAR(20),
              check_out_source VARCHAR(20), import_id BIGINT, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_attendance_record UNIQUE (tenant_id, user_id, work_date)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_attendance_request (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL,
              work_date DATE NOT NULL, start_time TIMESTAMP NOT NULL, end_time TIMESTAMP NOT NULL,
              hours DECIMAL(5,1) NOT NULL DEFAULT 0, check_type VARCHAR(8), destination VARCHAR(100),
              status VARCHAR(20) NOT NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_attendance_request UNIQUE (tenant_id, application_id)
            )
            """);
    }

    private static OaShiftReq shift(String code, String start, String end, Integer version) {
        return new OaShiftReq(code, "白班", start, end, 5, null, null, version);
    }

    private static OaAttendanceImportRow row(String employeeNo, String date, String in, String out) {
        return new OaAttendanceImportRow(employeeNo, date, in, out);
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-attendance-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        OaExpenseServiceTest.createTables(jdbc);
        OaLeaveServiceTest.createLeaveTables(jdbc);
        createAttendanceTables(jdbc);
        OaExpenseServiceTest.seedAccounts(jdbc);
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (105, 101, 'eve', 'Eve', 10, 1, 0)");
        jdbc.execute("ALTER TABLE oa_employee ADD COLUMN employee_no VARCHAR(64)");
        for (long userId = 101; userId <= 105; userId++) {
            jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, employee_no, employment_status, deleted) VALUES (?, 101, ?, ?, ?, 0)", userId, userId, "E" + userId, userId == 105
                ? "LEFT"
                : "ACTIVE");
        }
        jdbc.update("""
            INSERT INTO oa_leave_type
              (tenant_id, code, name, balance_required, max_days_per_request, status, sort, version, create_user, create_time)
            VALUES (101, 'SICK', '病假', FALSE, NULL, 'ENABLED', 1, 0, 1, CURRENT_TIMESTAMP)
            """);

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-attendance")
            .tenantId(Long.toString(TENANT))
            .addString("correction.bpmn20.xml", OaAttendanceService.CORRECTION_BPMN)
            .addString("overtime.bpmn20.xml", OaAttendanceService.OVERTIME_BPMN)
            .addString("outing.bpmn20.xml", OaAttendanceService.OUTING_BPMN)
            .addString("leave.bpmn20.xml", OaLeaveService.TEMPLATE_BPMN)
            .addString("travel.bpmn20.xml", OaTravelService.TRAVEL_BPMN)
            .deploy();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        StubStaffDirectory directory = new StubStaffDirectory();

        OaAttendanceRepository attendanceRepository = new OaAttendanceRepository(jdbc);
        OaLeaveRepository leaveRepository = new OaLeaveRepository(jdbc);
        OaTravelRepository travelRepository = new OaTravelRepository(jdbc);
        OaLeaveHook leaveHook = new OaLeaveHook(leaveRepository, attendanceRepository);
        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaBusinessHooks hooks = new OaBusinessHooks(List
            .of(new OaCorrectionHook(attendanceRepository), new OaOvertimeHook(attendanceRepository), new OaOutingHook(attendanceRepository), leaveHook, new OaTravelHook(travelRepository)), launchRepository);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaAttendanceService service = new OaAttendanceService(attendanceRepository, null, null, objectMapper, transactionManager);
        OaLeaveService leaveService = new OaLeaveService(leaveRepository, leaveHook, null, null, objectMapper, transactionManager);
        OaTravelService travelService = new OaTravelService(travelRepository, null, null, null, outboxService, objectMapper, transactionManager);

        seedRelease(jdbc, repositoryService, 1, CORRECTION_RELEASE, OaAttendanceService.CORRECTION_MODEL_KEY, OaCorrectionHook.BUSINESS_TYPE, OaAttendanceService.CORRECTION_BPMN, service
            .correctionSchema());
        seedRelease(jdbc, repositoryService, 2, OVERTIME_RELEASE, OaAttendanceService.OVERTIME_MODEL_KEY, OaOvertimeHook.BUSINESS_TYPE, OaAttendanceService.OVERTIME_BPMN, service
            .overtimeSchema());
        seedRelease(jdbc, repositoryService, 3, OUTING_RELEASE, OaAttendanceService.OUTING_MODEL_KEY, OaOutingHook.BUSINESS_TYPE, OaAttendanceService.OUTING_BPMN, service
            .outingSchema());
        seedRelease(jdbc, repositoryService, 4, LEAVE_RELEASE, OaLeaveService.TEMPLATE_MODEL_KEY, OaLeaveHook.BUSINESS_TYPE, OaLeaveService.TEMPLATE_BPMN, leaveService
            .templateSchema(leaveService.types(TENANT, true)));
        seedRelease(jdbc, repositoryService, 5, TRAVEL_RELEASE, OaTravelService.TRAVEL_MODEL_KEY, OaTravelHook.BUSINESS_TYPE, OaTravelService.TRAVEL_BPMN, travelService
            .travelSchema());

        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, context
            .getBean(org.flowable.engine.HistoryService.class), repositoryService);
        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, hooks, service, launchService, approvalService, runtimeService, taskService);
    }

    private static void seedRelease(JdbcTemplate jdbc,
                                    RepositoryService repositoryService,
                                    long modelId,
                                    long releaseId,
                                    String processKey,
                                    String businessType,
                                    String bpmn,
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
            VALUES (?, 101, ?, ?, 'hr', '说明', 'PUBLISHED', ?, ?, 1, ?, ?, 0, 7, CURRENT_TIMESTAMP, 0)
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
            VALUES (?, 101, ?, 1, ?, ?, 4, 7, CURRENT_TIMESTAMP)
            """, versionId, formId, schema, "b".repeat(64));
    }

    /** 员工目录桩：101–105 在职，101 的直属上级是 102，HR 岗位只有 103，FINANCE 岗位只有 104。 */
    private static final class StubStaffDirectory implements OaStaffDirectory {

        @Override
        public boolean activeEmployee(long tenantId, long userId) {
            return tenantId == TENANT && userId >= 101 && userId <= 105;
        }

        @Override
        public Long departmentId(long tenantId, long userId) {
            return userId == HR || userId == FINANCE ? 20L : 10L;
        }

        @Override
        public Long supervisorId(long tenantId, long userId) {
            return userId == APPLICANT ? SUPERVISOR : null;
        }

        @Override
        public boolean enabledPosition(long tenantId, String code) {
            return tenantId == TENANT && (OaAttendanceHookSupport.HR_POSITION
                .equals(code) || OaTravelHook.FINANCE_POSITION.equals(code));
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            if (OaAttendanceHookSupport.HR_POSITION.equals(code)) {
                return List.of(HR);
            }
            return OaTravelHook.FINANCE_POSITION.equals(code) ? List.of(FINANCE) : List.of();
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(SUPERVISOR);
        }
    }

    private record Fixture(JdbcTemplate jdbc, OaBusinessHooks hooks, OaAttendanceService service,
                           OaLaunchService launchService, OaApprovalService approvalService,
                           RuntimeService runtimeService, TaskService taskService) {

        private OaShiftResp dayShift() {
            return service.createShift(TENANT, 1, shift("DAY", "09:00", "18:00", null));
        }

        private OaAttendanceDailyResp daily(LocalDate date) {
            return service.daily(TENANT, APPLICANT, date, date).get(0);
        }

        private long submit(String requestId, long releaseId, String title, Map<String, Object> form) {
            return launchService.submit(TENANT, APPLICANT, releaseId, new OaLaunchSubmitReq(requestId, title, form, List
                .of())).applicationId();
        }

        private long submitCorrection(String requestId, String checkType, LocalDateTime time) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaCorrectionHook.FIELD_CHECK_TYPE, checkType);
            form.put(OaCorrectionHook.FIELD_CHECK_TIME, time.format(DATETIME));
            form.put("reason", "忘记打卡");
            return submit(requestId, CORRECTION_RELEASE, "补卡", form);
        }

        private long submitOvertime(String requestId, LocalDateTime start, LocalDateTime end) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaOvertimeHook.FIELD_START_TIME, start.format(DATETIME));
            form.put(OaOvertimeHook.FIELD_END_TIME, end.format(DATETIME));
            form.put("reason", "版本上线");
            return submit(requestId, OVERTIME_RELEASE, "加班", form);
        }

        private long submitOuting(String requestId, LocalDateTime start, LocalDateTime end) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaOutingHook.FIELD_DESTINATION, "客户现场");
            form.put(OaOutingHook.FIELD_START_TIME, start.format(DATETIME));
            form.put(OaOutingHook.FIELD_END_TIME, end.format(DATETIME));
            form.put("reason", "现场支持");
            return submit(requestId, OUTING_RELEASE, "外出", form);
        }

        private long submitLeave(String requestId, LocalDate start, String startHalf, LocalDate end, String endHalf) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaLeaveHook.FIELD_TYPE, "SICK");
            form.put(OaLeaveHook.FIELD_START_DATE, start.toString());
            form.put(OaLeaveHook.FIELD_START_HALF, startHalf);
            form.put(OaLeaveHook.FIELD_END_DATE, end.toString());
            form.put(OaLeaveHook.FIELD_END_HALF, endHalf);
            form.put("reason", "看病");
            return submit(requestId, LEAVE_RELEASE, "请假", form);
        }

        private long submitTravel(String requestId, LocalDate start, LocalDate end) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaTravelHook.FIELD_DESTINATION, "上海");
            form.put(OaTravelHook.FIELD_START_DATE, start.toString());
            form.put(OaTravelHook.FIELD_END_DATE, end.toString());
            form.put(OaTravelHook.FIELD_BUDGET, new BigDecimal("500"));
            form.put("reason", "客户拜访");
            return submit(requestId, TRAVEL_RELEASE, "出差", form);
        }

        private void approve(long userId, long applicationId, String requestId) {
            approvalService.approve(TENANT, userId, task(applicationId).getId(), action(requestId));
        }

        private Task task(long applicationId) {
            Task task = taskService.createTaskQuery().processInstanceId(instanceId(applicationId)).singleResult();
            assertThat(task).isNotNull();
            return task;
        }

        private String instanceId(long applicationId) {
            return jdbc
                .queryForObject("SELECT process_instance_id FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private Object variable(long applicationId, String name) {
            return runtimeService.getVariable(instanceId(applicationId), name);
        }

        private String applicationStatus(long applicationId) {
            return jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private String requestStatus(long applicationId) {
            return jdbc
                .queryForObject("SELECT status FROM oa_attendance_request WHERE application_id = ?", String.class, applicationId);
        }

        private LocalDateTime checkIn(long userId, LocalDate date) {
            return jdbc
                .queryForObject("SELECT check_in FROM oa_attendance_record WHERE user_id = ? AND work_date = ?", LocalDateTime.class, userId, date);
        }

        private LocalDateTime checkOut(long userId, LocalDate date) {
            return jdbc
                .queryForObject("SELECT check_out FROM oa_attendance_record WHERE user_id = ? AND work_date = ?", LocalDateTime.class, userId, date);
        }

        private void application(long id, String businessType, String status) {
            jdbc.update("""
                INSERT INTO oa_application
                  (id, tenant_id, business_type, business_key, title, applicant_id, applicant_name, release_id,
                   status, create_user, create_time, deleted)
                VALUES (?, 101, ?, ?, ?, 101, 'Alice', 10, ?, 101, CURRENT_TIMESTAMP, 0)
                """, id, businessType, "seed-" + id, businessType + "-" + id, status);
        }

        private void request(long applicationId,
                             String kind,
                             LocalDateTime start,
                             LocalDateTime end,
                             String hours,
                             String checkType,
                             String destination,
                             String status) {
            application(applicationId, kind, "APPROVED".equals(status) ? "APPROVED" : "WITHDRAWN");
            jdbc.update("""
                INSERT INTO oa_attendance_request
                  (tenant_id, application_id, applicant_id, kind, work_date, start_time, end_time, hours, check_type,
                   destination, status, create_time)
                VALUES (101, ?, 101, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, applicationId, kind, start.toLocalDate(), Timestamp.valueOf(start), Timestamp
                .valueOf(end), new BigDecimal(hours), checkType, destination, status);
        }

        private long count(String sql) {
            return jdbc.queryForObject(sql, Long.class);
        }
    }
}
