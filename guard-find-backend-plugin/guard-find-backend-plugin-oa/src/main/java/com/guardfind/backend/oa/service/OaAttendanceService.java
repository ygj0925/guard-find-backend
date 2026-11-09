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
import com.guardfind.backend.oa.attendance.OaAttendanceHookSupport;
import com.guardfind.backend.oa.attendance.OaCorrectionHook;
import com.guardfind.backend.oa.attendance.OaOutingHook;
import com.guardfind.backend.oa.attendance.OaOvertimeHook;
import com.guardfind.backend.oa.model.OaAttendanceDailyResp;
import com.guardfind.backend.oa.model.OaAttendanceImportReq;
import com.guardfind.backend.oa.model.OaAttendanceImportResp;
import com.guardfind.backend.oa.model.OaAttendanceImportRow;
import com.guardfind.backend.oa.model.OaAttendanceRecordResp;
import com.guardfind.backend.oa.model.OaAttendanceRequestResp;
import com.guardfind.backend.oa.model.OaAttendanceSummaryResp;
import com.guardfind.backend.oa.model.OaAttendanceTemplateResp;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaScheduleResp;
import com.guardfind.backend.oa.model.OaScheduleSaveReq;
import com.guardfind.backend.oa.model.OaShiftReq;
import com.guardfind.backend.oa.model.OaShiftResp;
import com.guardfind.backend.oa.repository.OaAttendanceRepository;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.DateSpan;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.ImportReplay;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.LeaveSpan;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.RecordRow;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.RequestRow;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.ScheduleRow;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.ShiftRow;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.UserInfo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 假勤管理：班次、排班、考勤记录受控导入、日报状态计算、月度汇总、补卡/加班/外出申请台账与模板初始化。
 *
 * <p>
 * 日报状态优先级：未排班/休息 → 出差 → 请假 → 打卡判定（缺卡、迟到、早退、正常）；已通过的外出覆盖班次时段时，
 * 打卡异常显示为外出。当天及以后的缺卡记为待打卡。班次不跨天，半天请假不拆分当日状态。
 * </p>
 */
@Service
public class OaAttendanceService {

    public static final String CORRECTION_FORM_KEY = "attendance_correction";
    public static final String CORRECTION_MODEL_KEY = "attendance_correction";
    public static final String OVERTIME_FORM_KEY = "overtime_request";
    public static final String OVERTIME_MODEL_KEY = "overtime_request";
    public static final String OUTING_FORM_KEY = "outing_request";
    public static final String OUTING_MODEL_KEY = "outing_request";

    public static final String SOURCE_IMPORT = "IMPORT";
    public static final int MAX_RANGE_DAYS = 62;
    public static final int MAX_IMPORT_BACKDATE_DAYS = 90;
    public static final int MAX_SUMMARY_USERS = 500;
    public static final int MAX_IMPORT_ERRORS = 100;

    public static final String STATUS_NORMAL = "NORMAL";
    public static final String STATUS_LATE = "LATE";
    public static final String STATUS_EARLY = "EARLY";
    public static final String STATUS_MISSING_IN = "MISSING_IN";
    public static final String STATUS_MISSING_OUT = "MISSING_OUT";
    public static final String STATUS_ABSENT = "ABSENT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_LEAVE = "LEAVE";
    public static final String STATUS_TRAVEL = "TRAVEL";
    public static final String STATUS_OUTING = "OUTING";
    public static final String STATUS_REST = "REST";
    public static final String STATUS_UNSCHEDULED = "UNSCHEDULED";

