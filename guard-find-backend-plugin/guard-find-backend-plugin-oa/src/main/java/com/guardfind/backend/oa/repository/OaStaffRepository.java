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

import com.guardfind.backend.oa.model.OaEmployeeOptionResp;
import com.guardfind.backend.oa.model.OaEmployeeResp;
import com.guardfind.backend.oa.model.OaPositionOptionResp;
import com.guardfind.backend.oa.model.OaPositionResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工与岗位的显式租户 SQL。所有读写都带 tenant_id，不依赖数据权限拦截器。
 */
@Repository
public class OaStaffRepository {

    private static final String EMPLOYEE_COLUMNS = """
        e.id, e.user_id, u.nickname, u.username, u.dept_id, d.name AS dept_name,
        e.employee_no, e.supervisor_id, su.nickname AS supervisor_name,
        e.employment_status, e.dept_leader, e.hire_date, e.leave_date, e.version,
        e.create_time, e.update_time
        """;

    private static final String EMPLOYEE_JOINS = """
          FROM oa_employee e
          JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
          LEFT JOIN sys_dept d ON d.id = u.dept_id AND d.deleted = 0
          LEFT JOIN sys_user su ON su.id = e.supervisor_id AND su.tenant_id = e.tenant_id AND su.deleted = 0
        """;

    private static final RowMapper<OaEmployeeResp> EMPLOYEE_MAPPER = (rs, rowNum) -> new OaEmployeeResp(rs
        .getLong("id"), rs.getLong("user_id"), rs.getString("nickname"), rs.getString("username"), nullableLong(rs
            .getObject("dept_id")), rs.getString("dept_name"), rs.getString("employee_no"), nullableLong(rs
                .getObject("supervisor_id")), rs.getString("supervisor_name"), rs.getString("employment_status"), rs
                    .getBoolean("dept_leader"), rs.getObject("hire_date", LocalDate.class), rs
                        .getObject("leave_date", LocalDate.class), List.of(), rs.getInt("version"), rs
                            .getTimestamp("create_time")
                            .toLocalDateTime(), rs.getTimestamp("update_time") == null
                                ? null
                                : rs.getTimestamp("update_time").toLocalDateTime());

    private static final RowMapper<OaPositionResp> POSITION_MAPPER = (rs, rowNum) -> new OaPositionResp(rs
        .getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("status"), rs.getInt("sort"), rs
            .getLong("employee_count"), rs.getInt("version"), rs.getTimestamp("create_time").toLocalDateTime(), rs
                .getTimestamp("update_time") == null ? null : rs.getTimestamp("update_time").toLocalDateTime());

    private static final RowMapper<OaPositionOptionResp> POSITION_OPTION_MAPPER = (rs,
                                                                                   rowNum) -> new OaPositionOptionResp(rs
                                                                                       .getLong("id"), rs
                                                                                           .getString("code"), rs
                                                                                               .getString("name"));

    private static final RowMapper<OaEmployeeOptionResp> EMPLOYEE_OPTION_MAPPER = (rs,
                                                                                   rowNum) -> new OaEmployeeOptionResp(rs
                                                                                       .getLong("user_id"), rs
                                                                                           .getString("nickname"), rs
                                                                                               .getString("username"), rs
                                                                                                   .getLong("dept_id"), rs
                                                                                                       .getString("dept_name"), rs
                                                                                                           .getString("employee_no"));

    private final JdbcTemplate jdbcTemplate;

