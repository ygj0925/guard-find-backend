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

package com.guardfind.backend.oa.repository;

import com.guardfind.backend.oa.model.OaAttendanceImportResp;
import com.guardfind.backend.oa.model.OaAttendanceRecordResp;
import com.guardfind.backend.oa.model.OaAttendanceRequestResp;
import com.guardfind.backend.oa.model.OaScheduleResp;
import com.guardfind.backend.oa.model.OaShiftResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 假勤（班次、排班、考勤记录、导入批次与补卡/加班/外出申请单）的显式租户 SQL。
 *
 * <p>
 * 重叠与次数检查用逐行 FOR UPDATE 读取：锁定读看到其他事务已提交的最新行，避免 MySQL 可重复读快照漏判；
 * 加锁查询不带 JOIN 以兼容 H2。
 * </p>
 */
@Repository
public class OaAttendanceRepository {

    public static final String KIND_CORRECTION = "CORRECTION";
    public static final String KIND_OVERTIME = "OVERTIME";
    public static final String KIND_OUTING = "OUTING";

    private static final String SHIFT_COLUMNS = "id, code, name, start_time, end_time, grace_minutes, status, remark, version, update_time";

    private static final RowMapper<OaShiftResp> SHIFT_MAPPER = (rs, rowNum) -> new OaShiftResp(rs.getLong("id"), rs
        .getString("code"), rs.getString("name"), hhmm(rs, "start_time"), hhmm(rs, "end_time"), rs
            .getInt("grace_minutes"), rs.getString("status"), rs.getString("remark"), rs
                .getInt("version"), timestamp(rs, "update_time"));

    private static final RowMapper<ShiftRow> SHIFT_ROW_MAPPER = (rs, rowNum) -> new ShiftRow(rs.getLong("id"), rs
        .getString("code"), rs.getString("name"), time(rs, "start_time"), time(rs, "end_time"), rs
            .getInt("grace_minutes"), rs.getString("status"), rs.getInt("version"));

    private static final String SCHEDULE_SELECT = """
        SELECT s.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, s.work_date, s.shift_id,
               f.code AS shift_code, f.name AS shift_name, f.start_time, f.end_time, f.grace_minutes
          FROM oa_schedule s
          LEFT JOIN oa_shift f ON f.tenant_id = s.tenant_id AND f.id = s.shift_id
          LEFT JOIN sys_user u ON u.tenant_id = s.tenant_id AND u.id = s.user_id
         WHERE s.tenant_id = ? AND s.work_date >= ? AND s.work_date <= ?
        """;

    private static final RowMapper<OaScheduleResp> SCHEDULE_MAPPER = (rs, rowNum) -> new OaScheduleResp(rs
        .getLong("user_id"), rs.getString("user_name"), date(rs, "work_date"), nullableLong(rs, "shift_id"), rs
            .getString("shift_code"), rs.getString("shift_name"), hhmm(rs, "start_time"), hhmm(rs, "end_time"));

    private static final RowMapper<ScheduleRow> SCHEDULE_ROW_MAPPER = (rs, rowNum) -> new ScheduleRow(rs
        .getLong("user_id"), date(rs, "work_date"), nullableLong(rs, "shift_id"), rs
            .getString("shift_name"), time(rs, "start_time"), time(rs, "end_time"), rs.getInt("grace_minutes"));

    private static final String RECORD_FROM = """
          FROM oa_attendance_record r
          LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.user_id
          LEFT JOIN oa_employee e ON e.tenant_id = r.tenant_id AND e.user_id = r.user_id AND e.deleted = 0
         WHERE r.tenant_id = ? AND r.work_date >= ? AND r.work_date <= ?
        """;

