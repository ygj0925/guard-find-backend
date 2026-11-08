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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaApplicationDetailResp;
import com.guardfind.backend.oa.model.OaApplicationResp;
import com.guardfind.backend.oa.model.OaApplicationTimelineResp;
import com.guardfind.backend.oa.model.OaCcReceiverResp;
import com.guardfind.backend.oa.model.OaLaunchItemResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发起与申请读写的显式租户 SQL。
 *
 * <p>
 * 可发起入口只读取模型当前生效、表单未停用的发布版本；申请查询始终限定发起人，
 * 避免普通员工通过 ID 探测他人申请。
 * </p>
 */
@Repository
public class OaLaunchRepository {

    private static final int MAX_LAUNCH_CANDIDATES = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String LAUNCH_SELECT = """
        SELECT r.id AS release_id, r.model_id, m.model_key, m.name AS model_name, m.category, m.description,
               r.release_version, r.process_definition_key, v.form_id, r.form_version_id, r.form_version,
               f.name AS form_name, f.form_key, r.launch_scope, r.publish_time
          FROM oa_process_release r
          JOIN oa_process_model m
            ON m.tenant_id = r.tenant_id AND m.id = r.model_id AND m.deleted = 0
           AND m.status = 'PUBLISHED' AND m.current_release_id = r.id
          JOIN oa_form_version v
            ON v.tenant_id = r.tenant_id AND v.id = r.form_version_id
          JOIN oa_form f
            ON f.tenant_id = v.tenant_id AND f.id = v.form_id AND f.deleted = 0 AND f.status = 'PUBLISHED'
         WHERE r.tenant_id = ? AND r.status = 'ACTIVE'
        """;

    private static final String LAUNCH_RELEASE_SELECT = """
        SELECT r.id AS release_id, r.model_id, m.model_key, m.name AS model_name, m.category, m.description,
               r.release_version, r.process_definition_key, v.form_id, r.form_version_id, r.form_version,
               f.name AS form_name, f.form_key, r.launch_scope, r.publish_time,
               r.bpmn_xml, v.schema_json, v.schema_digest, m.business_type
          FROM oa_process_release r
          JOIN oa_process_model m
            ON m.tenant_id = r.tenant_id AND m.id = r.model_id AND m.deleted = 0
           AND m.status = 'PUBLISHED' AND m.current_release_id = r.id
          JOIN oa_form_version v
            ON v.tenant_id = r.tenant_id AND v.id = r.form_version_id
          JOIN oa_form f
            ON f.tenant_id = v.tenant_id AND f.id = v.form_id AND f.deleted = 0 AND f.status = 'PUBLISHED'
         WHERE r.tenant_id = ? AND r.id = ? AND r.status = 'ACTIVE' AND r.form_version_id IS NOT NULL
        """;

    private static final RowMapper<OaLaunchItemResp> LAUNCH_MAPPER = (rs, rowNum) -> new OaLaunchItemResp(rs
        .getLong("release_id"), rs.getLong("model_id"), rs.getString("model_key"), rs.getString("model_name"), rs
            .getString("category"), rs.getString("description"), rs.getInt("release_version"), rs
                .getString("process_definition_key"), rs.getLong("form_id"), rs.getLong("form_version_id"), rs
                    .getInt("form_version"), rs.getString("form_name"), rs.getString("form_key"), rs
                        .getString("launch_scope"), timestamp(rs, "publish_time"));

    private static final RowMapper<OaLaunchRelease> LAUNCH_RELEASE_MAPPER = (rs, rowNum) -> new OaLaunchRelease(rs
        .getLong("release_id"), rs.getLong("model_id"), rs.getString("model_key"), rs.getString("model_name"), rs
            .getString("category"), rs.getString("description"), rs.getInt("release_version"), rs
                .getString("process_definition_key"), rs.getLong("form_id"), rs.getLong("form_version_id"), rs
                    .getInt("form_version"), rs.getString("form_name"), rs.getString("form_key"), rs
                        .getString("launch_scope"), timestamp(rs, "publish_time"), rs.getString("bpmn_xml"), rs
                            .getString("schema_json"), rs.getString("schema_digest"), rs.getString("business_type"));

