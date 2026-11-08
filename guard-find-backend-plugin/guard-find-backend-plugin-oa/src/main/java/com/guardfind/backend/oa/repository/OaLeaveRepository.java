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

import com.guardfind.backend.oa.model.OaLeaveBalanceResp;
import com.guardfind.backend.oa.model.OaLeaveLedgerResp;
import com.guardfind.backend.oa.model.OaLeaveRequestResp;
import com.guardfind.backend.oa.model.OaLeaveTypeResp;
import com.guardfind.backend.oa.model.OaWorkCalendarResp;
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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请假试点的显式租户 SQL。
 *
 * <p>
 * 余额变更全部使用带条件的 UPDATE（可用额度、请假单状态），并发占用或重复终态回调
 * 只会有一个成功；台账按（租户, 申请, 动作）唯一兜底。
 * </p>
 */
@Repository
public class OaLeaveRepository {

    private static final String TYPE_COLUMNS = """
        id, code, name, balance_required, max_days_per_request, status, sort, remark, version, create_time, update_time
        """;

    private static final RowMapper<OaLeaveTypeResp> TYPE_MAPPER = (rs, rowNum) -> new OaLeaveTypeResp(rs
        .getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("balance_required"), rs
            .getBigDecimal("max_days_per_request"), rs.getString("status"), rs.getInt("sort"), rs
                .getString("remark"), rs.getInt("version"), timestamp(rs, "create_time"), timestamp(rs, "update_time"));

    private static final RowMapper<Balance> BALANCE_ROW_MAPPER = (rs, rowNum) -> new Balance(rs.getLong("id"), rs
        .getLong("user_id"), rs.getLong("leave_type_id"), rs.getInt("period_year"), rs.getBigDecimal("total_days"), rs
            .getBigDecimal("used_days"), rs.getBigDecimal("occupied_days"));

    private static final RowMapper<LeaveRequestRow> REQUEST_ROW_MAPPER = (rs, rowNum) -> new LeaveRequestRow(rs
        .getLong("id"), rs.getLong("application_id"), rs.getLong("applicant_id"), nullableLong(rs, "balance_id"), rs
            .getBigDecimal("days"), rs.getString("status"));

    private static final String BALANCE_SELECT = """
        SELECT b.id, b.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, b.leave_type_id,
               t.code AS leave_type_code, t.name AS leave_type_name, b.period_year, b.total_days, b.used_days,
               b.occupied_days, b.update_time, b.create_time
          FROM oa_leave_balance b
          JOIN oa_leave_type t ON t.tenant_id = b.tenant_id AND t.id = b.leave_type_id
          LEFT JOIN sys_user u ON u.tenant_id = b.tenant_id AND u.id = b.user_id
         WHERE b.tenant_id = ?
        """;

    private static final RowMapper<OaLeaveBalanceResp> BALANCE_MAPPER = (rs, rowNum) -> {
        BigDecimal total = rs.getBigDecimal("total_days");
        BigDecimal used = rs.getBigDecimal("used_days");
        BigDecimal occupied = rs.getBigDecimal("occupied_days");
        LocalDateTime updated = timestamp(rs, "update_time");
        return new OaLeaveBalanceResp(rs.getLong("id"), rs.getLong("user_id"), rs.getString("user_name"), rs
            .getLong("leave_type_id"), rs.getString("leave_type_code"), rs.getString("leave_type_name"), rs
                .getInt("period_year"), total, used, occupied, total.subtract(used).subtract(occupied), updated == null
                    ? timestamp(rs, "create_time")
                    : updated);
    };

