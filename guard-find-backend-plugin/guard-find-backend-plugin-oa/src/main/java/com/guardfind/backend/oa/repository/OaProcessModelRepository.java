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

import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaProcessReleaseResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程模型与发布版本的显式租户 SQL。
 *
 * <p>
 * 所有读写都带 tenant_id，不依赖全局数据拦截器；发布版本不可更新或删除。
 * </p>
 */
@Repository
public class OaProcessModelRepository {

    private static final String MODEL_COLUMNS = """
        id, model_key, name, category, description, bpmn_xml, xml_digest, status,
        current_release_id, current_release_version, form_id, form_version_id,
        version, create_time, update_time, business_type
        """;

    /** 列表不读取 bpmn_xml，避免分页响应携带完整 XML。 */
    private static final String MODEL_LIST_COLUMNS = """
        id, model_key, name, category, description, NULL AS bpmn_xml, xml_digest, status,
        current_release_id, current_release_version, form_id, form_version_id,
        version, create_time, update_time, business_type
        """;

    private static final RowMapper<OaProcessModelResp> MODEL_MAPPER = (rs, rowNum) -> new OaProcessModelResp(rs
        .getLong("id"), rs.getString("model_key"), rs.getString("name"), rs.getString("category"), rs
            .getString("description"), rs.getString("bpmn_xml"), rs.getString("xml_digest"), rs
                .getString("status"), nullableLong(rs.getObject("current_release_id")), rs
                    .getInt("current_release_version"), nullableLong(rs.getObject("form_id")), nullableLong(rs
                        .getObject("form_version_id")), rs.getInt("version"), rs.getTimestamp("create_time")
                            .toLocalDateTime(), rs.getTimestamp("update_time") == null
                                ? null
                                : rs.getTimestamp("update_time").toLocalDateTime(), rs.getString("business_type"));

    private static final RowMapper<OaProcessReleaseResp> RELEASE_MAPPER = (rs, rowNum) -> new OaProcessReleaseResp(rs
        .getLong("id"), rs.getInt("release_version"), rs.getString("engine_definition_id"), rs
            .getString("process_definition_key"), rs.getInt("process_definition_version"), nullableLong(rs
                .getObject("form_id")), nullableLong(rs.getObject("form_version_id")), rs
                    .getObject("form_version") == null ? null : rs.getInt("form_version"), rs
                        .getString("launch_scope"), rs.getString("status"), rs.getLong("published_by"), rs
                            .getTimestamp("publish_time")
                            .toLocalDateTime(), rs.getString("remark"), rs.getString("xml_digest"));

    private final JdbcTemplate jdbcTemplate;