    public OaStaffRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaEmployeeResp> pageEmployees(long tenantId, String keyword, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT ").append(EMPLOYEE_COLUMNS)
            .append(EMPLOYEE_JOINS)
            .append(" WHERE e.tenant_id = ? AND e.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendEmployeeFilters(sql, args, keyword, status);
        sql.append(" ORDER BY e.update_time DESC, e.id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return withPositions(tenantId, jdbcTemplate.query(sql.toString(), EMPLOYEE_MAPPER, args.toArray()));
    }

    public long countEmployees(long tenantId, String keyword, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(EMPLOYEE_JOINS)
            .append(" WHERE e.tenant_id = ? AND e.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendEmployeeFilters(sql, args, keyword, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public OaEmployeeResp detailEmployee(long tenantId, long id) {
        List<OaEmployeeResp> rows = jdbcTemplate.query("SELECT " + EMPLOYEE_COLUMNS + EMPLOYEE_JOINS
            .concat(" WHERE e.tenant_id = ? AND e.id = ? AND e.deleted = 0"), EMPLOYEE_MAPPER, tenantId, id);
        return withPositions(tenantId, rows).stream().findFirst().orElse(null);
    }

    public OaEmployeeResp lockEmployee(long tenantId, long id) {
        List<Long> ids = jdbcTemplate.queryForList("""
            SELECT id FROM oa_employee WHERE tenant_id = ? AND id = ? AND deleted = 0 FOR UPDATE
            """, Long.class, tenantId, id);
        return ids.isEmpty() ? null : detailEmployee(tenantId, id);
    }

    public OaEmployeeResp employeeByUserId(long tenantId, long userId) {
        List<OaEmployeeResp> rows = jdbcTemplate.query("SELECT " + EMPLOYEE_COLUMNS + EMPLOYEE_JOINS
            .concat(" WHERE e.tenant_id = ? AND e.user_id = ? AND e.deleted = 0"), EMPLOYEE_MAPPER, tenantId, userId);
        return withPositions(tenantId, rows).stream().findFirst().orElse(null);
    }

    public boolean activeUserInTenant(long tenantId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM sys_user
             WHERE tenant_id = ? AND id = ? AND deleted = 0 AND status = 1
            """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    public boolean employeeNoExists(long tenantId, String employeeNo, Long excludeId) {
        if (employeeNo == null || employeeNo.isBlank()) {
            return false;
        }
        List<Long> ids = jdbcTemplate.queryForList("""
            SELECT id FROM oa_employee
             WHERE tenant_id = ? AND employee_no = ? AND deleted = 0
            """, Long.class, tenantId, employeeNo);
        return ids.stream().anyMatch(id -> excludeId == null || id.longValue() != excludeId.longValue());
    }

    public Long supervisorOf(long tenantId, long userId) {
        List<Long> values = jdbcTemplate.queryForList("""
            SELECT supervisor_id FROM oa_employee
             WHERE tenant_id = ? AND user_id = ? AND deleted = 0 AND employment_status = 'ACTIVE'
            """, Long.class, tenantId, userId);
        return values.isEmpty() ? null : values.get(0);
    }

    public long insertEmployee(long tenantId,
                               long userId,
                               String employeeNo,
                               Long supervisorId,
                               String employmentStatus,
                               boolean deptLeader,
                               LocalDate hireDate,
                               LocalDate leaveDate,
                               long operatorId,
                               LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_employee
                  (tenant_id, user_id, employee_no, supervisor_id, employment_status, dept_leader,
                   hire_date, leave_date, version, create_user, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, userId);
            ps.setString(3, blankToNull(employeeNo));
            ps.setObject(4, supervisorId);
            ps.setString(5, employmentStatus);
            ps.setBoolean(6, deptLeader);
            ps.setObject(7, hireDate);
            ps.setObject(8, leaveDate);
            ps.setLong(9, operatorId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateEmployee(long tenantId,
                              long id,
                              String employeeNo,
                              Long supervisorId,
                              String employmentStatus,
                              boolean deptLeader,
                              LocalDate hireDate,
                              LocalDate leaveDate,
                              long operatorId,
                              LocalDateTime now,
                              int version) {
        return jdbcTemplate
            .update("""
                UPDATE oa_employee
                   SET employee_no = ?, supervisor_id = ?, employment_status = ?, dept_leader = ?,
                       hire_date = ?, leave_date = ?, update_user = ?, update_time = ?, version = version + 1
                 WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
                """, blankToNull(employeeNo), supervisorId, employmentStatus, deptLeader, hireDate, leaveDate, operatorId, now, tenantId, id, version);
    }

    public void replacePositions(long tenantId,
                                 long userId,
                                 List<Long> positionIds,
                                 long operatorId,
                                 LocalDateTime now) {
        jdbcTemplate.update("""
            UPDATE oa_employee_position
               SET deleted = id, update_user = ?, update_time = ?
             WHERE tenant_id = ? AND user_id = ? AND deleted = 0
            """, operatorId, now, tenantId, userId);
        if (positionIds == null || positionIds.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("""
            INSERT INTO oa_employee_position
              (tenant_id, user_id, position_id, create_user, create_time, deleted)
            VALUES (?, ?, ?, ?, ?, 0)
            """, positionIds.stream()
            .distinct()
            .map(positionId -> new Object[] {tenantId, userId, positionId, operatorId, now})
            .toList());
    }

    public List<Long> enabledPositionIds(long tenantId, List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(positionIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(positionIds);
        return jdbcTemplate.queryForList("""
            SELECT id FROM oa_position
             WHERE tenant_id = ? AND deleted = 0 AND status = 'ENABLED' AND id IN (%s)
            """.formatted(placeholders), Long.class, args.toArray());
    }

    public List<OaEmployeeOptionResp> employeeOptions(long tenantId, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT u.id AS user_id, u.nickname, u.username, u.dept_id, d.name AS dept_name, e.employee_no
              FROM oa_employee e
              JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
              LEFT JOIN sys_dept d ON d.id = u.dept_id AND d.deleted = 0
             WHERE e.tenant_id = ? AND e.deleted = 0 AND e.employment_status = 'ACTIVE' AND u.status = 1
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (u.nickname LIKE ? OR u.username LIKE ? OR e.employee_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY u.id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), EMPLOYEE_OPTION_MAPPER, args.toArray());
    }

    public List<OaEmployeeOptionResp> userOptions(long tenantId, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT u.id AS user_id, u.nickname, u.username, u.dept_id, d.name AS dept_name, NULL AS employee_no
              FROM sys_user u
              LEFT JOIN sys_dept d ON d.id = u.dept_id AND d.deleted = 0
             WHERE u.tenant_id = ? AND u.deleted = 0 AND u.status = 1
               AND NOT EXISTS (
                   SELECT 1 FROM oa_employee e
                    WHERE e.tenant_id = u.tenant_id AND e.user_id = u.id AND e.deleted = 0
               )
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (u.nickname LIKE ? OR u.username LIKE ?)");
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY u.id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), EMPLOYEE_OPTION_MAPPER, args.toArray());
    }

    public List<OaPositionResp> pagePositions(long tenantId, String keyword, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT ").append(positionColumns())
            .append(" FROM oa_position p WHERE p.tenant_id = ? AND p.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendPositionFilters(sql, args, keyword, status);
        sql.append(" ORDER BY p.sort, p.id LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), POSITION_MAPPER, args.toArray());
    }

    public long countPositions(long tenantId, String keyword, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM oa_position p WHERE p.tenant_id = ? AND p.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendPositionFilters(sql, args, keyword, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public OaPositionResp detailPosition(long tenantId, long id) {
        List<OaPositionResp> rows = jdbcTemplate
            .query("SELECT " + positionColumns() + " FROM oa_position p WHERE p.tenant_id = ? AND p.id = ? AND p.deleted = 0", POSITION_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean positionCodeExists(long tenantId, String code, Long excludeId) {
        List<Long> ids = jdbcTemplate.queryForList("""
            SELECT id FROM oa_position WHERE tenant_id = ? AND code = ? AND deleted = 0
            """, Long.class, tenantId, code);
        return ids.stream().anyMatch(id -> excludeId == null || id.longValue() != excludeId.longValue());
    }

    public long insertPosition(long tenantId, String code, String name, int sort, long operatorId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_position
                  (tenant_id, code, name, status, sort, version, create_user, create_time, deleted)
                VALUES (?, ?, ?, 'ENABLED', ?, 0, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setInt(4, sort);
            ps.setLong(5, operatorId);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updatePosition(long tenantId,
                              long id,
                              String name,
                              int sort,
                              String status,
                              long operatorId,
                              LocalDateTime now,
                              int version) {
        return jdbcTemplate.update("""
            UPDATE oa_position
               SET name = ?, sort = ?, status = ?, update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
            """, name, sort, status, operatorId, now, tenantId, id, version);
    }

    public List<OaPositionOptionResp> positionOptions(long tenantId, int limit) {
        return jdbcTemplate.query("""
            SELECT id, code, name FROM oa_position
             WHERE tenant_id = ? AND deleted = 0 AND status = 'ENABLED'
             ORDER BY sort, id LIMIT ?
            """, POSITION_OPTION_MAPPER, tenantId, limit);
    }

    private List<OaEmployeeResp> withPositions(long tenantId, List<OaEmployeeResp> employees) {
        if (employees.isEmpty()) {
            return employees;
        }
        List<Long> userIds = employees.stream().map(OaEmployeeResp::userId).toList();
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(userIds);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT ep.user_id, p.id, p.code, p.name
              FROM oa_employee_position ep
              JOIN oa_position p ON p.id = ep.position_id AND p.tenant_id = ep.tenant_id AND p.deleted = 0
             WHERE ep.tenant_id = ? AND ep.deleted = 0 AND ep.user_id IN (%s)
             ORDER BY p.sort, p.id
            """.formatted(placeholders), args.toArray());
        Map<Long, List<OaPositionOptionResp>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long userId = ((Number)row.get("user_id")).longValue();
            grouped.computeIfAbsent(userId, key -> new ArrayList<>())
                .add(new OaPositionOptionResp(((Number)row.get("id")).longValue(), String.valueOf(row
                    .get("code")), String.valueOf(row.get("name"))));
        }
        return employees.stream()
            .map(employee -> new OaEmployeeResp(employee.id(), employee.userId(), employee.nickname(), employee
                .username(), employee.deptId(), employee.deptName(), employee.employeeNo(), employee
                    .supervisorId(), employee.supervisorName(), employee.employmentStatus(), employee
                        .deptLeader(), employee.hireDate(), employee.leaveDate(), List.copyOf(grouped
                            .getOrDefault(employee.userId(), List.of())), employee.version(), employee
                                .createTime(), employee.updateTime()))
            .toList();
    }

    private static String positionColumns() {
        return """
            p.id, p.code, p.name, p.status, p.sort, p.version, p.create_time, p.update_time,
            (SELECT COUNT(*)
               FROM oa_employee_position ep
               JOIN oa_employee e ON e.tenant_id = ep.tenant_id AND e.user_id = ep.user_id AND e.deleted = 0
              WHERE ep.tenant_id = p.tenant_id AND ep.position_id = p.id AND ep.deleted = 0
                AND e.employment_status = 'ACTIVE') AS employee_count
            """;
    }

    private static void appendEmployeeFilters(StringBuilder sql, List<Object> args, String keyword, String status) {
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (u.nickname LIKE ? OR u.username LIKE ? OR e.employee_no LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.employment_status = ?");
            args.add(status.trim());
        }
    }

    private static void appendPositionFilters(StringBuilder sql, List<Object> args, String keyword, String status) {
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            sql.append(" AND (p.name LIKE ? OR p.code LIKE ?)");
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND p.status = ?");
            args.add(status.trim());
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number)value).longValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }
}