    private static final RowMapper<OaLeaveRequestResp> REQUEST_MAPPER = (rs, rowNum) -> new OaLeaveRequestResp(rs
        .getLong("id"), rs.getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getString("leave_type_code"), rs.getString("leave_type_name"), date(rs, "start_date"), rs
                .getString("start_half"), date(rs, "end_date"), rs.getString("end_half"), rs.getBigDecimal("days"), rs
                    .getInt("period_year"), rs.getString("status"), timestamp(rs, "create_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaLeaveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaLeaveTypeResp> types(long tenantId, boolean enabledOnly) {
        String sql = "SELECT " + TYPE_COLUMNS + " FROM oa_leave_type WHERE tenant_id = ?" + (enabledOnly
            ? " AND status = 'ENABLED'"
            : "") + " ORDER BY sort, id";
        return jdbcTemplate.query(sql, TYPE_MAPPER, tenantId);
    }

    public OaLeaveTypeResp type(long tenantId, long id) {
        List<OaLeaveTypeResp> types = jdbcTemplate
            .query("SELECT " + TYPE_COLUMNS + " FROM oa_leave_type " + "WHERE tenant_id = ? AND id = ?", TYPE_MAPPER, tenantId, id);
        return types.isEmpty() ? null : types.get(0);
    }

    public OaLeaveTypeResp enabledTypeByCode(long tenantId, String code) {
        List<OaLeaveTypeResp> types = jdbcTemplate
            .query("SELECT " + TYPE_COLUMNS + " FROM oa_leave_type " + "WHERE tenant_id = ? AND code = ? AND status = 'ENABLED'", TYPE_MAPPER, tenantId, code);
        return types.isEmpty() ? null : types.get(0);
    }

    public boolean typeCodeExists(long tenantId, String code) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_leave_type WHERE tenant_id = ? AND code = ?
            """, Integer.class, tenantId, code);
        return count != null && count > 0;
    }

    public long insertType(long tenantId,
                           String code,
                           String name,
                           boolean balanceRequired,
                           BigDecimal maxDays,
                           String status,
                           int sort,
                           String remark,
                           long userId,
                           LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_leave_type
                  (tenant_id, code, name, balance_required, max_days_per_request, status, sort, remark, version,
                   create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setBoolean(4, balanceRequired);
            ps.setBigDecimal(5, maxDays);
            ps.setString(6, status);
            ps.setInt(7, sort);
            ps.setString(8, remark);
            ps.setLong(9, userId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateType(long tenantId,
                          long id,
                          String name,
                          boolean balanceRequired,
                          BigDecimal maxDays,
                          String status,
                          int sort,
                          String remark,
                          long userId,
                          LocalDateTime now,
                          int version) {
        return jdbcTemplate.update("""
            UPDATE oa_leave_type
               SET name = ?, balance_required = ?, max_days_per_request = ?, status = ?, sort = ?, remark = ?,
                   update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, name, balanceRequired, maxDays, status, sort, remark, userId, now, tenantId, id, version);
    }

    public List<OaWorkCalendarResp> calendar(long tenantId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
            SELECT id, calendar_date, day_type, name
              FROM oa_work_calendar
             WHERE tenant_id = ? AND calendar_date BETWEEN ? AND ?
             ORDER BY calendar_date
            """, (rs, rowNum) -> new OaWorkCalendarResp(rs.getLong("id"), date(rs, "calendar_date"), rs
            .getString("day_type"), rs.getString("name")), tenantId, Date.valueOf(from), Date.valueOf(to));
    }

    public Map<LocalDate, String> calendarOverrides(long tenantId, LocalDate from, LocalDate to) {
        Map<LocalDate, String> overrides = new LinkedHashMap<>();
        for (OaWorkCalendarResp day : calendar(tenantId, from, to)) {
            overrides.put(day.calendarDate(), day.dayType());
        }
        return overrides;
    }

    public void upsertCalendar(long tenantId,
                               LocalDate date,
                               String dayType,
                               String name,
                               long userId,
                               LocalDateTime now) {
        int rows = jdbcTemplate.update("""
            UPDATE oa_work_calendar SET day_type = ?, name = ? WHERE tenant_id = ? AND calendar_date = ?
            """, dayType, name, tenantId, Date.valueOf(date));
        if (rows == 0) {
            jdbcTemplate.update("""
                INSERT INTO oa_work_calendar (tenant_id, calendar_date, day_type, name, create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, Date.valueOf(date), dayType, name, userId, now);
        }
    }

    public int deleteCalendar(long tenantId, LocalDate date) {
        return jdbcTemplate
            .update("DELETE FROM oa_work_calendar WHERE tenant_id = ? AND calendar_date = ?", tenantId, Date
                .valueOf(date));
    }

    public Balance lockBalance(long tenantId, long userId, long leaveTypeId, int year) {
        List<Balance> rows = jdbcTemplate.query("""
            SELECT id, user_id, leave_type_id, period_year, total_days, used_days, occupied_days
              FROM oa_leave_balance
             WHERE tenant_id = ? AND user_id = ? AND leave_type_id = ? AND period_year = ?
             FOR UPDATE
            """, BALANCE_ROW_MAPPER, tenantId, userId, leaveTypeId, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Balance balance(long tenantId, long userId, long leaveTypeId, int year) {
        List<Balance> rows = jdbcTemplate.query("""
            SELECT id, user_id, leave_type_id, period_year, total_days, used_days, occupied_days
              FROM oa_leave_balance
             WHERE tenant_id = ? AND user_id = ? AND leave_type_id = ? AND period_year = ?
            """, BALANCE_ROW_MAPPER, tenantId, userId, leaveTypeId, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Balance balanceById(long tenantId, long balanceId) {
        List<Balance> rows = jdbcTemplate.query("""
            SELECT id, user_id, leave_type_id, period_year, total_days, used_days, occupied_days
              FROM oa_leave_balance WHERE tenant_id = ? AND id = ?
            """, BALANCE_ROW_MAPPER, tenantId, balanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertBalanceIfAbsent(long tenantId, long userId, long leaveTypeId, int year, LocalDateTime now) {
        if (balance(tenantId, userId, leaveTypeId, year) != null) {
            return;
        }
        try {
            jdbcTemplate.update("""
                INSERT INTO oa_leave_balance
                  (tenant_id, user_id, leave_type_id, period_year, total_days, used_days, occupied_days, version,
                   create_time)
                VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?)
                """, tenantId, userId, leaveTypeId, year, now);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 并发发放时另一事务已建行，随后统一加锁更新
        }
    }

    public int adjustTotal(long tenantId, long balanceId, BigDecimal delta, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_leave_balance
               SET total_days = total_days + ?, version = version + 1, update_time = ?
             WHERE tenant_id = ? AND id = ? AND total_days + ? >= used_days + occupied_days
            """, delta, now, tenantId, balanceId, delta);
    }

    public int occupy(long tenantId, long balanceId, BigDecimal days, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_leave_balance
               SET occupied_days = occupied_days + ?, version = version + 1, update_time = ?
             WHERE tenant_id = ? AND id = ? AND total_days - used_days - occupied_days >= ?
            """, days, now, tenantId, balanceId, days);
    }

    public int release(long tenantId, long balanceId, BigDecimal days, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_leave_balance
               SET occupied_days = occupied_days - ?, version = version + 1, update_time = ?
             WHERE tenant_id = ? AND id = ? AND occupied_days >= ?
            """, days, now, tenantId, balanceId, days);
    }

    public int deduct(long tenantId, long balanceId, BigDecimal days, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_leave_balance
               SET occupied_days = occupied_days - ?, used_days = used_days + ?, version = version + 1,
                   update_time = ?
             WHERE tenant_id = ? AND id = ? AND occupied_days >= ?
            """, days, days, now, tenantId, balanceId, days);
    }

    public void insertRequest(long tenantId,
                              long applicationId,
                              long applicantId,
                              OaLeaveTypeResp type,
                              LocalDate start,
                              String startHalf,
                              LocalDate end,
                              String endHalf,
                              BigDecimal days,
                              int year,
                              Long balanceId,
                              String status,
                              LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_leave_request
              (tenant_id, application_id, applicant_id, leave_type_id, leave_type_code, leave_type_name, start_date,
               start_half, end_date, end_half, days, period_year, balance_id, status, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, applicationId, applicantId, type.id(), type.code(), type.name(), Date
            .valueOf(start), startHalf, Date.valueOf(end), endHalf, days, year, balanceId, status, now);
    }

    public LeaveRequestRow lockRequest(long tenantId, long applicationId) {
        List<LeaveRequestRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, balance_id, days, status
              FROM oa_leave_request
             WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, REQUEST_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionRequest(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_leave_request SET status = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public void insertLedger(long tenantId,
                             long balanceId,
                             Long applicationId,
                             String action,
                             BigDecimal days,
                             Balance after,
                             long operatorId,
                             String remark,
                             LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_leave_ledger
              (tenant_id, balance_id, application_id, action, days, total_after, used_after, occupied_after,
               operator_id, remark, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, balanceId, applicationId, action, days, after.totalDays(), after.usedDays(), after
            .occupiedDays(), operatorId, remark, now);
    }

    public List<OaLeaveBalanceResp> balances(long tenantId,
                                             Integer year,
                                             String keyword,
                                             Long userId,
                                             int offset,
                                             int limit) {
        StringBuilder sql = new StringBuilder(BALANCE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendBalanceFilters(sql, args, year, keyword, userId);
        sql.append(" ORDER BY b.period_year DESC, b.user_id, t.sort, t.id LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), BALANCE_MAPPER, args.toArray());
    }

    public long countBalances(long tenantId, Integer year, String keyword, Long userId) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
              FROM oa_leave_balance b
              JOIN oa_leave_type t ON t.tenant_id = b.tenant_id AND t.id = b.leave_type_id
              LEFT JOIN sys_user u ON u.tenant_id = b.tenant_id AND u.id = b.user_id
             WHERE b.tenant_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendBalanceFilters(sql, args, year, keyword, userId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaLeaveBalanceResp balanceDetail(long tenantId, long balanceId) {
        List<OaLeaveBalanceResp> rows = jdbcTemplate
            .query(BALANCE_SELECT + " AND b.id = ?", BALANCE_MAPPER, tenantId, balanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaLeaveLedgerResp> ledger(long tenantId, long balanceId, int limit) {
        return jdbcTemplate.query("""
            SELECT l.id, l.balance_id, l.application_id, l.action, l.days, l.total_after, l.used_after,
                   l.occupied_after, l.operator_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS operator_name,
                   l.remark, l.create_time
              FROM oa_leave_ledger l
              LEFT JOIN sys_user u ON u.tenant_id = l.tenant_id AND u.id = l.operator_id
             WHERE l.tenant_id = ? AND l.balance_id = ?
             ORDER BY l.id DESC
             LIMIT ?
            """, (rs, rowNum) -> new OaLeaveLedgerResp(rs.getLong("id"), rs
            .getLong("balance_id"), nullableLong(rs, "application_id"), rs.getString("action"), rs
                .getBigDecimal("days"), rs.getBigDecimal("total_after"), rs.getBigDecimal("used_after"), rs
                    .getBigDecimal("occupied_after"), rs.getLong("operator_id"), rs.getString("operator_name"), rs
                        .getString("remark"), timestamp(rs, "create_time")), tenantId, balanceId, limit);
    }

    public List<OaLeaveRequestResp> requests(long tenantId, long applicantId, int offset, int limit) {
        return jdbcTemplate.query("""
            SELECT r.id, r.application_id, a.title, a.status AS application_status, r.leave_type_code,
                   r.leave_type_name, r.start_date, r.start_half, r.end_date, r.end_half, r.days, r.period_year,
                   r.status, r.create_time
              FROM oa_leave_request r
              JOIN oa_application a ON a.tenant_id = r.tenant_id AND a.id = r.application_id
             WHERE r.tenant_id = ? AND r.applicant_id = ?
             ORDER BY r.id DESC
             LIMIT ? OFFSET ?
            """, REQUEST_MAPPER, tenantId, applicantId, limit, offset);
    }

    public long countRequests(long tenantId, long applicantId) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_leave_request WHERE tenant_id = ? AND applicant_id = ?
            """, Long.class, tenantId, applicantId);
        return count == null ? 0 : count;
    }

    public boolean userExists(long tenantId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sys_user WHERE tenant_id = ? AND id = ? AND deleted = 0
            """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    private static void appendBalanceFilters(StringBuilder sql,
                                             List<Object> args,
                                             Integer year,
                                             String keyword,
                                             Long userId) {
        if (year != null) {
            sql.append(" AND b.period_year = ?");
            args.add(year);
        }
        if (userId != null) {
            sql.append(" AND b.user_id = ?");
            args.add(userId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (u.username LIKE ? OR u.nickname LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
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

    public record Balance(long id, long userId, long leaveTypeId, int periodYear, BigDecimal totalDays,
                          BigDecimal usedDays, BigDecimal occupiedDays) {

        public BigDecimal available() {
            return totalDays.subtract(usedDays).subtract(occupiedDays);
        }
    }

    public record LeaveRequestRow(long id, long applicationId, long applicantId, Long balanceId, BigDecimal days,
                                  String status) {
    }
}