    public OaProcessModelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaProcessModelResp> page(long tenantId, String keyword, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT ").append(MODEL_LIST_COLUMNS)
            .append(" FROM oa_process_model WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, keyword, status);
        sql.append(" ORDER BY update_time DESC, id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MODEL_MAPPER, args.toArray());
    }

    public long count(long tenantId, String keyword, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM oa_process_model WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, keyword, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public OaProcessModelResp detail(long tenantId, long id) {
        List<OaProcessModelResp> models = jdbcTemplate
            .query("SELECT " + MODEL_COLUMNS + " FROM oa_process_model WHERE tenant_id = ? AND id = ? AND deleted = 0", MODEL_MAPPER, tenantId, id);
        return models.isEmpty() ? null : models.get(0);
    }

    public OaProcessModelResp lock(long tenantId, long id) {
        List<OaProcessModelResp> models = jdbcTemplate
            .query("SELECT " + MODEL_COLUMNS + " FROM oa_process_model WHERE tenant_id = ? AND id = ? AND deleted = 0 FOR UPDATE", MODEL_MAPPER, tenantId, id);
        return models.isEmpty() ? null : models.get(0);
    }

    public boolean modelKeyExists(long tenantId, String modelKey) {
        Integer count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM oa_process_model WHERE tenant_id = ? AND model_key = ? AND deleted = 0", Integer.class, tenantId, modelKey);
        return count != null && count > 0;
    }

    public long insertModel(long tenantId,
                            String modelKey,
                            String name,
                            String category,
                            String description,
                            String businessType,
                            String bpmnXml,
                            String xmlDigest,
                            long userId,
                            LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_process_model
                  (tenant_id, model_key, name, category, business_type, description, bpmn_xml, xml_digest, status,
                   current_release_version, version, create_user, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 0, 0, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, modelKey);
            ps.setString(3, name);
            ps.setString(4, blankToNull(category));
            ps.setString(5, businessType);
            ps.setString(6, blankToNull(description));
            ps.setString(7, blankToNull(bpmnXml));
            ps.setString(8, xmlDigest);
            ps.setLong(9, userId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateDraft(long tenantId,
                           long id,
                           String name,
                           String category,
                           String description,
                           String bpmnXml,
                           String xmlDigest,
                           long userId,
                           LocalDateTime now,
                           int version) {
        return jdbcTemplate
            .update("""
                UPDATE oa_process_model
                   SET name = ?, category = ?, description = ?, bpmn_xml = ?, xml_digest = ?,
                       update_user = ?, update_time = ?, version = version + 1
                 WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
                """, name, blankToNull(category), blankToNull(description), blankToNull(bpmnXml), xmlDigest, userId, now, tenantId, id, version);
    }

    public int updatePublished(long tenantId,
                               long id,
                               String bpmnXml,
                               String xmlDigest,
                               long releaseId,
                               int releaseVersion,
                               Long formId,
                               Long formVersionId,
                               long userId,
                               LocalDateTime now,
                               int version) {
        return jdbcTemplate
            .update("""
                UPDATE oa_process_model
                   SET bpmn_xml = ?, xml_digest = ?, status = 'PUBLISHED',
                       current_release_id = ?, current_release_version = ?,
                       form_id = ?, form_version_id = ?,
                       update_user = ?, update_time = ?, version = version + 1
                 WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
                """, bpmnXml, xmlDigest, releaseId, releaseVersion, formId, formVersionId, userId, now, tenantId, id, version);
    }

    public int markDisabled(long tenantId, long id, long userId, LocalDateTime now, int version) {
        return jdbcTemplate.update("""
            UPDATE oa_process_model
               SET status = 'DISABLED', update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
            """, userId, now, tenantId, id, version);
    }

    public long insertRelease(long tenantId,
                              long modelId,
                              int releaseVersion,
                              String bpmnXml,
                              String xmlDigest,
                              String engineDefinitionId,
                              String processDefinitionKey,
                              int processDefinitionVersion,
                              Long formId,
                              Long formVersionId,
                              Integer formVersion,
                              String launchScope,
                              long userId,
                              LocalDateTime now,
                              String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_process_release
                  (tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
                   process_definition_key, process_definition_version, form_id, form_version_id, form_version,
                   launch_scope, status, published_by, publish_time, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, modelId);
            ps.setInt(3, releaseVersion);
            ps.setString(4, bpmnXml);
            ps.setString(5, xmlDigest);
            ps.setString(6, engineDefinitionId);
            ps.setString(7, processDefinitionKey);
            ps.setInt(8, processDefinitionVersion);
            ps.setObject(9, formId);
            ps.setObject(10, formVersionId);
            ps.setObject(11, formVersion);
            ps.setString(12, blankToNull(launchScope));
            ps.setLong(13, userId);
            ps.setObject(14, now);
            ps.setString(15, blankToNull(remark));
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int stopActiveReleases(long tenantId, long modelId, long keepReleaseId) {
        return jdbcTemplate.update("""
            UPDATE oa_process_release
               SET status = 'STOPPED'
             WHERE tenant_id = ? AND model_id = ? AND status = 'ACTIVE' AND id <> ?
            """, tenantId, modelId, keepReleaseId);
    }

    public int stopAllActiveReleases(long tenantId, long modelId) {
        return jdbcTemplate.update("""
            UPDATE oa_process_release
               SET status = 'STOPPED'
             WHERE tenant_id = ? AND model_id = ? AND status = 'ACTIVE'
            """, tenantId, modelId);
    }

    public List<OaProcessReleaseResp> releases(long tenantId, long modelId) {
        return jdbcTemplate.query("""
            SELECT id, release_version, engine_definition_id, process_definition_key,
                   process_definition_version, form_id, form_version_id, form_version,
                   launch_scope, status, published_by, publish_time, remark, xml_digest
              FROM oa_process_release
             WHERE tenant_id = ? AND model_id = ?
             ORDER BY release_version DESC
            """, RELEASE_MAPPER, tenantId, modelId);
    }

    public int nextReleaseVersion(long tenantId, long modelId) {
        Integer version = jdbcTemplate.queryForObject("""
            SELECT COALESCE(MAX(release_version), 0) FROM oa_process_release WHERE tenant_id = ? AND model_id = ?
            """, Integer.class, tenantId, modelId);
        return (version == null ? 0 : version) + 1;
    }

    public String latestProcessKey(long tenantId, long modelId) {
        List<String> keys = jdbcTemplate.queryForList("""
            SELECT process_definition_key FROM oa_process_release
             WHERE tenant_id = ? AND model_id = ?
             ORDER BY release_version DESC LIMIT 1
            """, String.class, tenantId, modelId);
        return keys.isEmpty() ? null : keys.get(0);
    }

    public boolean processKeyUsedByOtherModel(long tenantId, String processKey, long modelId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_process_release
             WHERE tenant_id = ? AND process_definition_key = ? AND model_id <> ?
            """, Integer.class, tenantId, processKey, modelId);
        return count != null && count > 0;
    }

    public FormVersion formVersion(long tenantId, long formVersionId) {
        List<FormVersion> versions = jdbcTemplate.query("""
            SELECT form_id, version, schema_json FROM oa_form_version WHERE tenant_id = ? AND id = ?
            """, (rs, rowNum) -> new FormVersion(rs.getLong("form_id"), rs.getInt("version"), rs
            .getString("schema_json")), tenantId, formVersionId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, String keyword, String status) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR model_key LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number)value).longValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public record FormVersion(long formId, int version, String schemaJson) {
    }
}