    private static final RowMapper<OaAttendanceRecordResp> RECORD_MAPPER = (rs, rowNum) -> new OaAttendanceRecordResp(rs
        .getLong("id"), rs.getLong("user_id"), rs.getString("user_name"), rs
            .getString("employee_no"), date(rs, "work_date"), timestamp(rs, "check_in"), timestamp(rs, "check_out"), rs
                .getString("check_in_source"), rs
                    .getString("check_out_source"), nullableLong(rs, "import_id"), timestamp(rs, "update_time"));

    private static final RowMapper<RecordRow> RECORD_ROW_MAPPER = (rs, rowNum) -> new RecordRow(rs.getLong("id"), rs
        .getLong("user_id"), date(rs, "work_date"), timestamp(rs, "check_in"), timestamp(rs, "check_out"), rs
            .getString("check_in_source"), rs.getString("check_out_source"));

    private static final String REQUEST_COLUMNS = "id, application_id, applicant_id, kind, work_date, start_time, end_time, hours, check_type, destination, status";

    private static final RowMapper<RequestRow> REQUEST_ROW_MAPPER = (rs, rowNum) -> new RequestRow(rs.getLong("id"), rs
        .getLong("application_id"), rs.getLong("applicant_id"), rs
            .getString("kind"), date(rs, "work_date"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                .getBigDecimal("hours"), rs.getString("check_type"), rs.getString("destination"), rs
                    .getString("status"));

    private static final String REQUEST_FROM = """
          FROM oa_attendance_request q
          JOIN oa_application a ON a.tenant_id = q.tenant_id AND a.id = q.application_id
          LEFT JOIN sys_user u ON u.tenant_id = q.tenant_id AND u.id = q.applicant_id
         WHERE q.tenant_id = ?
        """;

    private static final RowMapper<OaAttendanceRequestResp> REQUEST_MAPPER = (rs,
                                                                              rowNum) -> new OaAttendanceRequestResp(rs
                                                                                  .getLong("id"), rs
                                                                                      .getLong("application_id"), rs
                                                                                          .getString("title"), rs
                                                                                              .getString("application_status"), rs
                                                                                                  .getLong("applicant_id"), rs
                                                                                                      .getString("applicant_name"), rs
                                                                                                          .getString("kind"), date(rs, "work_date"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                                                                                                              .getBigDecimal("hours"), rs
                                                                                                                  .getString("check_type"), rs
                                                                                                                      .getString("destination"), rs
                                                                                                                          .getString("status"), timestamp(rs, "create_time"));

    private static final RowMapper<LeaveSpan> LEAVE_MAPPER = (rs, rowNum) -> new LeaveSpan(rs
        .getLong("applicant_id"), date(rs, "start_date"), rs.getString("start_half"), date(rs, "end_date"), rs
            .getString("end_half"), rs.getString("leave_type_name"));

    private static final RowMapper<DateSpan> TRAVEL_MAPPER = (rs, rowNum) -> new DateSpan(rs
        .getLong("applicant_id"), date(rs, "start_date"), date(rs, "end_date"), rs.getString("destination"));

    private final JdbcTemplate jdbcTemplate;

    public OaAttendanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁住申请人账号行，串行化同一申请人的请假/加班/外出/补卡提交，使重叠与次数检查在并发下成立。
     */
    public void lockApplicant(long tenantId, long applicantId) {
        jdbcTemplate.query("SELECT id FROM sys_user WHERE tenant_id = ? AND id = ? FOR UPDATE", (rs, rowNum) -> rs
            .getLong("id"), tenantId, applicantId);
    }

    public List<OaShiftResp> shifts(long tenantId, String status) {
        if (status == null) {
            return jdbcTemplate
                .query("SELECT " + SHIFT_COLUMNS + " FROM oa_shift WHERE tenant_id = ? ORDER BY start_time, id", SHIFT_MAPPER, tenantId);
        }
        return jdbcTemplate
            .query("SELECT " + SHIFT_COLUMNS + " FROM oa_shift WHERE tenant_id = ? AND status = ? ORDER BY start_time, id", SHIFT_MAPPER, tenantId, status);
    }

    public OaShiftResp shift(long tenantId, long id) {
        List<OaShiftResp> rows = jdbcTemplate
            .query("SELECT " + SHIFT_COLUMNS + " FROM oa_shift WHERE tenant_id = ? AND id = ?", SHIFT_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ShiftRow shiftRow(long tenantId, long id) {
        List<ShiftRow> rows = jdbcTemplate
            .query("SELECT " + SHIFT_COLUMNS + " FROM oa_shift WHERE tenant_id = ? AND id = ?", SHIFT_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertShift(long tenantId,
                            String code,
                            String name,
                            LocalTime start,
                            LocalTime end,
                            int graceMinutes,
                            String status,
                            String remark,
                            long userId,
                            LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_shift
                  (tenant_id, code, name, start_time, end_time, grace_minutes, status, remark, version, create_user,
                   create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setTime(4, Time.valueOf(start));
            ps.setTime(5, Time.valueOf(end));
            ps.setInt(6, graceMinutes);
            ps.setString(7, status);
            ps.setString(8, remark);
            ps.setLong(9, userId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateShift(long tenantId,
                           long id,
                           String name,
                           LocalTime start,
                           LocalTime end,
                           int graceMinutes,
                           String status,
                           String remark,
                           int version,
                           long userId,
                           LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_shift
               SET name = ?, start_time = ?, end_time = ?, grace_minutes = ?, status = ?, remark = ?,
                   version = version + 1, update_user = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, name, Time.valueOf(start), Time
            .valueOf(end), graceMinutes, status, remark, userId, now, tenantId, id, version);
    }

    public int updateSchedule(long tenantId, long userId, LocalDate workDate, long shiftId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_schedule SET shift_id = ?, update_time = ? WHERE tenant_id = ? AND user_id = ? AND work_date = ?
            """, shiftId, now, tenantId, userId, Date.valueOf(workDate));
    }

    public void insertSchedule(long tenantId,
                               long userId,
                               LocalDate workDate,
                               long shiftId,
                               long operatorId,
                               LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_schedule (tenant_id, user_id, work_date, shift_id, create_user, create_time)
            VALUES (?, ?, ?, ?, ?, ?)
            """, tenantId, userId, Date.valueOf(workDate), shiftId, operatorId, now);
    }

    public int deleteSchedule(long tenantId, long userId, LocalDate workDate) {
        return jdbcTemplate
            .update("DELETE FROM oa_schedule WHERE tenant_id = ? AND user_id = ? AND work_date = ?", tenantId, userId, Date
                .valueOf(workDate));
    }

    public List<OaScheduleResp> schedules(long tenantId, LocalDate start, LocalDate end, Long userId) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end)));
        String sql = SCHEDULE_SELECT;
        if (userId != null) {
            sql += " AND s.user_id = ?";
            args.add(userId);
        }
        return jdbcTemplate.query(sql + " ORDER BY s.user_id, s.work_date", SCHEDULE_MAPPER, args.toArray());
    }

    public List<ScheduleRow> scheduleRows(long tenantId, Collection<Long> userIds, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end)));
        return jdbcTemplate
            .query(SCHEDULE_SELECT + inClause("s.user_id", userIds, args) + " ORDER BY s.work_date", SCHEDULE_ROW_MAPPER, args
                .toArray());
    }

