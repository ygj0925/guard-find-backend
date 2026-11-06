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

import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaFormVersionDetailResp;
import com.guardfind.backend.oa.model.OaFormVersionOptionResp;
import com.guardfind.backend.oa.model.OaFormVersionResp;
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
 * 表单草稿与不可变版本的显式租户 SQL。所有读写都带 tenant_id。
 */
@Repository
public class OaFormRepository {

    private static final String FORM_COLUMNS = """
        id, form_key, name, description, status, current_version, current_version_id,
        draft_schema_json, draft_schema_digest, version, create_time, update_time
        """;

    /** 列表不读取 schema，避免分页响应携带完整表单定义。 */
    private static final String FORM_LIST_COLUMNS = """
        id, form_key, name, description, status, current_version, current_version_id,
        NULL AS draft_schema_json, draft_schema_digest, version, create_time, update_time
        """;

    private static final RowMapper<OaFormResp> FORM_MAPPER = (rs, rowNum) -> new OaFormResp(rs.getLong("id"), rs
        .getString("form_key"), rs.getString("name"), rs.getString("description"), rs.getString("status"), rs
            .getInt("current_version"), nullableLong(rs.getObject("current_version_id")), rs
                .getString("draft_schema_json"), rs.getString("draft_schema_digest"), rs.getInt("version"), rs
                    .getTimestamp("create_time")
                    .toLocalDateTime(), rs.getTimestamp("update_time") == null
                        ? null
                        : rs.getTimestamp("update_time").toLocalDateTime());

    private static final RowMapper<OaFormVersionResp> VERSION_MAPPER = (rs, rowNum) -> new OaFormVersionResp(rs
        .getLong("id"), rs.getInt("version"), null, rs.getString("schema_digest"), rs.getInt("field_count"), rs
            .getLong("published_by"), rs.getTimestamp("publish_time").toLocalDateTime());

    private static final RowMapper<OaFormVersionOptionResp> OPTION_MAPPER = (rs,
                                                                             rowNum) -> new OaFormVersionOptionResp(rs
                                                                                 .getLong("form_id"), rs
                                                                                     .getString("form_key"), rs
                                                                                         .getString("name"), rs
                                                                                             .getLong("version_id"), rs
                                                                                                 .getInt("version"), rs
                                                                                                     .getTimestamp("publish_time")
                                                                                                     .toLocalDateTime());

