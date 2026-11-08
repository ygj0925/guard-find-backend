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

import com.guardfind.backend.oa.model.OaCcResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抄送回执的显式租户 SQL；接收人只能读取自己的回执。
 */
@Repository
public class OaCcRepository {

    private static final RowMapper<OaCcResp> CC_MAPPER = (rs, rowNum) -> new OaCcResp(rs.getLong("id"), rs
        .getLong("application_id"), rs.getString("title"), rs.getString("business_key"), rs
            .getString("application_status"), rs.getString("applicant_name"), rs.getString("applicant_dept_name"), rs
                .getString("model_name"), rs
                    .getString("status"), timestamp(rs, "read_time"), timestamp(rs, "create_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaCcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 返回在职且账号启用的员工姓名，按入参顺序保留。
     */
    public Map<Long, String> activeEmployeeNames(long tenantId, Collection<Long> userIds) {
        Map<Long, String> found = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return found;
        }
        String placeholders = String.join(",", userIds.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(userIds);
        jdbcTemplate.query("""
            SELECT e.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS employee_name
              FROM oa_employee e
              JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
             WHERE e.tenant_id = ? AND e.deleted = 0 AND e.employment_status = 'ACTIVE' AND u.status = 1
               AND e.user_id IN (%s)
            """.formatted(placeholders), rs -> {
            found.put(rs.getLong("user_id"), rs.getString("employee_name"));
        }, args.toArray());
        Map<Long, String> ordered = new LinkedHashMap<>();
        for (Long id : userIds) {
            if (found.containsKey(id)) {
                ordered.put(id, found.get(id));
            }
        }
        return ordered;
    }

    public void insertReceipts(long tenantId,
                               long applicationId,
                               String processInstanceId,
                               Map<Long, String> receivers,
                               String sourceTaskId,
                               long ccBy,
                               LocalDateTime now) {
        List<Object[]> rows = new ArrayList<>();
        receivers.forEach((id, name) -> rows.add(new Object[] {tenantId, applicationId, processInstanceId, id, name,
            sourceTaskId, ccBy, now}));
        jdbcTemplate
            .batchUpdate("""
                INSERT INTO oa_cc_receipt
                  (tenant_id, application_id, process_instance_id, receiver_id, receiver_name, source_task_id, cc_by, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'UNREAD', ?)
                """, rows);
    }

    /**
     * 唯一键按申请+接收人，撤销后再次抄送复用原行并重置为未读。
     */
    public int reactivate(long tenantId,
                          long applicationId,
                          long receiverId,
                          String receiverName,
                          String sourceTaskId,
                          long ccBy,
                          LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_cc_receipt
               SET status = 'UNREAD', read_time = NULL, revoke_by = NULL, revoke_time = NULL,
                   receiver_name = ?, source_task_id = ?, cc_by = ?, create_time = ?
             WHERE tenant_id = ? AND application_id = ? AND receiver_id = ? AND status = 'REVOKED'
            """, receiverName, sourceTaskId, ccBy, now, tenantId, applicationId, receiverId);
    }

    public Map<Long, String> receiptStatuses(long tenantId, long applicationId) {
        Map<Long, String> statuses = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT receiver_id, status FROM oa_cc_receipt WHERE tenant_id = ? AND application_id = ? ORDER BY id
            """, rs -> {
            statuses.put(rs.getLong("receiver_id"), rs.getString("status"));
        }, tenantId, applicationId);
        return statuses;
    }

    public CcReceipt receipt(long tenantId, long applicationId, long receiverId) {
        List<CcReceipt> rows = jdbcTemplate.query("""
            SELECT receiver_id, receiver_name, cc_by, status
              FROM oa_cc_receipt
             WHERE tenant_id = ? AND application_id = ? AND receiver_id = ?
            """, (rs, rowNum) -> new CcReceipt(rs.getLong("receiver_id"), rs.getString("receiver_name"), rs
            .getObject("cc_by") == null ? null : rs.getLong("cc_by"), rs
                .getString("status")), tenantId, applicationId, receiverId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int revoke(long tenantId, long applicationId, long receiverId, long revokeBy, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_cc_receipt
               SET status = 'REVOKED', revoke_by = ?, revoke_time = ?
             WHERE tenant_id = ? AND application_id = ? AND receiver_id = ? AND status <> 'REVOKED'
            """, revokeBy, now, tenantId, applicationId, receiverId);
    }

    public List<OaCcResp> page(long tenantId, long receiverId, String keyword, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT c.id, c.application_id, a.title, a.business_key, a.status AS application_status,
                   a.applicant_name, a.applicant_dept_name, m.name AS model_name, c.status, c.read_time, c.create_time
              FROM oa_cc_receipt c
              JOIN oa_application a ON a.tenant_id = c.tenant_id AND a.id = c.application_id AND a.deleted = 0
              LEFT JOIN oa_process_release r ON r.tenant_id = a.tenant_id AND r.id = a.release_id
              LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
             WHERE c.tenant_id = ? AND c.receiver_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(receiverId);
        appendFilters(sql, args, keyword, status);
        sql.append(" ORDER BY c.create_time DESC, c.id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), CC_MAPPER, args.toArray());
    }

    public long count(long tenantId, long receiverId, String keyword, String status) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
              FROM oa_cc_receipt c
              JOIN oa_application a ON a.tenant_id = c.tenant_id AND a.id = c.application_id AND a.deleted = 0
             WHERE c.tenant_id = ? AND c.receiver_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(receiverId);
        appendFilters(sql, args, keyword, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public boolean isReceiver(long tenantId, long applicationId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_cc_receipt
             WHERE tenant_id = ? AND application_id = ? AND receiver_id = ? AND status <> 'REVOKED'
            """, Integer.class, tenantId, applicationId, userId);
        return count != null && count > 0;
    }

    public int markRead(long tenantId, long applicationId, long receiverId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_cc_receipt
               SET status = 'READ', read_time = ?
             WHERE tenant_id = ? AND application_id = ? AND receiver_id = ? AND status = 'UNREAD'
            """, now, tenantId, applicationId, receiverId);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, String keyword, String status) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR a.business_key LIKE ? OR a.applicant_name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND c.status = ?");
            args.add(status.trim());
        } else {
            sql.append(" AND c.status <> 'REVOKED'");
        }
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record CcReceipt(long receiverId, String receiverName, Long ccBy, String status) {
    }
}