    private static final RowMapper<OaApplicationResp> APPLICATION_MAPPER = (rs, rowNum) -> new OaApplicationResp(rs
        .getLong("id"), rs.getString("business_type"), rs.getString("business_key"), rs.getString("title"), rs
            .getString("status"), nullableInt(rs, "form_version"), rs.getString("process_instance_id"), rs
                .getString("model_name"), rs
                    .getString("form_name"), timestamp(rs, "submitted_time"), timestamp(rs, "finished_time"), timestamp(rs, "create_time"));

    private static final RowMapper<OaApplicationTimelineResp> TIMELINE_MAPPER = (rs,
                                                                                 rowNum) -> new OaApplicationTimelineResp(rs
                                                                                     .getString("action"), nullableLong(rs, "operator_id"), rs
                                                                                         .getString("operator_name"), rs
                                                                                             .getString("task_id"), rs
                                                                                                 .getString("reason"), timestamp(rs, "create_time"), nullableLong(rs, "original_assignee_id"), null, targetUserId(rs
                                                                                                     .getString("detail_json")), null);

    private final JdbcTemplate jdbcTemplate;

    public OaLaunchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaLaunchItemResp> activeLaunches(long tenantId, String keyword, String category) {
        StringBuilder sql = new StringBuilder(LAUNCH_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (m.name LIKE ? OR m.model_key LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND m.category = ?");
            args.add(category.trim());
        }
        sql.append(" ORDER BY r.publish_time DESC, r.id DESC LIMIT ").append(MAX_LAUNCH_CANDIDATES);
        return jdbcTemplate.query(sql.toString(), LAUNCH_MAPPER, args.toArray());
    }

    public OaLaunchRelease launchRelease(long tenantId, long releaseId) {
        List<OaLaunchRelease> releases = jdbcTemplate
            .query(LAUNCH_RELEASE_SELECT, LAUNCH_RELEASE_MAPPER, tenantId, releaseId);
        return releases.isEmpty() ? null : releases.get(0);
    }

    public String applicationBusinessType(long tenantId, long applicationId) {
        List<String> types = jdbcTemplate.queryForList("""
            SELECT m.business_type
              FROM oa_application a
              JOIN oa_process_release r ON r.tenant_id = a.tenant_id AND r.id = a.release_id
              JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
             WHERE a.tenant_id = ? AND a.id = ?
            """, String.class, tenantId, applicationId);
        return types.isEmpty() ? null : types.get(0);
    }

    public ApplicantProfile applicantProfile(long tenantId, long userId) {
        List<ApplicantProfile> users = jdbcTemplate.query("""
            SELECT id, COALESCE(NULLIF(nickname, ''), username) AS display_name, dept_id
              FROM sys_user
             WHERE tenant_id = ? AND id = ? AND deleted = 0
            """, (rs, rowNum) -> new ApplicantProfile(rs.getLong("id"), rs
            .getString("display_name"), nullableLong(rs, "dept_id"), null, Set.of()), tenantId, userId);
        if (users.isEmpty()) {
            return null;
        }
        ApplicantProfile user = users.get(0);
        String deptName = null;
        if (user.deptId() != null) {
            List<String> names = jdbcTemplate.queryForList("""
                SELECT name FROM sys_dept WHERE tenant_id = ? AND id = ? AND deleted = 0
                """, String.class, tenantId, user.deptId());
            deptName = names.isEmpty() ? null : names.get(0);
        }
        Set<String> roleCodes = new LinkedHashSet<>(jdbcTemplate.queryForList("""
            SELECT r.code
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id AND r.deleted = 0
             WHERE ur.tenant_id = ? AND ur.user_id = ?
            """, String.class, tenantId, userId));
        return new ApplicantProfile(user.userId(), user.name(), user.deptId(), deptName, Set.copyOf(roleCodes));
    }

    public DedupRecord dedup(long tenantId, long actorId, String command, String requestId) {
        List<DedupRecord> records = jdbcTemplate.query("""
            SELECT status, result_json
              FROM oa_request_dedup
             WHERE tenant_id = ? AND actor_id = ? AND command = ? AND request_id = ?
            """, (rs, rowNum) -> new DedupRecord(rs.getString("status"), rs
            .getString("result_json")), tenantId, actorId, command, requestId);
        return records.isEmpty() ? null : records.get(0);
    }

    public void insertDedup(long tenantId,
                            long actorId,
                            String command,
                            String requestId,
                            LocalDateTime now,
                            LocalDateTime expireTime) {
        jdbcTemplate.update("""
            INSERT INTO oa_request_dedup
              (tenant_id, actor_id, command, request_id, status, result_json, create_time, expire_time)
            VALUES (?, ?, ?, ?, 'PROCESSING', NULL, ?, ?)
            """, tenantId, actorId, command, requestId, now, expireTime);
    }

    public int markDedupSucceeded(long tenantId, long actorId, String command, String requestId, String resultJson) {
        return jdbcTemplate.update("""
            UPDATE oa_request_dedup
               SET status = 'SUCCEEDED', result_json = ?
             WHERE tenant_id = ? AND actor_id = ? AND command = ? AND request_id = ?
            """, resultJson, tenantId, actorId, command, requestId);
    }

    public long insertApplication(long tenantId,
                                  String businessType,
                                  String businessKey,
                                  String title,
                                  long applicantId,
                                  String applicantName,
                                  Long applicantDeptId,
                                  String applicantDeptName,
                                  long releaseId,
                                  Long formVersionId,
                                  Integer formVersion,
                                  String formDataJson,
                                  String processInstanceId,
                                  LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_application
                  (tenant_id, business_type, business_key, title, applicant_id, applicant_name,
                   applicant_dept_id, applicant_dept_name, release_id, form_version_id, form_version,
                   form_data, process_instance_id, status, submitted_time, version, create_user, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, 0, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, businessType);
            ps.setString(3, businessKey);
            ps.setString(4, title);
            ps.setLong(5, applicantId);
            ps.setString(6, applicantName);
            ps.setObject(7, applicantDeptId);
            ps.setString(8, applicantDeptName);
            ps.setLong(9, releaseId);
            ps.setObject(10, formVersionId);
            ps.setObject(11, formVersion);
            ps.setString(12, formDataJson);
            ps.setString(13, processInstanceId);
            ps.setObject(14, now);
            ps.setLong(15, applicantId);
            ps.setObject(16, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public void insertAudit(long tenantId,
                            long applicationId,
                            String processInstanceId,
                            String action,
                            long operatorId,
                            String operatorName,
                            String requestId,
                            String detailJson,
                            LocalDateTime now) {
        jdbcTemplate
            .update("""
                INSERT INTO oa_approval_audit
                  (tenant_id, application_id, process_instance_id, task_id, action, operator_id, operator_name,
                   original_assignee_id, reason, request_id, detail_json, create_time)
                VALUES (?, ?, ?, NULL, ?, ?, ?, NULL, NULL, ?, ?, ?)
                """, tenantId, applicationId, processInstanceId, action, operatorId, operatorName, requestId, detailJson, now);
    }

    public List<OaApplicationResp> applicationPage(long tenantId,
                                                   long applicantId,
                                                   String keyword,
                                                   String status,
                                                   int offset,
                                                   int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT a.id, a.business_type, a.business_key, a.title, a.status, a.form_version,
                   a.process_instance_id, m.name AS model_name, f.name AS form_name,
                   a.submitted_time, a.finished_time, a.create_time
              FROM oa_application a
              LEFT JOIN oa_process_release r ON r.tenant_id = a.tenant_id AND r.id = a.release_id
              LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
              LEFT JOIN oa_form_version v ON v.tenant_id = a.tenant_id AND v.id = a.form_version_id
              LEFT JOIN oa_form f ON f.tenant_id = v.tenant_id AND f.id = v.form_id
             WHERE a.tenant_id = ? AND a.applicant_id = ? AND a.deleted = 0
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(applicantId);
        appendApplicationFilters(sql, args, keyword, status);
        sql.append(" ORDER BY a.submitted_time DESC, a.id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), APPLICATION_MAPPER, args.toArray());
    }

    public long applicationCount(long tenantId, long applicantId, String keyword, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM oa_application a WHERE a.tenant_id = ? AND a.applicant_id = ? AND a.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(applicantId);
        appendApplicationFilters(sql, args, keyword, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public OaApplicationDetailResp applicationDetail(long tenantId, long applicantId, long applicationId) {
        return loadApplicationDetail(tenantId, applicantId, applicationId);
    }

    /**
     * 审批人详情：不按发起人过滤，调用方必须先完成任务授权。
     */
    public OaApplicationDetailResp applicationDetailById(long tenantId, long applicationId) {
        return loadApplicationDetail(tenantId, null, applicationId);
    }

    private OaApplicationDetailResp loadApplicationDetail(long tenantId, Long applicantId, long applicationId) {
        StringBuilder sql = new StringBuilder("""
            SELECT a.id, a.business_type, a.business_key, a.title, a.status, a.applicant_id, a.applicant_name,
                   a.applicant_dept_id, a.applicant_dept_name, a.release_id, r.release_version,
                   a.form_version_id, a.form_version, f.name AS form_name, f.form_key,
                   m.name AS model_name, m.model_key, v.schema_json, a.form_data, a.process_instance_id,
                   a.submitted_time, a.finished_time, a.create_time
              FROM oa_application a
              LEFT JOIN oa_process_release r ON r.tenant_id = a.tenant_id AND r.id = a.release_id
              LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
              LEFT JOIN oa_form_version v ON v.tenant_id = a.tenant_id AND v.id = a.form_version_id
              LEFT JOIN oa_form f ON f.tenant_id = v.tenant_id AND f.id = v.form_id
             WHERE a.tenant_id = ? AND a.id = ? AND a.deleted = 0
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(applicationId);
        if (applicantId != null) {
            sql.append(" AND a.applicant_id = ?");
            args.add(applicantId);
        }
        List<OaApplicationDetailResp> details = jdbcTemplate.query(sql.toString(), (rs,
                                                                                    rowNum) -> new OaApplicationDetailResp(rs
                                                                                        .getLong("id"), rs
                                                                                            .getString("business_type"), rs
                                                                                                .getString("business_key"), rs
                                                                                                    .getString("title"), rs
                                                                                                        .getString("status"), rs
                                                                                                            .getLong("applicant_id"), rs
                                                                                                                .getString("applicant_name"), nullableLong(rs, "applicant_dept_id"), rs
                                                                                                                    .getString("applicant_dept_name"), rs
                                                                                                                        .getLong("release_id"), nullableInt(rs, "release_version"), nullableLong(rs, "form_version_id"), nullableInt(rs, "form_version"), rs
                                                                                                                            .getString("form_name"), rs
                                                                                                                                .getString("form_key"), rs
                                                                                                                                    .getString("model_name"), rs
                                                                                                                                        .getString("model_key"), rs
                                                                                                                                            .getString("schema_json"), parseMap(rs
                                                                                                                                                .getString("form_data")), rs
                                                                                                                                                    .getString("process_instance_id"), timestamp(rs, "submitted_time"), timestamp(rs, "finished_time"), timestamp(rs, "create_time"), List
                                                                                                                                                        .of(), Map
                                                                                                                                                            .of(), List
                                                                                                                                                                .of()), args
                                                                                                                                                                    .toArray());
        if (details.isEmpty()) {
            return null;
        }
        OaApplicationDetailResp row = details.get(0);
        OaApplicationDetailResp detail = row.withFormLabels(personLabels(tenantId, row.schemaJson(), row.formData()));
        return detail.withTimeline(timeline(tenantId, applicationId))
            .withCcReceivers(ccReceivers(tenantId, applicationId));
    }

    public List<OaCcReceiverResp> ccReceivers(long tenantId, long applicationId) {
        return jdbcTemplate.query("""
            SELECT c.receiver_id, c.receiver_name, c.status, c.read_time, c.cc_by, c.source_task_id, c.revoke_time,
                   COALESCE(NULLIF(u.nickname, ''), u.username) AS cc_by_name
              FROM oa_cc_receipt c
              LEFT JOIN sys_user u ON u.id = c.cc_by AND u.tenant_id = c.tenant_id
             WHERE c.tenant_id = ? AND c.application_id = ?
             ORDER BY c.id
            """, (rs, rowNum) -> new OaCcReceiverResp(rs.getLong("receiver_id"), rs.getString("receiver_name"), rs
            .getString("status"), timestamp(rs, "read_time"), nullableLong(rs, "cc_by"), rs.getString("cc_by_name"), rs
                .getString("source_task_id"), timestamp(rs, "revoke_time")), tenantId, applicationId);
    }

    /**
     * 人员字段存的是用户 ID；详情返回 key→姓名映射，避免页面直接暴露 ID。
     */
    private Map<String, String> personLabels(long tenantId, String schemaJson, Map<String, Object> formData) {
        if (schemaJson == null || schemaJson.isBlank() || formData.isEmpty()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(schemaJson);
        } catch (Exception e) {
            return Map.of();
        }
        Map<String, List<Long>> fieldIds = new LinkedHashMap<>();
        Set<Long> allIds = new LinkedHashSet<>();
        for (JsonNode field : root.path("fields")) {
            if (!"person".equals(field.path("type").asText())) {
                continue;
            }
            String key = field.path("key").asText("");
            if (key.isEmpty()) {
                continue;
            }
            List<Long> ids = personIds(formData.get(key));
            if (!ids.isEmpty()) {
                fieldIds.put(key, ids);
                allIds.addAll(ids);
            }
        }
        if (allIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = employeeNames(tenantId, allIds);
        Map<String, String> labels = new LinkedHashMap<>();
        fieldIds.forEach((key, ids) -> {
            String joined = ids.stream()
                .map(names::get)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.joining("、"));
            if (!joined.isEmpty()) {
                labels.put(key, joined);
            }
        });
        return labels;
    }

    private static List<Long> personIds(Object value) {
        List<?> items = value instanceof List<?> list ? list : List.of(value);
        List<Long> ids = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Number number && number.longValue() > 0) {
                ids.add(number.longValue());
            } else if (item instanceof CharSequence text) {
                try {
                    long id = Long.parseLong(text.toString().trim());
                    if (id > 0) {
                        ids.add(id);
                    }
                } catch (NumberFormatException ignored) {
                    // 非 ID 值不参与姓名解析
                }
            }
        }
        return ids;
    }

    private Map<Long, String> employeeNames(long tenantId, Set<Long> ids) {
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        Map<Long, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT e.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS employee_name
              FROM oa_employee e
              LEFT JOIN sys_user u
                ON u.id = e.user_id AND u.deleted = 0
             WHERE e.tenant_id = ? AND e.deleted = 0 AND e.user_id IN (%s)
            """.formatted(placeholders), rs -> {
            names.put(rs.getLong("user_id"), rs.getString("employee_name"));
        }, args.toArray());
        return names;
    }

    public List<OaApplicationTimelineResp> timeline(long tenantId, long applicationId) {
        List<OaApplicationTimelineResp> rows = jdbcTemplate.query("""
            SELECT action, operator_id, operator_name, task_id, reason, create_time, original_assignee_id, detail_json
              FROM oa_approval_audit
             WHERE tenant_id = ? AND application_id = ?
             ORDER BY create_time, id
            """, TIMELINE_MAPPER, tenantId, applicationId);
        Set<Long> ids = new LinkedHashSet<>();
        for (OaApplicationTimelineResp row : rows) {
            if (row.originalAssigneeId() != null) {
                ids.add(row.originalAssigneeId());
            }
            if (row.targetUserId() != null) {
                ids.add(row.targetUserId());
            }
        }
        if (ids.isEmpty()) {
            return rows;
        }
        Map<Long, String> names = employeeNames(tenantId, ids);
        return rows.stream().map(row -> row.withNames(names)).toList();
    }

    private static Long targetUserId(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            JsonNode target = OBJECT_MAPPER.readTree(detailJson).path("targetUserId");
            if (target.isMissingNode() || target.isNull()) {
                return null;
            }
            long id = target.isNumber() ? target.asLong() : Long.parseLong(target.asText().trim());
            return id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendApplicationFilters(StringBuilder sql, List<Object> args, String keyword, String status) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR a.business_key LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND a.status = ?");
            args.add(status.trim());
        }
    }

    private static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("申请表单数据快照不是合法 JSON", e);
        }
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number)value).longValue();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number)value).intValue();
    }

    public record OaLaunchRelease(long releaseId, long modelId, String modelKey, String modelName, String category,
                                  String description, int releaseVersion, String processDefinitionKey, long formId,
                                  long formVersionId, int formVersion, String formName, String formKey,
                                  String launchScope, LocalDateTime publishTime, String bpmnXml, String schemaJson,
                                  String schemaDigest, String businessType) {
    }

    public record ApplicantProfile(long userId, String name, Long deptId, String deptName, Set<String> roleCodes) {

        public ApplicantProfile {
            roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
        }
    }

    public record DedupRecord(String status, String resultJson) {
    }
}