    private static final RowMapper<OaFormVersionDetailResp> VERSION_DETAIL_MAPPER = (rs,
                                                                                     rowNum) -> new OaFormVersionDetailResp(rs
                                                                                         .getLong("id"), rs
                                                                                             .getLong("form_id"), rs
                                                                                                 .getString("form_key"), rs
                                                                                                     .getString("name"), rs
                                                                                                         .getString("status"), rs
                                                                                                             .getInt("version"), rs
                                                                                                                 .getString("schema_json"), rs
                                                                                                                     .getString("schema_digest"), rs
                                                                                                                         .getInt("field_count"), rs
                                                                                                                             .getTimestamp("publish_time")
                                                                                                                             .toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public OaFormRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaFormResp> page(long tenantId, String keyword, String status, int offset, int size) {
        StringBuilder sql = new StringBuilder("SELECT ").append(FORM_LIST_COLUMNS)
            .append(" FROM oa_form WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, keyword, status);
        sql.append(" ORDER BY update_time DESC, id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), FORM_MAPPER, args.toArray());
    }

    public long count(long tenantId, String keyword, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM oa_form WHERE tenant_id = ? AND deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, keyword, status);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public OaFormResp detail(long tenantId, long id) {
        List<OaFormResp> forms = jdbcTemplate
            .query("SELECT " + FORM_COLUMNS + " FROM oa_form WHERE tenant_id = ? AND id = ? AND deleted = 0", FORM_MAPPER, tenantId, id);
        return forms.isEmpty() ? null : forms.get(0);
    }

    public OaFormResp lock(long tenantId, long id) {
        List<OaFormResp> forms = jdbcTemplate
            .query("SELECT " + FORM_COLUMNS + " FROM oa_form WHERE tenant_id = ? AND id = ? AND deleted = 0 FOR UPDATE", FORM_MAPPER, tenantId, id);
        return forms.isEmpty() ? null : forms.get(0);
    }

    public boolean formKeyExists(long tenantId, String formKey) {
        Integer count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM oa_form WHERE tenant_id = ? AND form_key = ? AND deleted = 0", Integer.class, tenantId, formKey);
        return count != null && count > 0;
    }

    public long insertForm(long tenantId,
                           String formKey,
                           String name,
                           String description,
                           String schemaJson,
                           String schemaDigest,
                           long userId,
                           LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_form
                  (tenant_id, form_key, name, description, draft_schema_json, draft_schema_digest,
                   status, current_version, version, create_user, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 0, 0, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, formKey);
            ps.setString(3, name);
            ps.setString(4, blankToNull(description));
            ps.setString(5, schemaJson);
            ps.setString(6, schemaDigest);
            ps.setLong(7, userId);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateDraft(long tenantId,
                           long id,
                           String name,
                           String description,
                           String schemaJson,
                           String schemaDigest,
                           long userId,
                           LocalDateTime now,
                           int version) {
        return jdbcTemplate.update("""
            UPDATE oa_form
               SET name = ?, description = ?, draft_schema_json = ?, draft_schema_digest = ?,
                   update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
            """, name, blankToNull(description), schemaJson, schemaDigest, userId, now, tenantId, id, version);
    }

    public int markPublished(long tenantId,
                             long id,
                             long versionId,
                             int formVersion,
                             String schemaDigest,
                             long userId,
                             LocalDateTime now,
                             int optimisticVersion) {
        return jdbcTemplate.update("""
            UPDATE oa_form
               SET status = 'PUBLISHED', current_version = ?, current_version_id = ?,
                   draft_schema_digest = ?, update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
            """, formVersion, versionId, schemaDigest, userId, now, tenantId, id, optimisticVersion);
    }

    public int markDisabled(long tenantId, long id, long userId, LocalDateTime now, int optimisticVersion) {
        return jdbcTemplate.update("""
            UPDATE oa_form
               SET status = 'DISABLED', update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND deleted = 0 AND version = ?
            """, userId, now, tenantId, id, optimisticVersion);
    }

    public int nextVersion(long tenantId, long formId) {
        Integer version = jdbcTemplate.queryForObject("""
            SELECT COALESCE(MAX(version), 0) FROM oa_form_version WHERE tenant_id = ? AND form_id = ?
            """, Integer.class, tenantId, formId);
        return (version == null ? 0 : version) + 1;
    }

    public long insertVersion(long tenantId,
                              long formId,
                              int version,
                              String schemaJson,
                              String schemaDigest,
                              int fieldCount,
                              long userId,
                              LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_form_version
                  (tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, formId);
            ps.setInt(3, version);
            ps.setString(4, schemaJson);
            ps.setString(5, schemaDigest);
            ps.setInt(6, fieldCount);
            ps.setLong(7, userId);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public List<OaFormVersionResp> versions(long tenantId, long formId) {
        return jdbcTemplate.query("""
            SELECT id, version, schema_digest, field_count, published_by, publish_time
              FROM oa_form_version
             WHERE tenant_id = ? AND form_id = ?
             ORDER BY version DESC
            """, VERSION_MAPPER, tenantId, formId);
    }

    public OaFormVersionDetailResp versionDetail(long tenantId, long versionId) {
        List<OaFormVersionDetailResp> versions = jdbcTemplate.query("""
            SELECT v.id, v.form_id, f.form_key, f.name, f.status, v.version, v.schema_json,
                   v.schema_digest, v.field_count, v.publish_time
              FROM oa_form_version v
              JOIN oa_form f ON f.tenant_id = v.tenant_id AND f.id = v.form_id
             WHERE v.tenant_id = ? AND v.id = ? AND f.deleted = 0
            """, VERSION_DETAIL_MAPPER, tenantId, versionId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    public List<OaFormVersionOptionResp> versionOptions(long tenantId) {
        return jdbcTemplate.query("""
            SELECT f.id AS form_id, f.form_key, f.name, v.id AS version_id, v.version, v.publish_time
              FROM oa_form f
              JOIN oa_form_version v ON v.tenant_id = f.tenant_id AND v.form_id = f.id
             WHERE f.tenant_id = ? AND f.deleted = 0 AND f.status = 'PUBLISHED'
             ORDER BY f.name, f.id, v.version DESC
             LIMIT 500
            """, OPTION_MAPPER, tenantId);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, String keyword, String status) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR form_key LIKE ?)");
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
}
