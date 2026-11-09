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

import com.guardfind.backend.oa.collab.OaOrgScope;
import com.guardfind.backend.oa.model.OaCalendarEventResp;
import com.guardfind.backend.oa.model.OaCalendarMemberResp;
import com.guardfind.backend.oa.model.OaWorkReportCommentResp;
import com.guardfind.backend.oa.model.OaWorkReportResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.guardfind.backend.oa.repository.OaPurchaseRepository.date;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.nullableLong;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.timestamp;

/**
 * 日程与工作报告的显式租户 SQL。可见范围由服务层按组织快照算出部门/人员集合后传入。
 */
@Repository
public class OaCollabRepository {

    private static final String EVENT_SELECT = """
        SELECT ev.id, ev.owner_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS owner_name, ev.scope, ev.dept_id,
               d.name AS dept_name, ev.title, ev.location, ev.description, ev.start_time, ev.end_time, ev.all_day,
               ev.remind_minutes, ev.remind_at, ev.status, ev.version,
               COALESCE(ev.update_time, ev.create_time) AS update_time
          FROM oa_calendar_event ev
          LEFT JOIN sys_user u ON u.tenant_id = ev.tenant_id AND u.id = ev.owner_id
          LEFT JOIN sys_dept d ON d.tenant_id = ev.tenant_id AND d.id = ev.dept_id
         WHERE ev.tenant_id = ?
        """;