    /**
     * 在职员工的工号到用户 ID 映射。
     */
    public Map<String, Long> activeEmployeesByNo(long tenantId, Collection<String> employeeNos) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (employeeNos.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>(List.of(tenantId));
        jdbcTemplate.query("""
            SELECT employee_no, user_id FROM oa_employee
             WHERE tenant_id = ? AND deleted = 0 AND employment_status = 'ACTIVE'
            """ + inClause("employee_no", employeeNos, args), rs -> {
            result.put(rs.getString("employee_no"), rs.getLong("user_id"));
        }, args.toArray());
        return result;
    }

    public Set<Long> activeUserIds(long tenantId, Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<Object> args = new ArrayList<>(List.of(tenantId));
        return new HashSet<>(jdbcTemplate.query("""
            SELECT user_id FROM oa_employee WHERE tenant_id = ? AND deleted = 0 AND employment_status = 'ACTIVE'
            """ + inClause("user_id", userIds, args), (rs, rowNum) -> rs.getLong("user_id"), args.toArray()));
    }

    /**
     * 当月有排班或考勤记录的员工（按用户 ID 排序，最多 limit 个）。
     */
    public List<Long> attendanceUsers(long tenantId, LocalDate start, LocalDate end, String keyword, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end), tenantId, Date
            .valueOf(start), Date.valueOf(end), tenantId));
        StringBuilder sql = new StringBuilder("""
            SELECT x.user_id FROM (
              SELECT user_id FROM oa_schedule WHERE tenant_id = ? AND work_date >= ? AND work_date <= ?
              UNION
              SELECT user_id FROM oa_attendance_record WHERE tenant_id = ? AND work_date >= ? AND work_date <= ?
            ) x
            LEFT JOIN sys_user u ON u.tenant_id = ? AND u.id = x.user_id
            """);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" WHERE (u.username LIKE ? OR u.nickname LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY x.user_id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getLong("user_id"), args.toArray());
    }

    /**
     * 用户 ID 到显示名与工号。
     */
    public Map<Long, UserInfo> users(long tenantId, Collection<Long> userIds) {
        Map<Long, UserInfo> result = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>(List.of(tenantId));
        jdbcTemplate.query("""
            SELECT u.id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, e.employee_no
              FROM sys_user u
              LEFT JOIN oa_employee e ON e.tenant_id = u.tenant_id AND e.user_id = u.id AND e.deleted = 0
             WHERE u.tenant_id = ?
            """ + inClause("u.id", userIds, args), rs -> {
            result.put(rs.getLong("id"), new UserInfo(rs.getString("user_name"), rs.getString("employee_no")));
        }, args.toArray());
        return result;
    }

    public List<OaAttendanceRecordResp> records(long tenantId,
                                                LocalDate start,
                                                LocalDate end,
                                                Long userId,
                                                int offset,
                                                int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end)));
        String sql = """
            SELECT r.id, r.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, e.employee_no,
                   r.work_date, r.check_in, r.check_out, r.check_in_source, r.check_out_source, r.import_id,
                   COALESCE(r.update_time, r.create_time) AS update_time
            """ + RECORD_FROM + userFilter("r.user_id", userId, args) + " ORDER BY r.work_date DESC, r.user_id LIMIT ? OFFSET ?";
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql, RECORD_MAPPER, args.toArray());
    }

    public long countRecords(long tenantId, LocalDate start, LocalDate end, Long userId) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end)));
        Long count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) " + RECORD_FROM + userFilter("r.user_id", userId, args), Long.class, args
                .toArray());
        return count == null ? 0 : count;
    }

    public RecordRow lockRecord(long tenantId, long userId, LocalDate workDate) {
        List<RecordRow> rows = jdbcTemplate.query("""
            SELECT id, user_id, work_date, check_in, check_out, check_in_source, check_out_source
              FROM oa_attendance_record WHERE tenant_id = ? AND user_id = ? AND work_date = ?
             FOR UPDATE
            """, RECORD_ROW_MAPPER, tenantId, userId, Date.valueOf(workDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RecordRow record(long tenantId, long userId, LocalDate workDate) {
        List<RecordRow> rows = jdbcTemplate.query("""
            SELECT id, user_id, work_date, check_in, check_out, check_in_source, check_out_source
              FROM oa_attendance_record WHERE tenant_id = ? AND user_id = ? AND work_date = ?
            """, RECORD_ROW_MAPPER, tenantId, userId, Date.valueOf(workDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<RecordRow> recordRows(long tenantId, Collection<Long> userIds, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end)));
        return jdbcTemplate.query("""
            SELECT id, user_id, work_date, check_in, check_out, check_in_source, check_out_source
              FROM oa_attendance_record WHERE tenant_id = ? AND work_date >= ? AND work_date <= ?
            """ + inClause("user_id", userIds, args), RECORD_ROW_MAPPER, args.toArray());
    }

    public void insertRecord(long tenantId,
                             long userId,
                             LocalDate workDate,
                             LocalDateTime checkIn,
                             LocalDateTime checkOut,
                             String checkInSource,
                             String checkOutSource,
                             Long importId,
                             LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_attendance_record
              (tenant_id, user_id, work_date, check_in, check_out, check_in_source, check_out_source, import_id,
               create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, userId, Date
            .valueOf(workDate), checkIn, checkOut, checkInSource, checkOutSource, importId, now);
    }

    public void updateRecord(long tenantId,
                             long id,
                             LocalDateTime checkIn,
                             LocalDateTime checkOut,
                             String checkInSource,
                             String checkOutSource,
                             Long importId,
                             LocalDateTime now) {
        jdbcTemplate.update("""
            UPDATE oa_attendance_record
               SET check_in = ?, check_out = ?, check_in_source = ?, check_out_source = ?,
                   import_id = COALESCE(?, import_id), update_time = ?
             WHERE tenant_id = ? AND id = ?
            """, checkIn, checkOut, checkInSource, checkOutSource, importId, now, tenantId, id);
    }

    public OaAttendanceImportResp importByRequest(long tenantId, String requestId, boolean lock) {
        List<OaAttendanceImportResp> rows = jdbcTemplate.query("""
            SELECT id, total_rows, inserted_rows, updated_rows, kept_corrections
              FROM oa_attendance_import WHERE tenant_id = ? AND request_id = ?
            """ + (lock ? " FOR UPDATE" : ""), (rs, rowNum) -> new OaAttendanceImportResp(true, true, rs
            .getLong("id"), rs.getInt("total_rows"), rs.getInt("inserted_rows"), rs.getInt("updated_rows"), rs
                .getInt("kept_corrections"), List.of()), tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertImport(long tenantId,
                             String requestId,
                             String fileName,
                             int totalRows,
                             long operatorId,
                             LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_attendance_import
                  (tenant_id, request_id, file_name, total_rows, inserted_rows, updated_rows, kept_corrections,
                   operator_id, create_time)
                VALUES (?, ?, ?, ?, 0, 0, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, requestId);
            ps.setString(3, fileName);
            ps.setInt(4, totalRows);
            ps.setLong(5, operatorId);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void finishImport(long tenantId, long id, int inserted, int updated, int kept) {
        jdbcTemplate.update("""
            UPDATE oa_attendance_import SET inserted_rows = ?, updated_rows = ?, kept_corrections = ?
             WHERE tenant_id = ? AND id = ?
            """, inserted, updated, kept, tenantId, id);
    }

    public void insertRequest(long tenantId,
                              long applicationId,
                              long applicantId,
                              String kind,
                              LocalDate workDate,
                              LocalDateTime start,
                              LocalDateTime end,
                              BigDecimal hours,
                              String checkType,
                              String destination,
                              String status,
                              LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_attendance_request
              (tenant_id, application_id, applicant_id, kind, work_date, start_time, end_time, hours, check_type,
               destination, status, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, applicationId, applicantId, kind, Date
            .valueOf(workDate), start, end, hours, checkType, destination, status, now);
    }

    public RequestRow lockRequestByApplication(long tenantId, long applicationId) {
        List<RequestRow> rows = jdbcTemplate.query("SELECT " + REQUEST_COLUMNS + """
             FROM oa_attendance_request WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, REQUEST_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionRequest(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_attendance_request SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    /**
     * 与给定时间段相交、未关闭的加班/外出申请（锁定读）。
     */
    public List<RequestRow> overlappingRequests(long tenantId,
                                                long applicantId,
                                                LocalDateTime start,
                                                LocalDateTime end) {
        return jdbcTemplate.query("SELECT " + REQUEST_COLUMNS + """
             FROM oa_attendance_request
             WHERE tenant_id = ? AND applicant_id = ? AND kind IN ('OVERTIME', 'OUTING') AND status <> 'CLOSED'
               AND start_time < ? AND end_time > ?
             ORDER BY start_time
             FOR UPDATE
            """, REQUEST_ROW_MAPPER, tenantId, applicantId, end, start);
    }

    /**
     * 某月未关闭的补卡申请（锁定读）。
     */
    public List<RequestRow> activeCorrections(long tenantId, long applicantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.query("SELECT " + REQUEST_COLUMNS + """
             FROM oa_attendance_request
             WHERE tenant_id = ? AND applicant_id = ? AND kind = 'CORRECTION' AND status <> 'CLOSED'
               AND work_date >= ? AND work_date <= ?
             FOR UPDATE
            """, REQUEST_ROW_MAPPER, tenantId, applicantId, Date.valueOf(start), Date.valueOf(end));
    }

    public List<RequestRow> approvedRequests(long tenantId, Collection<Long> userIds, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(start), Date.valueOf(end)));
        return jdbcTemplate.query("SELECT " + REQUEST_COLUMNS + """
             FROM oa_attendance_request
             WHERE tenant_id = ? AND status = 'APPROVED' AND work_date >= ? AND work_date <= ?
            """ + inClause("applicant_id", userIds, args) + " ORDER BY start_time", REQUEST_ROW_MAPPER, args.toArray());
    }

    public List<OaAttendanceRequestResp> requests(long tenantId,
                                                  String kind,
                                                  String status,
                                                  String keyword,
                                                  Long applicantId,
                                                  int offset,
                                                  int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT q.id, q.application_id, a.title, a.status AS application_status, q.applicant_id,
                   COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, q.kind, q.work_date, q.start_time,
                   q.end_time, q.hours, q.check_type, q.destination, q.status, q.create_time
            """ + REQUEST_FROM);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        appendRequestFilters(sql, args, kind, status, keyword, applicantId);
        sql.append(" ORDER BY q.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), REQUEST_MAPPER, args.toArray());
    }

    public long countRequests(long tenantId, String kind, String status, String keyword, Long applicantId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + REQUEST_FROM);
        List<Object> args = new ArrayList<>(List.of(tenantId));
        appendRequestFilters(sql, args, kind, status, keyword, applicantId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /**
     * 与日期区间相交的有效请假单（占用/已扣减/仅记录），锁定读。
     */
    public List<LeaveSpan> activeLeaves(long tenantId, long applicantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.query("""
            SELECT applicant_id, start_date, start_half, end_date, end_half, leave_type_name FROM oa_leave_request
             WHERE tenant_id = ? AND applicant_id = ? AND status IN ('OCCUPIED', 'DEDUCTED', 'RECORDED')
               AND start_date <= ? AND end_date >= ?
             FOR UPDATE
            """, LEAVE_MAPPER, tenantId, applicantId, Date.valueOf(end), Date.valueOf(start));
    }

    public List<LeaveSpan> approvedLeaves(long tenantId, Collection<Long> userIds, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(end), Date.valueOf(start)));
        return jdbcTemplate.query("""
            SELECT l.applicant_id, l.start_date, l.start_half, l.end_date, l.end_half, l.leave_type_name
              FROM oa_leave_request l
              JOIN oa_application a ON a.tenant_id = l.tenant_id AND a.id = l.application_id AND a.status = 'APPROVED'
             WHERE l.tenant_id = ? AND l.status IN ('DEDUCTED', 'RECORDED') AND l.start_date <= ? AND l.end_date >= ?
            """ + inClause("l.applicant_id", userIds, args), LEAVE_MAPPER, args.toArray());
    }

    public List<DateSpan> activeTravels(long tenantId, long applicantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.query("""
            SELECT applicant_id, start_date, end_date, destination FROM oa_travel
             WHERE tenant_id = ? AND applicant_id = ? AND status <> 'CLOSED' AND start_date <= ? AND end_date >= ?
             FOR UPDATE
            """, TRAVEL_MAPPER, tenantId, applicantId, Date.valueOf(end), Date.valueOf(start));
    }

    public List<DateSpan> approvedTravels(long tenantId, Collection<Long> userIds, LocalDate start, LocalDate end) {
        List<Object> args = new ArrayList<>(List.of(tenantId, Date.valueOf(end), Date.valueOf(start)));
        return jdbcTemplate.query("""
            SELECT applicant_id, start_date, end_date, destination FROM oa_travel
             WHERE tenant_id = ? AND status = 'APPROVED' AND start_date <= ? AND end_date >= ?
            """ + inClause("applicant_id", userIds, args), TRAVEL_MAPPER, args.toArray());
    }

    private static void appendRequestFilters(StringBuilder sql,
                                             List<Object> args,
                                             String kind,
                                             String status,
                                             String keyword,
                                             Long applicantId) {
        if (kind != null) {
            sql.append(" AND q.kind = ?");
            args.add(kind);
        }
        if (status != null) {
            sql.append(" AND q.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND q.applicant_id = ?");
            args.add(applicantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ? OR q.destination LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
    }

    private static String userFilter(String column, Long userId, List<Object> args) {
        if (userId == null) {
            return "";
        }
        args.add(userId);
        return " AND " + column + " = ?";
    }

    /**
     * 追加 IN 条件；集合为 null 表示不过滤，空集合表示无结果。
     */
    private static String inClause(String column, Collection<?> values, List<Object> args) {
        if (values == null) {
            return "";
        }
        if (values.isEmpty()) {
            return " AND 1 = 0";
        }
        args.addAll(values);
        return " AND " + column + " IN (" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")";
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    private static LocalTime time(ResultSet rs, String column) throws SQLException {
        Time value = rs.getTime(column);
        return value == null ? null : value.toLocalTime();
    }

    private static String hhmm(ResultSet rs, String column) throws SQLException {
        LocalTime value = time(rs, column);
        return value == null ? null : value.toString().substring(0, 5);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ShiftRow(long id, String code, String name, LocalTime start, LocalTime end, int graceMinutes,
                           String status, int version) {
    }

    public record ScheduleRow(long userId, LocalDate workDate, Long shiftId, String shiftName, LocalTime start,
                              LocalTime end, int graceMinutes) {
    }

    public record RecordRow(long id, long userId, LocalDate workDate, LocalDateTime checkIn, LocalDateTime checkOut,
                            String checkInSource, String checkOutSource) {
    }

    public record RequestRow(long id, long applicationId, long applicantId, String kind, LocalDate workDate,
                             LocalDateTime start, LocalDateTime end, BigDecimal hours, String checkType,
                             String destination, String status) {
    }

    public record LeaveSpan(long userId, LocalDate startDate, String startHalf, LocalDate endDate, String endHalf,
                            String typeName) {

        public LocalDateTime startTime() {
            return startDate.atStartOfDay().plusHours("PM".equals(startHalf) ? 12 : 0);
        }

        public LocalDateTime endTime() {
            return endDate.atStartOfDay().plusHours("AM".equals(endHalf) ? 12 : 24);
        }
    }

    public record DateSpan(long userId, LocalDate startDate, LocalDate endDate, String label) {

        public boolean covers(LocalDate date) {
            return !date.isBefore(startDate) && !date.isAfter(endDate);
        }
    }

    public record UserInfo(String name, String employeeNo) {
    }
}