    private static final Set<String> ABNORMAL = Set
        .of(STATUS_LATE, STATUS_EARLY, STATUS_MISSING_IN, STATUS_MISSING_OUT, STATUS_ABSENT);
    private static final Set<String> KINDS = Set
        .of(OaAttendanceRepository.KIND_CORRECTION, OaAttendanceRepository.KIND_OVERTIME, OaAttendanceRepository.KIND_OUTING);
    private static final Set<String> REQUEST_STATUSES = Set
        .of(OaAttendanceHookSupport.STATUS_APPROVING, OaAttendanceHookSupport.STATUS_APPROVED, OaAttendanceHookSupport.STATUS_CLOSED);
    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final OaAttendanceRepository repository;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaAttendanceService(OaAttendanceRepository repository,
                               OaFormService formService,
                               OaProcessModelService modelService,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.formService = formService;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaShiftResp> shifts(long tenantId, String status) {
        if (status != null && !status.isBlank() && !Set.of("ENABLED", "DISABLED").contains(status)) {
            throw new BusinessException("班次状态无效: " + status);
        }
        return repository.shifts(tenantId, status == null || status.isBlank() ? null : status);
    }

    public OaShiftResp createShift(long tenantId, long userId, OaShiftReq req) {
        if (req.code() == null || req.code().isBlank()) {
            throw new BusinessException("班次编码不能为空");
        }
        LocalTime start = LocalTime.parse(req.startTime());
        LocalTime end = shiftEnd(start, req.endTime());
        long id;
        try {
            id = repository.insertShift(tenantId, req.code().trim(), req.name()
                .trim(), start, end, grace(req), status(req.status()), blankToNull(req.remark()), userId, LocalDateTime
                    .now());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("班次编码已存在: " + req.code().trim());
        }
        return repository.shift(tenantId, id);
    }

    public OaShiftResp updateShift(long tenantId, long userId, long id, OaShiftReq req) {
        ShiftRow existing = repository.shiftRow(tenantId, id);
        if (existing == null) {
            throw new BusinessException("班次不存在");
        }
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        LocalTime start = LocalTime.parse(req.startTime());
        LocalTime end = shiftEnd(start, req.endTime());
        if (repository.updateShift(tenantId, id, req.name().trim(), start, end, grace(req), status(req
            .status()), blankToNull(req.remark()), req.version(), userId, LocalDateTime.now()) != 1) {
            throw new BusinessException("班次已被他人修改，请刷新后重试");
        }
        return repository.shift(tenantId, id);
    }

    public List<OaScheduleResp> schedules(long tenantId, LocalDate start, LocalDate end, Long userId) {
        requireRange(start, end);
        return repository.schedules(tenantId, start, end, userId);
    }

    /**
     * 批量排班：区间内按星期过滤，为每名在职员工写入（或清除）当天班次；同一员工同一天只保留一条。
     */
    public int saveSchedules(long tenantId, long operatorId, OaScheduleSaveReq req) {
        requireRange(req.startDate(), req.endDate());
        Set<Integer> weekdays = new HashSet<>();
        if (req.weekdays() != null) {
            for (Integer weekday : req.weekdays()) {
                if (weekday == null || weekday < 1 || weekday > 7) {
                    throw new BusinessException("星期只能是 1–7");
                }
                weekdays.add(weekday);
            }
        }
        Set<Long> userIds = new TreeSet<>(req.userIds());
        boolean clear = Boolean.TRUE.equals(req.clear());
        return transactionTemplate.execute(status -> {
            Set<Long> active = repository.activeUserIds(tenantId, userIds);
            List<Long> missing = userIds.stream().filter(id -> !active.contains(id)).toList();
            if (!missing.isEmpty()) {
                throw new BusinessException("以下员工不存在或不在职: " + missing);
            }
            if (!clear) {
                if (req.shiftId() == null) {
                    throw new BusinessException("请选择班次");
                }
                ShiftRow shift = repository.shiftRow(tenantId, req.shiftId());
                if (shift == null || !"ENABLED".equals(shift.status())) {
                    throw new BusinessException("班次不存在或已停用");
                }
            }
            LocalDateTime now = LocalDateTime.now();
            int affected = 0;
            for (LocalDate date = req.startDate(); !date.isAfter(req.endDate()); date = date.plusDays(1)) {
                if (!weekdays.isEmpty() && !weekdays.contains(date.getDayOfWeek().getValue())) {
                    continue;
                }
                for (Long userId : userIds) {
                    affected += clear
                        ? repository.deleteSchedule(tenantId, userId, date)
                        : upsertSchedule(tenantId, userId, date, req.shiftId(), operatorId, now);
                }
            }
            return affected;
        });
    }

    public List<OaAttendanceRecordResp> records(long tenantId,
                                                LocalDate start,
                                                LocalDate end,
                                                Long userId,
                                                int page,
                                                int size) {
        requireRange(start, end);
        int safeSize = safeSize(size);
        return repository.records(tenantId, start, end, userId, offset(page, safeSize), safeSize);
    }

    public long countRecords(long tenantId, LocalDate start, LocalDate end, Long userId) {
        requireRange(start, end);
        return repository.countRecords(tenantId, start, end, userId);
    }

    /**
     * 预检导入数据：返回逐行错误，或在无错误时返回将新增/更新/因补卡保留的数量，不写库。
     */
    public OaAttendanceImportResp previewImport(long tenantId, OaAttendanceImportReq req) {
        Validated validated = validate(tenantId, req.rows());
        if (!validated.errors().isEmpty()) {
            return invalid(req, validated.errors());
        }
        int inserted = 0;
        int updated = 0;
        int kept = 0;
        for (ImportRow row : validated.rows()) {
            Merge merge = merge(repository.record(tenantId, row.userId(), row.workDate()), row);
            inserted += merge.insert() ? 1 : 0;
            updated += !merge.insert() && merge.changed() ? 1 : 0;
            kept += merge.kept();
        }
        return new OaAttendanceImportResp(true, false, null, req.rows().size(), inserted, updated, kept, List.of());
    }

    /**
     * 提交导入：同一请求号只生效一次（内容不同则拒绝）；任一行有错整批拒绝；已补卡的一侧保留补卡结果。
     */
    public OaAttendanceImportResp commitImport(long tenantId, long operatorId, OaAttendanceImportReq req) {
        if (req.requestId() == null || req.requestId().isBlank()) {
            throw new BusinessException("缺少请求号");
        }
        String requestId = req.requestId().trim();
        String digest = payloadDigest(req.rows());
        return transactionTemplate.execute(status -> {
            OaAttendanceImportResp replay = replay(repository.importByRequest(tenantId, requestId, false), digest);
            if (replay != null) {
                return replay;
            }
            Validated validated = validate(tenantId, req.rows());
            if (!validated.errors().isEmpty()) {
                return invalid(req, validated.errors());
            }
            LocalDateTime now = LocalDateTime.now();
            long importId;
            try {
                importId = repository.insertImport(tenantId, requestId, digest, blankToNull(req.fileName()), req.rows()
                    .size(), operatorId, now);
            } catch (DuplicateKeyException e) {
                return replay(repository.importByRequest(tenantId, requestId, true), digest);
            }
            int inserted = 0;
            int updated = 0;
            int kept = 0;
            for (ImportRow row : validated.rows()) {
                Merge merge = writeRow(tenantId, row, importId, now);
                inserted += merge.insert() ? 1 : 0;
                updated += !merge.insert() && merge.changed() ? 1 : 0;
                kept += merge.kept();
            }
            repository.finishImport(tenantId, importId, inserted, updated, kept);
            return new OaAttendanceImportResp(true, false, importId, req.rows().size(), inserted, updated, kept, List
                .of());
        });
    }

    public List<OaAttendanceDailyResp> daily(long tenantId, long userId, LocalDate start, LocalDate end) {
        requireRange(start, end);
        List<Long> users = List.of(userId);
        Map<Long, UserInfo> infos = repository.users(tenantId, users);
        return compute(tenantId, users, infos, start, end).getOrDefault(userId, List.of());
    }

    public List<OaAttendanceSummaryResp> summary(long tenantId, YearMonth month, String keyword) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        List<Long> users = repository.attendanceUsers(tenantId, start, end, keyword, MAX_SUMMARY_USERS);
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, UserInfo> infos = repository.users(tenantId, users);
        Map<Long, List<OaAttendanceDailyResp>> days = compute(tenantId, users, infos, start, end);
        Map<Long, int[]> requestCounts = new HashMap<>();
        for (RequestRow request : repository.approvedRequests(tenantId, users, start, end)) {
            int[] counts = requestCounts.computeIfAbsent(request.applicantId(), id -> new int[2]);
            if (OaAttendanceRepository.KIND_OUTING.equals(request.kind())) {
                counts[0]++;
            } else if (OaAttendanceRepository.KIND_CORRECTION.equals(request.kind())) {
                counts[1]++;
            }
        }
        List<OaAttendanceSummaryResp> result = new ArrayList<>();
        for (Long userId : users) {
            int scheduled = 0;
            int normal = 0;
            int late = 0;
            int early = 0;
            int missing = 0;
            int absent = 0;
            int leave = 0;
            int travel = 0;
            BigDecimal overtime = BigDecimal.ZERO;
            for (OaAttendanceDailyResp day : days.getOrDefault(userId, List.of())) {
                overtime = overtime.add(day.overtimeHours());
                if (STATUS_REST.equals(day.status()) || STATUS_UNSCHEDULED.equals(day.status())) {
                    continue;
                }
                scheduled++;
                switch (day.status()) {
                    case STATUS_NORMAL -> normal++;
                    case STATUS_MISSING_IN, STATUS_MISSING_OUT -> missing++;
                    case STATUS_ABSENT -> absent++;
                    case STATUS_LEAVE -> leave++;
                    case STATUS_TRAVEL -> travel++;
                    default -> {
                    }
                }
                if (STATUS_LATE.equals(day.status()) || STATUS_EARLY.equals(day.status())) {
                    late += day.late() ? 1 : 0;
                    early += day.early() ? 1 : 0;
                }
            }
            int[] counts = requestCounts.getOrDefault(userId, new int[2]);
            UserInfo info = infos.get(userId);
            result.add(new OaAttendanceSummaryResp(userId, info == null ? null : info.name(), info == null
                ? null
                : info
                    .employeeNo(), scheduled, normal, late, early, missing, absent, leave, travel, counts[0], overtime, counts[1]));
        }
        return result;
    }

