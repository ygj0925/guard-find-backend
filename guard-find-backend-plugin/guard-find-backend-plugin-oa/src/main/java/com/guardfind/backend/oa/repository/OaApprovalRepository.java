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

import com.guardfind.backend.oa.model.OaDoneRecordResp;
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
 * 审批动作的显式租户 SQL：申请状态、已办记录与不可变审计。
 */
@Repository
public class OaApprovalRepository {

    private static final String DONE_ACTION_FILTER = " AND au.action IN ('APPROVE', 'REJECT', 'RETURN')";
    private static final String OWN_ACTION_FILTER = " AND action IN ('APPROVE', 'REJECT', 'RETURN')";

    private static final RowMapper<OaDoneRecordResp> DONE_MAPPER = (rs, rowNum) -> new OaDoneRecordResp(rs
        .getLong("application_id"), rs.getString("title"), rs.getString("business_key"), rs.getString("model_name"), rs
            .getString("form_name"), rs.getString("applicant_name"), rs.getString("action"), rs.getString("reason"), rs
                .getString("task_id"), timestamp(rs, "create_time"), rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public OaApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ApprovalApplication applicationByInstanceId(long tenantId, String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return null;
        }
        List<ApprovalApplication> rows = jdbcTemplate.query("""
            SELECT id, applicant_id, status, process_instance_id
              FROM oa_application
             WHERE tenant_id = ? AND process_instance_id = ? AND deleted = 0
            """, (rs, rowNum) -> new ApprovalApplication(rs.getLong("id"), rs.getLong("applicant_id"), rs
            .getString("status"), rs.getString("process_instance_id")), tenantId, processInstanceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 撤回前加行锁，避免与审批动作并发时读到已过期的“无审批动作”快照。
     */
    public ApprovalApplication lockApplication(long tenantId, long applicationId) {
        List<ApprovalApplication> rows = jdbcTemplate.query("""
            SELECT id, applicant_id, status, process_instance_id
              FROM oa_application
             WHERE tenant_id = ? AND id = ? AND deleted = 0
             FOR UPDATE
            """, (rs, rowNum) -> new ApprovalApplication(rs.getLong("id"), rs.getLong("applicant_id"), rs
            .getString("status"), rs.getString("process_instance_id")), tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int touchRunning(long tenantId, long applicationId, long actorId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_application
               SET update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND status = 'RUNNING' AND deleted = 0
            """, actorId, now, tenantId, applicationId);
    }

    public Map<String, TodoApplication> todoApplications(long tenantId, Collection<String> processInstanceIds) {
        if (processInstanceIds == null || processInstanceIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(processInstanceIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(processInstanceIds);
        Map<String, TodoApplication> applications = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT a.id, a.process_instance_id, a.business_key, a.title, a.applicant_name, a.applicant_dept_name,
                   a.submitted_time, m.name AS model_name, f.name AS form_name
              FROM oa_application a
              LEFT JOIN oa_process_release r ON r.tenant_id = a.tenant_id AND r.id = a.release_id
              LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
              LEFT JOIN oa_form_version v ON v.tenant_id = a.tenant_id AND v.id = a.form_version_id
              LEFT JOIN oa_form f ON f.tenant_id = v.tenant_id AND f.id = v.form_id
             WHERE a.tenant_id = ? AND a.deleted = 0 AND a.process_instance_id IN (%s)
            """.formatted(placeholders), rs -> {
            applications.put(rs.getString("process_instance_id"), new TodoApplication(rs.getLong("id"), rs
                .getString("business_key"), rs.getString("title"), rs.getString("applicant_name"), rs
                    .getString("applicant_dept_name"), rs.getString("model_name"), rs
                        .getString("form_name"), timestamp(rs, "submitted_time")));
        }, args.toArray());
        return applications;
    }

    public List<OaDoneRecordResp> donePage(long tenantId, long operatorId, String keyword, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT a.id AS application_id, a.title, a.business_key, a.status, a.applicant_name,
                   m.name AS model_name, f.name AS form_name,
                   au.action, au.reason, au.task_id, au.create_time
              FROM oa_approval_audit au
              JOIN oa_application a ON a.tenant_id = au.tenant_id AND a.id = au.application_id AND a.deleted = 0
              LEFT JOIN oa_process_release r ON r.tenant_id = a.tenant_id AND r.id = a.release_id
              LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
              LEFT JOIN oa_form_version v ON v.tenant_id = a.tenant_id AND v.id = a.form_version_id
              LEFT JOIN oa_form f ON f.tenant_id = v.tenant_id AND f.id = v.form_id
             WHERE au.tenant_id = ? AND au.operator_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(operatorId);
        sql.append(DONE_ACTION_FILTER);
        appendKeyword(sql, args, keyword);
        sql.append(" ORDER BY au.create_time DESC, au.id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), DONE_MAPPER, args.toArray());
    }

    public long doneCount(long tenantId, long operatorId, String keyword) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
              FROM oa_approval_audit au
              JOIN oa_application a ON a.tenant_id = au.tenant_id AND a.id = au.application_id AND a.deleted = 0
             WHERE au.tenant_id = ? AND au.operator_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(operatorId);
        sql.append(DONE_ACTION_FILTER);
        appendKeyword(sql, args, keyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * 已办详情回看：仅允许查看本人对该任务留下的审批动作。
     */
    public Long auditApplicationId(long tenantId, String taskId, long operatorId) {
        List<Long> ids = jdbcTemplate.queryForList("""
            SELECT application_id
              FROM oa_approval_audit
             WHERE tenant_id = ? AND task_id = ? AND operator_id = ?
            """ + OWN_ACTION_FILTER + " ORDER BY id DESC LIMIT 1", Long.class, tenantId, taskId, operatorId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public long effectiveApprovalCount(long tenantId, long applicationId) {
        Long total = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
              FROM oa_approval_audit
             WHERE tenant_id = ? AND application_id = ?
            """ + OWN_ACTION_FILTER, Long.class, tenantId, applicationId);
        return total == null ? 0 : total;
    }

    public int updateStatus(long tenantId,
                            long applicationId,
                            String expectedStatus,
                            String newStatus,
                            LocalDateTime finishedTime,
                            long actorId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_application
               SET status = ?, finished_time = ?, update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND status = ? AND deleted = 0
            """, newStatus, finishedTime, actorId, now, tenantId, applicationId, expectedStatus);
    }

    public void insertAudit(long tenantId,
                            long applicationId,
                            String processInstanceId,
                            String taskId,
                            String action,
                            long operatorId,
                            String operatorName,
                            Long originalAssigneeId,
                            String reason,
                            String requestId,
                            String detailJson,
                            LocalDateTime now) {
        jdbcTemplate
            .update("""
                INSERT INTO oa_approval_audit
                  (tenant_id, application_id, process_instance_id, task_id, action, operator_id, operator_name,
                   original_assignee_id, reason, request_id, detail_json, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, applicationId, processInstanceId, taskId, action, operatorId, operatorName, originalAssigneeId, reason, requestId, detailJson, now);
    }

    public String userName(long tenantId, long userId) {
        List<String> names = jdbcTemplate.queryForList("""
            SELECT COALESCE(NULLIF(nickname, ''), username)
              FROM sys_user
             WHERE tenant_id = ? AND id = ? AND deleted = 0
            """, String.class, tenantId, userId);
        return names.isEmpty() ? null : names.get(0);
    }

    private static void appendKeyword(StringBuilder sql, List<Object> args, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR a.business_key LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record ApprovalApplication(long id, long applicantId, String status, String processInstanceId) {
    }

    public record TodoApplication(long id, String businessKey, String title, String applicantName,
                                  String applicantDeptName, String modelName, String formName,
                                  LocalDateTime submittedTime) {
    }
}