    private static final RowMapper<OaCalendarEventResp> EVENT_MAPPER = (rs, rowNum) -> new OaCalendarEventResp(rs
        .getLong("id"), rs.getLong("owner_id"), rs.getString("owner_name"), rs
            .getString("scope"), nullableLong(rs, "dept_id"), rs.getString("dept_name"), rs.getString("title"), rs
                .getString("location"), rs
                    .getString("description"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                        .getBoolean("all_day"), nullableInt(rs.getInt("remind_minutes"), rs
                            .wasNull()), timestamp(rs, "remind_at"), rs.getString("status"), rs
                                .getInt("version"), timestamp(rs, "update_time"), List.of());

    private static final String REPORT_SELECT = """
        SELECT r.id, r.author_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS author_name, r.dept_id,
               d.name AS dept_name, r.report_type, r.period_start, r.period_end, r.done_content, r.plan_content,
               r.issue_content, r.status, r.submit_time, r.version, COALESCE(r.update_time, r.create_time) AS update_time,
               (SELECT COUNT(*) FROM oa_work_report_comment c WHERE c.tenant_id = r.tenant_id AND c.report_id = r.id) AS comment_count
          FROM oa_work_report r
          LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.author_id
          LEFT JOIN sys_dept d ON d.tenant_id = r.tenant_id AND d.id = r.dept_id
         WHERE r.tenant_id = ?
        """;

    private static final RowMapper<OaWorkReportResp> REPORT_MAPPER = (rs, rowNum) -> {
        LocalDate periodEnd = date(rs, "period_end");
        LocalDateTime submitTime = timestamp(rs, "submit_time");
        return new OaWorkReportResp(rs.getLong("id"), rs.getLong("author_id"), rs
            .getString("author_name"), nullableLong(rs, "dept_id"), rs.getString("dept_name"), rs
                .getString("report_type"), date(rs, "period_start"), periodEnd, rs.getString("done_content"), rs
                    .getString("plan_content"), rs.getString("issue_content"), rs
                        .getString("status"), submitTime, isLate(submitTime, periodEnd), rs.getInt("comment_count"), rs
                            .getInt("version"), timestamp(rs, "update_time"));
    };

    private static final RowMapper<ReportRow> REPORT_ROW_MAPPER = (rs, rowNum) -> new ReportRow(rs.getLong("id"), rs
        .getLong("author_id"), nullableLong(rs, "dept_id"), rs
            .getString("report_type"), date(rs, "period_start"), date(rs, "period_end"), rs
                .getString("status"), timestamp(rs, "submit_time"), rs.getInt("version"));

    private final JdbcTemplate jdbcTemplate;

    public OaCollabRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OaOrgScope orgScope(long tenantId) {
        List<OaOrgScope.Dept> depts = jdbcTemplate.query("""
            SELECT id, parent_id, name FROM sys_dept WHERE tenant_id = ? AND deleted = 0 ORDER BY id
            """, (rs, rowNum) -> new OaOrgScope.Dept(rs.getLong("id"), nullableLong(rs, "parent_id"), rs
            .getString("name")), tenantId);
        List<OaOrgScope.Employee> employees = jdbcTemplate.query("""
            SELECT e.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS name, u.dept_id, e.supervisor_id,
                   e.dept_leader
              FROM oa_employee e
              JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
             WHERE e.tenant_id = ?
               AND e.deleted = 0
               AND e.employment_status = 'ACTIVE'
               AND u.status = 1
            """, (rs, rowNum) -> new OaOrgScope.Employee(rs.getLong("user_id"), rs
            .getString("name"), nullableLong(rs, "dept_id"), nullableLong(rs, "supervisor_id"), rs
                .getBoolean("dept_leader")), tenantId);
        return new OaOrgScope(depts, employees);
    }

    public Long eventByRequest(long tenantId, long ownerId, String requestId) {
        List<Long> ids = jdbcTemplate.queryForList("""
            SELECT id FROM oa_calendar_event WHERE tenant_id = ? AND owner_id = ? AND request_id = ?
            """, Long.class, tenantId, ownerId, requestId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long insertEvent(long tenantId, long ownerId, String requestId, EventValues values, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_calendar_event
                  (tenant_id, owner_id, request_id, scope, dept_id, title, location, description, start_time, end_time,
                   all_day, remind_minutes, remind_at, reminded_at, status, version, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, ownerId);
            ps.setString(3, requestId);
            int index = bindValues(ps, 4, values);
            ps.setTimestamp(index, values.remindedAt() == null ? null : Timestamp.valueOf(values.remindedAt()));
            ps.setTimestamp(index + 1, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKeyAs(Number.class);
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public EventRow lockEvent(long tenantId, long id) {
        List<EventRow> rows = jdbcTemplate.query("""
            SELECT id, owner_id, scope, dept_id, title, start_time, end_time, remind_at, status, version
              FROM oa_calendar_event
             WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new EventRow(rs.getLong("id"), rs.getLong("owner_id"), rs
            .getString("scope"), nullableLong(rs, "dept_id"), rs
                .getString("title"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), timestamp(rs, "remind_at"), rs
                    .getString("status"), rs.getInt("version")), tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int updateEvent(long tenantId, long id, int version, EventValues values, LocalDateTime now) {
        return jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                UPDATE oa_calendar_event
                   SET scope = ?, dept_id = ?, title = ?, location = ?, description = ?, start_time = ?, end_time = ?,
                       all_day = ?, remind_minutes = ?, remind_at = ?, reminded_at = ?, version = version + 1,
                       update_time = ?
                 WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'ACTIVE'
                """);
            int index = bindValues(ps, 1, values);
            ps.setTimestamp(index, values.remindedAt() == null ? null : Timestamp.valueOf(values.remindedAt()));
            ps.setTimestamp(index + 1, Timestamp.valueOf(now));
            ps.setLong(index + 2, tenantId);
            ps.setLong(index + 3, id);
            ps.setInt(index + 4, version);
            return ps;
        });
    }

    public int cancelEvent(long tenantId, long id, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_calendar_event SET status = 'CANCELLED', version = version + 1, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
            """, now, tenantId, id);
    }

    public List<Long> memberIds(long tenantId, long eventId) {
        return jdbcTemplate.queryForList("""
            SELECT user_id FROM oa_calendar_member WHERE tenant_id = ? AND event_id = ? ORDER BY user_id
            """, Long.class, tenantId, eventId);
    }

    public void replaceMembers(long tenantId, long eventId, Collection<Long> userIds, LocalDateTime now) {
        jdbcTemplate.update("DELETE FROM oa_calendar_member WHERE tenant_id = ? AND event_id = ?", tenantId, eventId);
        List<Object[]> args = new ArrayList<>();
        for (Long userId : userIds) {
            args.add(new Object[] {tenantId, eventId, userId, now});
        }
        if (!args.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                INSERT INTO oa_calendar_member (tenant_id, event_id, user_id, create_time) VALUES (?, ?, ?, ?)
                """, args);
        }
    }

    public List<OaCalendarMemberResp> members(long tenantId, Collection<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(eventIds);
        return jdbcTemplate.query("""
            SELECT m.event_id, m.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name
              FROM oa_calendar_member m
              LEFT JOIN sys_user u ON u.tenant_id = m.tenant_id AND u.id = m.user_id
             WHERE m.tenant_id = ? AND m.event_id IN (%s)
             ORDER BY m.event_id, m.user_id
            """.formatted(placeholders(eventIds.size())), (rs, rowNum) -> new OaCalendarMemberResp(rs
            .getLong("event_id"), rs.getLong("user_id"), rs.getString("user_name")), args.toArray());
    }

    public OaCalendarEventResp event(long tenantId, long id) {
        List<OaCalendarEventResp> rows = jdbcTemplate
            .query(EVENT_SELECT + " AND ev.id = ?", EVENT_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean isMember(long tenantId, long eventId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_calendar_member WHERE tenant_id = ? AND event_id = ? AND user_id = ?
            """, Integer.class, tenantId, eventId, userId);
        return count != null && count > 0;
    }

    /**
     * 区间内对用户可见的有效日程：本人创建、受邀成员，或发布到给定部门集合的部门日程。
     */
    public List<OaCalendarEventResp> visibleEvents(long tenantId,
                                                   long userId,
                                                   Collection<Long> deptIds,
                                                   LocalDateTime from,
                                                   LocalDateTime to,
                                                   int limit) {
        StringBuilder sql = new StringBuilder(EVENT_SELECT).append("""
             AND ev.status = 'ACTIVE' AND ev.start_time < ? AND ev.end_time > ?
             AND (ev.owner_id = ?
                  OR EXISTS (SELECT 1 FROM oa_calendar_member m
                              WHERE m.tenant_id = ev.tenant_id AND m.event_id = ev.id AND m.user_id = ?)
            """);
        List<Object> args = new ArrayList<>(List.of(tenantId, Timestamp.valueOf(to), Timestamp
            .valueOf(from), userId, userId));
        if (!deptIds.isEmpty()) {
            sql.append(" OR (ev.scope = 'DEPT' AND ev.dept_id IN (").append(placeholders(deptIds.size())).append("))");
            args.addAll(deptIds);
        }
        sql.append(") ORDER BY ev.start_time, ev.id LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), EVENT_MAPPER, args.toArray());
    }

    public List<DueReminder> dueReminders(LocalDateTime now, int limit) {
        return jdbcTemplate.query("""
            SELECT tenant_id, id, owner_id, scope, dept_id, title, location, start_time, remind_at
              FROM oa_calendar_event
             WHERE status = 'ACTIVE' AND reminded_at IS NULL AND remind_at <= ?
             ORDER BY remind_at, id
             LIMIT ?
            """, (rs, rowNum) -> new DueReminder(rs.getLong("tenant_id"), rs.getLong("id"), rs.getLong("owner_id"), rs
            .getString("scope"), nullableLong(rs, "dept_id"), rs.getString("title"), rs
                .getString("location"), timestamp(rs, "start_time"), timestamp(rs, "remind_at")), now, limit);
    }

    public int markReminded(long tenantId, long id, LocalDateTime remindAt, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_calendar_event SET reminded_at = ?
             WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE' AND reminded_at IS NULL AND remind_at = ?
            """, now, tenantId, id, remindAt);
    }

    public ReportRow lockReport(long tenantId, long authorId, String type, LocalDate periodStart) {
        List<ReportRow> rows = jdbcTemplate.query("""
            SELECT id, author_id, dept_id, report_type, period_start, period_end, status, submit_time, version
              FROM oa_work_report
             WHERE tenant_id = ? AND author_id = ? AND report_type = ? AND period_start = ?
             FOR UPDATE
            """, REPORT_ROW_MAPPER, tenantId, authorId, type, Date.valueOf(periodStart));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public OaWorkReportResp periodReport(long tenantId, long authorId, String type, LocalDate periodStart) {
        List<OaWorkReportResp> rows = jdbcTemplate.query(REPORT_SELECT + """
             AND r.author_id = ? AND r.report_type = ? AND r.period_start = ?
            """, REPORT_MAPPER, tenantId, authorId, type, Date.valueOf(periodStart));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ReportRow reportRow(long tenantId, long id) {
        List<ReportRow> rows = jdbcTemplate.query("""
            SELECT id, author_id, dept_id, report_type, period_start, period_end, status, submit_time, version
              FROM oa_work_report
             WHERE tenant_id = ? AND id = ?
            """, REPORT_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertReport(long tenantId, long authorId, ReportValues values, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_work_report
                  (tenant_id, author_id, dept_id, report_type, period_start, period_end, done_content, plan_content,
                   issue_content, status, submit_time, version, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, authorId);
            setLong(ps, 3, values.deptId());
            ps.setString(4, values.type());
            ps.setDate(5, Date.valueOf(values.periodStart()));
            ps.setDate(6, Date.valueOf(values.periodEnd()));
            ps.setString(7, values.done());
            ps.setString(8, values.plan());
            ps.setString(9, values.issue());
            ps.setString(10, values.status());
            ps.setTimestamp(11, values.submitTime() == null ? null : Timestamp.valueOf(values.submitTime()));
            ps.setTimestamp(12, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKeyAs(Number.class);
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public int updateDraft(long tenantId, long id, int version, ReportValues values, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_work_report
               SET dept_id = ?, done_content = ?, plan_content = ?, issue_content = ?, status = ?, submit_time = ?,
                   version = version + 1, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'DRAFT'
            """, values.deptId(), values.done(), values.plan(), values.issue(), values.status(), values
            .submitTime(), now, tenantId, id, version);
    }

    public int deleteDraft(long tenantId, long id, long authorId) {
        return jdbcTemplate.update("""
            DELETE FROM oa_work_report WHERE tenant_id = ? AND id = ? AND author_id = ? AND status = 'DRAFT'
            """, tenantId, id, authorId);
    }

    public OaWorkReportResp report(long tenantId, long id) {
        List<OaWorkReportResp> rows = jdbcTemplate.query(REPORT_SELECT + " AND r.id = ?", REPORT_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaWorkReportResp> reports(long tenantId, ReportFilter filter, int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = where(tenantId, filter, args);
        if (where == null) {
            return List.of();
        }
        args.add(limit);
        args.add(offset);
        return jdbcTemplate
            .query(REPORT_SELECT + where + " ORDER BY r.period_start DESC, r.id DESC LIMIT ? OFFSET ?", REPORT_MAPPER, args
                .toArray());
    }

    public long countReports(long tenantId, ReportFilter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(tenantId, filter, args);
        if (where == null) {
            return 0;
        }
        Long count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM oa_work_report r WHERE r.tenant_id = ?" + where, Long.class, args
                .toArray());
        return count == null ? 0 : count;
    }

    public List<ReportRow> periodReports(long tenantId, String type, LocalDate periodStart) {
        return jdbcTemplate.query("""
            SELECT id, author_id, dept_id, report_type, period_start, period_end, status, submit_time, version
              FROM oa_work_report
             WHERE tenant_id = ? AND report_type = ? AND period_start = ? AND status = 'SUBMITTED'
            """, REPORT_ROW_MAPPER, tenantId, type, Date.valueOf(periodStart));
    }

    public void insertComment(long tenantId, long reportId, long userId, String content, LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_work_report_comment (tenant_id, report_id, user_id, content, create_time)
            VALUES (?, ?, ?, ?, ?)
            """, tenantId, reportId, userId, content, now);
    }

    public List<OaWorkReportCommentResp> comments(long tenantId, long reportId) {
        return jdbcTemplate.query("""
            SELECT c.id, c.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, c.content, c.create_time
              FROM oa_work_report_comment c
              LEFT JOIN sys_user u ON u.tenant_id = c.tenant_id AND u.id = c.user_id
             WHERE c.tenant_id = ? AND c.report_id = ?
             ORDER BY c.id
            """, (rs, rowNum) -> new OaWorkReportCommentResp(rs.getLong("id"), rs.getLong("user_id"), rs
            .getString("user_name"), rs.getString("content"), timestamp(rs, "create_time")), tenantId, reportId);
    }

    public static boolean isLate(LocalDateTime submitTime, LocalDate periodEnd) {
        return submitTime != null && periodEnd != null && submitTime.toLocalDate().isAfter(periodEnd);
    }

    /**
     * 返回 null 表示可见范围为空，调用方直接返回空结果。
     */
    private static String where(long tenantId, ReportFilter filter, List<Object> args) {
        args.add(tenantId);
        StringBuilder sql = new StringBuilder();
        if (filter.authorOnly() != null) {
            sql.append(" AND r.author_id = ?");
            args.add(filter.authorOnly());
        } else {
            sql.append(" AND r.status = 'SUBMITTED'");
            if (!filter.all()) {
                Collection<Long> depts = nonNull(filter.visibleDepts());
                Collection<Long> authors = nonNull(filter.visibleAuthors());
                if (depts.isEmpty() && authors.isEmpty()) {
                    return null;
                }
                List<String> parts = new ArrayList<>();
                if (!depts.isEmpty()) {
                    parts.add("r.dept_id IN (" + placeholders(depts.size()) + ")");
                    args.addAll(depts);
                }
                if (!authors.isEmpty()) {
                    parts.add("r.author_id IN (" + placeholders(authors.size()) + ")");
                    args.addAll(authors);
                }
                sql.append(" AND (").append(String.join(" OR ", parts)).append(")");
            }
        }
        if (filter.type() != null) {
            sql.append(" AND r.report_type = ?");
            args.add(filter.type());
        }
        if (filter.periodStart() != null) {
            sql.append(" AND r.period_start = ?");
            args.add(Date.valueOf(filter.periodStart()));
        }
        if (filter.authorId() != null) {
            sql.append(" AND r.author_id = ?");
            args.add(filter.authorId());
        }
        if (filter.deptIds() != null) {
            if (filter.deptIds().isEmpty()) {
                return null;
            }
            sql.append(" AND r.dept_id IN (").append(placeholders(filter.deptIds().size())).append(")");
            args.addAll(filter.deptIds());
        }
        return sql.toString();
    }

    private static int bindValues(PreparedStatement ps, int start, EventValues values) throws java.sql.SQLException {
        ps.setString(start, values.scope());
        setLong(ps, start + 1, values.deptId());
        ps.setString(start + 2, values.title());
        ps.setString(start + 3, values.location());
        ps.setString(start + 4, values.description());
        ps.setTimestamp(start + 5, Timestamp.valueOf(values.start()));
        ps.setTimestamp(start + 6, Timestamp.valueOf(values.end()));
        ps.setBoolean(start + 7, values.allDay());
        if (values.remindMinutes() == null) {
            ps.setNull(start + 8, Types.INTEGER);
        } else {
            ps.setInt(start + 8, values.remindMinutes());
        }
        ps.setTimestamp(start + 9, values.remindAt() == null ? null : Timestamp.valueOf(values.remindAt()));
        return start + 10;
    }

    private static void setLong(PreparedStatement ps, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Integer nullableInt(int value, boolean wasNull) {
        return wasNull ? null : value;
    }

    private static Collection<Long> nonNull(Collection<Long> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /**
     * reminded_at 非空表示创建时提醒时间已过，不再补发。
     */
    public record EventValues(String scope, Long deptId, String title, String location, String description,
                              LocalDateTime start, LocalDateTime end, boolean allDay, Integer remindMinutes,
                              LocalDateTime remindAt, LocalDateTime remindedAt) {
    }

    public record EventRow(long id, long ownerId, String scope, Long deptId, String title, LocalDateTime start,
                           LocalDateTime end, LocalDateTime remindAt, String status, int version) {
    }

    public record DueReminder(long tenantId, long id, long ownerId, String scope, Long deptId, String title,
                              String location, LocalDateTime start, LocalDateTime remindAt) {
    }

    public record ReportValues(Long deptId, String type, LocalDate periodStart, LocalDate periodEnd, String done,
                               String plan, String issue, String status, LocalDateTime submitTime) {
    }

    public record ReportRow(long id, long authorId, Long deptId, String type, LocalDate periodStart,
                            LocalDate periodEnd, String status, LocalDateTime submitTime, int version) {
    }

    /**
     * authorOnly 非空时查询本人全部报告（含草稿）；否则只查已提交且落在可见范围内的报告。
     * deptIds 为附加的部门过滤，非 null 但为空时结果为空。
     */
    public record ReportFilter(Long authorOnly, boolean all, Collection<Long> visibleDepts,
                               Collection<Long> visibleAuthors, String type, LocalDate periodStart, Long authorId,
                               Collection<Long> deptIds) {
    }
}