    public List<OaAttendanceRequestResp> requests(long tenantId,
                                                  String kind,
                                                  String status,
                                                  String keyword,
                                                  Long applicantId,
                                                  int page,
                                                  int size) {
        int safeSize = safeSize(size);
        return repository
            .requests(tenantId, checked(kind, KINDS, "申请类型"), checked(status, REQUEST_STATUSES, "申请单状态"), keyword, applicantId, offset(page, safeSize), safeSize);
    }

    public long countRequests(long tenantId, String kind, String status, String keyword, Long applicantId) {
        return repository
            .countRequests(tenantId, checked(kind, KINDS, "申请类型"), checked(status, REQUEST_STATUSES, "申请单状态"), keyword, applicantId);
    }

    /**
     * 初始化假勤试点：补卡、加班、外出三张已发布表单与对应业务类型的草稿流程模型（直属上级 → HR 岗位）。
     */
    public OaAttendanceTemplateResp template(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            long correctionFormId = publishForm(tenantId, userId, CORRECTION_FORM_KEY, "补卡申请", correctionSchema());
            OaProcessModelResp correction = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(CORRECTION_MODEL_KEY, "补卡申请", "hr", "直属上级 → HR 岗位审核；通过后写入当日考勤记录", CORRECTION_BPMN, OaCorrectionHook.BUSINESS_TYPE));
            long overtimeFormId = publishForm(tenantId, userId, OVERTIME_FORM_KEY, "加班申请", overtimeSchema());
            OaProcessModelResp overtime = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(OVERTIME_MODEL_KEY, "加班申请", "hr", "直属上级 → HR 岗位审核；通过后计入考勤汇总加班时长", OVERTIME_BPMN, OaOvertimeHook.BUSINESS_TYPE));
            long outingFormId = publishForm(tenantId, userId, OUTING_FORM_KEY, "外出申请", outingSchema());
            OaProcessModelResp outing = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(OUTING_MODEL_KEY, "外出申请", "hr", "直属上级 → HR 岗位审核；通过后外出时段的打卡异常显示为外出", OUTING_BPMN, OaOutingHook.BUSINESS_TYPE));
            return new OaAttendanceTemplateResp(correctionFormId, correction.id(), correction
                .modelKey(), overtimeFormId, overtime.id(), overtime.modelKey(), outingFormId, outing.id(), outing
                    .modelKey());
        });
    }

    public String correctionSchema() {
        return schema(List.of(field(OaCorrectionHook.FIELD_CHECK_TYPE, "补卡类型", "radio", true, List
            .of(option("上班", OaCorrectionHook.CHECK_IN), option("下班", OaCorrectionHook.CHECK_OUT))), field(OaCorrectionHook.FIELD_CHECK_TIME, "补卡时间", "datetime", true, null), field("reason", "补卡原因", "textarea", true, null)));
    }

    public String overtimeSchema() {
        return schema(List
            .of(field(OaOvertimeHook.FIELD_START_TIME, "开始时间", "datetime", true, null), field(OaOvertimeHook.FIELD_END_TIME, "结束时间", "datetime", true, null), field("reason", "加班事由", "textarea", true, null)));
    }

    public String outingSchema() {
        return schema(List
            .of(field(OaOutingHook.FIELD_DESTINATION, "外出地点", "text", true, null), field(OaOutingHook.FIELD_START_TIME, "开始时间", "datetime", true, null), field(OaOutingHook.FIELD_END_TIME, "结束时间", "datetime", true, null), field("reason", "外出事由", "textarea", true, null)));
    }

    private Map<Long, List<OaAttendanceDailyResp>> compute(long tenantId,
                                                           List<Long> userIds,
                                                           Map<Long, UserInfo> infos,
                                                           LocalDate start,
                                                           LocalDate end) {
        Map<String, ScheduleRow> schedules = new HashMap<>();
        for (ScheduleRow row : repository.scheduleRows(tenantId, userIds, start, end)) {
            schedules.put(key(row.userId(), row.workDate()), row);
        }
        Map<String, RecordRow> records = new HashMap<>();
        for (RecordRow row : repository.recordRows(tenantId, userIds, start, end)) {
            records.put(key(row.userId(), row.workDate()), row);
        }
        Map<Long, List<LeaveSpan>> leaves = new HashMap<>();
        for (LeaveSpan leave : repository.approvedLeaves(tenantId, userIds, start, end)) {
            leaves.computeIfAbsent(leave.userId(), id -> new ArrayList<>()).add(leave);
        }
        Map<Long, List<DateSpan>> travels = new HashMap<>();
        for (DateSpan travel : repository.approvedTravels(tenantId, userIds, start, end)) {
            travels.computeIfAbsent(travel.userId(), id -> new ArrayList<>()).add(travel);
        }
        Map<Long, List<RequestRow>> outings = new HashMap<>();
        Map<String, BigDecimal> overtime = new HashMap<>();
        int outingReach = (int)Math.ceil(OaOutingHook.MAX_HOURS.doubleValue() / 24);
        for (RequestRow request : repository.approvedRequests(tenantId, userIds, start.minusDays(outingReach), end)) {
            if (OaAttendanceRepository.KIND_OUTING.equals(request.kind())) {
                outings.computeIfAbsent(request.applicantId(), id -> new ArrayList<>()).add(request);
            } else if (OaAttendanceRepository.KIND_OVERTIME.equals(request.kind()) && !request.workDate()
                .isBefore(start)) {
                overtime.merge(key(request.applicantId(), request.workDate()), request.hours(), BigDecimal::add);
            }
        }
        LocalDate today = LocalDate.now();
        Map<Long, List<OaAttendanceDailyResp>> result = new LinkedHashMap<>();
        for (Long userId : userIds) {
            UserInfo info = infos.get(userId);
            String name = info == null ? null : info.name();
            List<OaAttendanceDailyResp> rows = new ArrayList<>();
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                String key = key(userId, date);
                rows.add(day(userId, name, date, today, schedules.get(key), records.get(key), leaves
                    .getOrDefault(userId, List.of()), travels.getOrDefault(userId, List.of()), outings
                        .getOrDefault(userId, List.of()), overtime.getOrDefault(key, BigDecimal.ZERO)));
            }
            result.put(userId, rows);
        }
        return result;
    }

    private static OaAttendanceDailyResp day(long userId,
                                             String name,
                                             LocalDate date,
                                             LocalDate today,
                                             ScheduleRow schedule,
                                             RecordRow record,
                                             List<LeaveSpan> leaves,
                                             List<DateSpan> travels,
                                             List<RequestRow> outings,
                                             BigDecimal overtimeHours) {
        LocalDateTime checkIn = record == null ? null : record.checkIn();
        LocalDateTime checkOut = record == null ? null : record.checkOut();
        String inSource = record == null ? null : record.checkInSource();
        String outSource = record == null ? null : record.checkOutSource();
        if (schedule == null || schedule.shiftId() == null) {
            String status = schedule == null ? STATUS_UNSCHEDULED : STATUS_REST;
            return new OaAttendanceDailyResp(userId, name, date, null, null, null, checkIn, checkOut, inSource, outSource, status, false, false, overtimeHours, null);
        }
        String shiftStart = schedule.start().toString().substring(0, 5);
        String shiftEnd = schedule.end().toString().substring(0, 5);
        for (DateSpan travel : travels) {
            if (travel.covers(date)) {
                return new OaAttendanceDailyResp(userId, name, date, schedule
                    .shiftName(), shiftStart, shiftEnd, checkIn, checkOut, inSource, outSource, STATUS_TRAVEL, false, false, overtimeHours, "出差：" + travel
                        .label());
            }
        }
        for (LeaveSpan leave : leaves) {
            if (!date.isBefore(leave.startDate()) && !date.isAfter(leave.endDate())) {
                return new OaAttendanceDailyResp(userId, name, date, schedule
                    .shiftName(), shiftStart, shiftEnd, checkIn, checkOut, inSource, outSource, STATUS_LEAVE, false, false, overtimeHours, "请假：" + leave
                        .typeName());
            }
        }
        LocalDateTime windowStart = date.atTime(schedule.start());
        LocalDateTime windowEnd = date.atTime(schedule.end());
        boolean late = checkIn != null && checkIn.isAfter(windowStart.plusMinutes(schedule.graceMinutes()));
        boolean early = checkOut != null && checkOut.isBefore(windowEnd);
        boolean past = date.isBefore(today);
        String status;
        if (checkIn == null && checkOut == null) {
            status = past ? STATUS_ABSENT : STATUS_PENDING;
        } else if (checkIn == null || checkOut == null) {
            status = !past ? STATUS_PENDING : checkIn == null ? STATUS_MISSING_IN : STATUS_MISSING_OUT;
        } else if (late) {
            status = STATUS_LATE;
        } else if (early) {
            status = STATUS_EARLY;
        } else {
            status = STATUS_NORMAL;
        }
        String note = null;
        if (ABNORMAL.contains(status) || STATUS_PENDING.equals(status)) {
            for (RequestRow outing : outings) {
                if (outing.start().isBefore(windowEnd) && outing.end().isAfter(windowStart)) {
                    status = STATUS_OUTING;
                    note = "外出：" + outing.destination();
                    break;
                }
            }
        }
        return new OaAttendanceDailyResp(userId, name, date, schedule
            .shiftName(), shiftStart, shiftEnd, checkIn, checkOut, inSource, outSource, status, late, early, overtimeHours, note);
    }

    private Validated validate(long tenantId, List<OaAttendanceImportRow> rows) {
        Set<String> employeeNos = new HashSet<>();
        for (OaAttendanceImportRow row : rows) {
            if (row != null && row.employeeNo() != null && !row.employeeNo().isBlank()) {
                employeeNos.add(row.employeeNo().trim());
            }
        }
        Map<String, Long> employees = repository.activeEmployeesByNo(tenantId, employeeNos);
        LocalDate today = LocalDate.now();
        List<String> errors = new ArrayList<>();
        List<ImportRow> valid = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int index = 0; index < rows.size() && errors.size() < MAX_IMPORT_ERRORS; index++) {
            int line = index + 1;
            OaAttendanceImportRow row = rows.get(index);
            if (row == null) {
                errors.add("第 " + line + " 行：空行");
                continue;
            }
            String employeeNo = row.employeeNo() == null ? "" : row.employeeNo().trim();
            Long userId = employees.get(employeeNo);
            if (employeeNo.isEmpty()) {
                errors.add("第 " + line + " 行：缺少工号");
                continue;
            }
            if (userId == null) {
                errors.add("第 " + line + " 行：工号 " + employeeNo + " 不存在或不在职");
                continue;
            }
            LocalDate workDate = parseDate(row.workDate());
            if (workDate == null) {
                errors.add("第 " + line + " 行：日期格式必须是 YYYY-MM-DD");
                continue;
            }
            if (workDate.isAfter(today)) {
                errors.add("第 " + line + " 行：日期不能晚于今天");
                continue;
            }
            if (workDate.isBefore(today.minusDays(MAX_IMPORT_BACKDATE_DAYS))) {
                errors.add("第 " + line + " 行：只能导入 " + MAX_IMPORT_BACKDATE_DAYS + " 天内的记录");
                continue;
            }
            String inText = blankToNull(row.checkIn());
            String outText = blankToNull(row.checkOut());
            if (inText == null && outText == null) {
                errors.add("第 " + line + " 行：上班与下班时间至少填一个");
                continue;
            }
            if (inText != null && !HH_MM.matcher(inText).matches() || outText != null && !HH_MM.matcher(outText)
                .matches()) {
                errors.add("第 " + line + " 行：时间格式必须是 HH:mm");
                continue;
            }
            LocalDateTime checkIn = inText == null ? null : workDate.atTime(LocalTime.parse(inText));
            LocalDateTime checkOut = outText == null ? null : workDate.atTime(LocalTime.parse(outText));
            if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
                errors.add("第 " + line + " 行：下班时间必须晚于上班时间");
                continue;
            }
            Integer previous = seen.putIfAbsent(key(userId, workDate), line);
            if (previous != null) {
                errors.add("第 " + line + " 行：与第 " + previous + " 行是同一员工同一天");
                continue;
            }
            valid.add(new ImportRow(userId, workDate, checkIn, checkOut));
        }
        return new Validated(valid, errors);
    }

    private Merge writeRow(long tenantId, ImportRow row, long importId, LocalDateTime now) {
        RecordRow existing = repository.lockRecord(tenantId, row.userId(), row.workDate());
        Merge merge = merge(existing, row);
        if (merge.insert()) {
            try {
                repository.insertRecord(tenantId, row.userId(), row.workDate(), merge.checkIn(), merge.checkOut(), merge
                    .inSource(), merge.outSource(), importId, now);
                return merge;
            } catch (DuplicateKeyException e) {
                existing = repository.lockRecord(tenantId, row.userId(), row.workDate());
                merge = merge(existing, row);
            }
        }
        if (merge.changed()) {
            repository.updateRecord(tenantId, existing.id(), merge.checkIn(), merge.checkOut(), merge.inSource(), merge
                .outSource(), importId, now);
        }
        return merge;
    }

    private static Merge merge(RecordRow existing, ImportRow row) {
        if (existing == null) {
            return new Merge(true, true, 0, row.checkIn(), row.checkOut(), row.checkIn() == null
                ? null
                : SOURCE_IMPORT, row.checkOut() == null ? null : SOURCE_IMPORT);
        }
        LocalDateTime checkIn = existing.checkIn();
        LocalDateTime checkOut = existing.checkOut();
        String inSource = existing.checkInSource();
        String outSource = existing.checkOutSource();
        boolean changed = false;
        int kept = 0;
        if (row.checkIn() != null) {
            if (OaCorrectionHook.SOURCE_CORRECTION.equals(inSource)) {
                kept++;
            } else {
                checkIn = row.checkIn();
                inSource = SOURCE_IMPORT;
                changed = true;
            }
        }
        if (row.checkOut() != null) {
            if (OaCorrectionHook.SOURCE_CORRECTION.equals(outSource)) {
                kept++;
            } else {
                checkOut = row.checkOut();
                outSource = SOURCE_IMPORT;
                changed = true;
            }
        }
        return new Merge(false, changed, kept, checkIn, checkOut, inSource, outSource);
    }

    private int upsertSchedule(long tenantId,
                               long userId,
                               LocalDate date,
                               long shiftId,
                               long operatorId,
                               LocalDateTime now) {
        if (repository.updateSchedule(tenantId, userId, date, shiftId, now) == 1) {
            return 1;
        }
        try {
            repository.insertSchedule(tenantId, userId, date, shiftId, operatorId, now);
            return 1;
        } catch (DuplicateKeyException e) {
            return repository.updateSchedule(tenantId, userId, date, shiftId, now);
        }
    }

    private static OaAttendanceImportResp invalid(OaAttendanceImportReq req, List<String> errors) {
        return new OaAttendanceImportResp(false, false, null, req.rows().size(), 0, 0, 0, errors);
    }

    private static LocalDate parseDate(String text) {
        if (text == null || !DATE.matcher(text.trim()).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime shiftEnd(LocalTime start, String endText) {
        LocalTime end = LocalTime.parse(endText);
        if (!end.isAfter(start)) {
            throw new BusinessException("下班时间必须晚于上班时间（暂不支持跨天班次）");
        }
        return end;
    }

    private static int grace(OaShiftReq req) {
        return req.graceMinutes() == null ? 0 : req.graceMinutes();
    }

    private static String status(String status) {
        return status == null || status.isBlank() ? "ENABLED" : status;
    }

    private static void requireRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BusinessException("请选择日期区间");
        }
        if (end.isBefore(start)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException("日期区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }
    }

    private long publishForm(long tenantId, long userId, String key, String name, String schema) {
        OaFormResp form = formService
            .create(tenantId, userId, new OaFormCreateReq(key, name, name + "试点模板，业务字段 key 不可修改", schema));
        OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
        if (!published.valid()) {
            throw new BusinessException(name + "表单发布失败：" + String.join("；", published.errors()));
        }
        return form.id();
    }

    private String schema(List<Map<String, Object>> fields) {
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("模板 schema 序列化失败", e);
        }
    }

    private static Map<String, Object> field(String key,
                                             String label,
                                             String type,
                                             boolean required,
                                             List<Map<String, Object>> options) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", "f_" + key);
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        if (options != null) {
            field.put("options", options);
        }
        return field;
    }

    private static Map<String, Object> option(String label, String value) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("label", label);
        option.put("value", value);
        return option;
    }

    private static String key(long userId, LocalDate date) {
        return userId + "@" + date;
    }

    private static String checked(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw new BusinessException(label + "无效: " + value);
        }
        return value;
    }

    private static int safeSize(int size) {
        return Math.min(100, Math.max(1, size));
    }

    private static int offset(int page, int size) {
        return (Math.max(1, page) - 1) * size;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static OaAttendanceImportResp replay(ImportReplay existing, String digest) {
        if (existing == null) {
            return null;
        }
        if (!existing.payloadDigest().equals(digest)) {
            throw new BusinessException("请求号已被使用");
        }
        return existing.result();
    }

    private static String payloadDigest(List<OaAttendanceImportRow> rows) {
        StringBuilder text = new StringBuilder();
        for (OaAttendanceImportRow row : rows) {
            text.append(trimToEmpty(row.employeeNo()))
                .append('\u001f')
                .append(trimToEmpty(row.workDate()))
                .append('\u001f')
                .append(trimToEmpty(row.checkIn()))
                .append('\u001f')
                .append(trimToEmpty(row.checkOut()))
                .append('\u001e');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record ImportRow(long userId, LocalDate workDate, LocalDateTime checkIn, LocalDateTime checkOut) {
    }

    private record Validated(List<ImportRow> rows, List<String> errors) {
    }

    private record Merge(boolean insert, boolean changed, int kept, LocalDateTime checkIn, LocalDateTime checkOut,
                         String inSource, String outSource) {
    }

    private static String bpmn(String processId, String name, String hrTaskName) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                              xmlns:flowable="http://flowable.org/bpmn"
                              id="Definitions_%1$s" targetNamespace="http://guardfind.com/oa/bpmn">
              <bpmn:process id="%1$s" name="%2$s" isExecutable="true">
                <bpmn:startEvent id="start" name="提交">
                  <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
                </bpmn:startEvent>
                <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
                  <bpmn:incoming>flowToSupervisor</bpmn:incoming>
                  <bpmn:outgoing>flowToHr</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:userTask id="hrApprove" name="%3$s" flowable:candidateUsers="${hrReviewer}">
                  <bpmn:incoming>flowToHr</bpmn:incoming>
                  <bpmn:outgoing>flowToEnd</bpmn:outgoing>
                </bpmn:userTask>
                <bpmn:endEvent id="end" name="结束">
                  <bpmn:incoming>flowToEnd</bpmn:incoming>
                </bpmn:endEvent>
                <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
                <bpmn:sequenceFlow id="flowToHr" sourceRef="supervisorApprove" targetRef="hrApprove"/>
                <bpmn:sequenceFlow id="flowToEnd" sourceRef="hrApprove" targetRef="end"/>
              </bpmn:process>
              <bpmndi:BPMNDiagram id="BPMNDiagram_%1$s">
                <bpmndi:BPMNPlane id="BPMNPlane_%1$s" bpmnElement="%1$s">
                  <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                    <dc:Bounds x="152" y="102" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                    <dc:Bounds x="240" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="hrApprove_di" bpmnElement="hrApprove">
                    <dc:Bounds x="400" y="80" width="100" height="80"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                    <dc:Bounds x="562" y="102" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                    <di:waypoint x="188" y="120"/>
                    <di:waypoint x="240" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="flowToHr_di" bpmnElement="flowToHr">
                    <di:waypoint x="340" y="120"/>
                    <di:waypoint x="400" y="120"/>
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="flowToEnd_di" bpmnElement="flowToEnd">
                    <di:waypoint x="500" y="120"/>
                    <di:waypoint x="562" y="120"/>
                  </bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </bpmn:definitions>
            """.formatted(processId, name, hrTaskName);
    }

    public static final String CORRECTION_BPMN = bpmn(CORRECTION_MODEL_KEY, "补卡申请", "HR 审核");
    public static final String OVERTIME_BPMN = bpmn(OVERTIME_MODEL_KEY, "加班申请", "HR 审核");
    public static final String OUTING_BPMN = bpmn(OUTING_MODEL_KEY, "外出申请", "HR 审核");
}
