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

import com.guardfind.backend.oa.model.OaOpsAuditResp;
import com.guardfind.backend.oa.model.OaOpsExportResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 运维干预审计（只追加）与租户内导出任务巡检的显式租户 SQL。
 */
@Repository
public class OaOpsRepository {

    private final JdbcTemplate jdbcTemplate;

    public OaOpsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertAudit(long tenantId,
                            String action,
                            String targetType,
                            String targetId,
                            long operatorId,
                            String operatorName,
                            String reason,
                            String detailJson,
                            LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_ops_audit
              (tenant_id, action, target_type, target_id, operator_id, operator_name, reason, detail_json, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, action, targetType, targetId, operatorId, operatorName, reason, detailJson, now);
    }

    public List<OaOpsAuditResp> pageAudits(long tenantId, String action, int offset, int size) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String filter = filter("action", action, args);
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query("""
            SELECT id, action, target_type, target_id, operator_id, operator_name, reason, detail_json, create_time
              FROM oa_ops_audit
             WHERE tenant_id = ?%s
             ORDER BY create_time DESC, id DESC
             LIMIT ? OFFSET ?
            """.formatted(filter), (rs, rowNum) -> new OaOpsAuditResp(rs.getLong("id"), rs.getString("action"), rs
            .getString("target_type"), rs.getString("target_id"), rs.getLong("operator_id"), rs
                .getString("operator_name"), rs.getString("reason"), rs
                    .getString("detail_json"), timestamp(rs, "create_time")), args.toArray());
    }

    public long countAudits(long tenantId, String action) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String filter = filter("action", action, args);
        Long count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM oa_ops_audit WHERE tenant_id = ?" + filter, Long.class, args
                .toArray());
        return count == null ? 0 : count;
    }

    public List<OaOpsExportResp> pageExports(long tenantId, String status, int offset, int size) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String filter = filter("t.status", status, args);
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query("""
            SELECT t.id, t.user_id, u.nickname AS user_name, t.export_type, t.date_from, t.date_to, t.business_type,
                   t.status, t.row_count, t.error_message, t.create_time, t.finish_time
              FROM oa_export_task t
              LEFT JOIN sys_user u ON u.tenant_id = t.tenant_id AND u.id = t.user_id
             WHERE t.tenant_id = ?%s
             ORDER BY t.id DESC
             LIMIT ? OFFSET ?
            """.formatted(filter), (rs, rowNum) -> {
            int rowCount = rs.getInt("row_count");
            return new OaOpsExportResp(rs.getLong("id"), rs.getLong("user_id"), rs.getString("user_name"), rs
                .getString("export_type"), date(rs, "date_from"), date(rs, "date_to"), rs.getString("business_type"), rs
                    .getString("status"), rs.wasNull() ? null : rowCount, rs
                        .getString("error_message"), timestamp(rs, "create_time"), timestamp(rs, "finish_time"));
        }, args.toArray());
    }

    public long countExports(long tenantId, String status) {
        List<Object> args = new ArrayList<>(List.of(tenantId));
        String filter = filter("t.status", status, args);
        Long count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM oa_export_task t WHERE t.tenant_id = ?" + filter, Long.class, args
                .toArray());
        return count == null ? 0 : count;
    }

    private static String filter(String column, String value, List<Object> args) {
        if (value == null || value.isBlank()) {
            return "";
        }
        args.add(value.trim());
        return " AND " + column + " = ?";
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
