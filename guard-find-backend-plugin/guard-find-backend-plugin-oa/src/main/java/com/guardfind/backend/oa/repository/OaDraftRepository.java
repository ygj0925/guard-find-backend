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
import java.util.List;

/**
 * 申请草稿的显式租户 SQL；所有读写都限定在草稿所属人。
 */
@Repository
public class OaDraftRepository {

    private static final String DRAFT_SELECT = """
        SELECT d.id, d.release_id, d.title, d.form_data, d.assignees_json, d.cc_user_ids_json, d.version,
               d.create_time, d.update_time, r.release_version, r.launch_scope,
               m.name AS model_name, f.name AS form_name,
               CASE WHEN r.status = 'ACTIVE' AND m.deleted = 0 AND m.status = 'PUBLISHED' AND m.current_release_id = r.id
                         AND f.deleted = 0 AND f.status = 'PUBLISHED' THEN 1 ELSE 0 END AS release_active
          FROM oa_application_draft d
          LEFT JOIN oa_process_release r ON r.tenant_id = d.tenant_id AND r.id = d.release_id
          LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
          LEFT JOIN oa_form_version v ON v.tenant_id = r.tenant_id AND v.id = r.form_version_id
          LEFT JOIN oa_form f ON f.tenant_id = v.tenant_id AND f.id = v.form_id
         WHERE d.tenant_id = ? AND d.applicant_id = ? AND d.deleted = 0
        """;

    private static final RowMapper<DraftRow> DRAFT_MAPPER = (rs, rowNum) -> new DraftRow(rs.getLong("id"), rs
        .getLong("release_id"), rs.getString("title"), rs.getString("form_data"), rs.getString("assignees_json"), rs
            .getString("cc_user_ids_json"), rs
                .getInt("version"), timestamp(rs, "create_time"), timestamp(rs, "update_time"), nullableInt(rs, "release_version"), rs
                    .getString("launch_scope"), rs.getString("model_name"), rs.getString("form_name"), rs
                        .getInt("release_active") == 1);

    private final JdbcTemplate jdbcTemplate;

    public OaDraftRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(long tenantId,
                       long applicantId,
                       long releaseId,
                       String title,
                       String formDataJson,
                       String assigneesJson,
                       String ccUserIdsJson,
                       LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_application_draft
                  (tenant_id, applicant_id, release_id, title, form_data, assignees_json, cc_user_ids_json,
                   version, create_time, update_time, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicantId);
            ps.setLong(3, releaseId);
            ps.setString(4, title);
            ps.setString(5, formDataJson);
            ps.setString(6, assigneesJson);
            ps.setString(7, ccUserIdsJson);
            ps.setObject(8, now);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public int update(long tenantId,
                      long applicantId,
                      long draftId,
                      long releaseId,
                      int expectedVersion,
                      String title,
                      String formDataJson,
                      String assigneesJson,
                      String ccUserIdsJson,
                      LocalDateTime now) {
        return jdbcTemplate
            .update("""
                UPDATE oa_application_draft
                   SET title = ?, form_data = ?, assignees_json = ?, cc_user_ids_json = ?,
                       version = version + 1, update_time = ?
                 WHERE tenant_id = ? AND applicant_id = ? AND id = ? AND release_id = ? AND version = ? AND deleted = 0
                """, title, formDataJson, assigneesJson, ccUserIdsJson, now, tenantId, applicantId, draftId, releaseId, expectedVersion);
    }

    public DraftRow find(long tenantId, long applicantId, long draftId) {
        List<DraftRow> rows = jdbcTemplate
            .query(DRAFT_SELECT + " AND d.id = ?", DRAFT_MAPPER, tenantId, applicantId, draftId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<DraftRow> page(long tenantId, long applicantId, String keyword, int offset, int size) {
        StringBuilder sql = new StringBuilder(DRAFT_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(applicantId);
        appendKeyword(sql, args, keyword);
        sql.append(" ORDER BY d.update_time DESC, d.id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), DRAFT_MAPPER, args.toArray());
    }

    public long count(long tenantId, long applicantId, String keyword) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*)
              FROM oa_application_draft d
              LEFT JOIN oa_process_release r ON r.tenant_id = d.tenant_id AND r.id = d.release_id
              LEFT JOIN oa_process_model m ON m.tenant_id = r.tenant_id AND m.id = r.model_id
             WHERE d.tenant_id = ? AND d.applicant_id = ? AND d.deleted = 0
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(applicantId);
        appendKeyword(sql, args, keyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * 软删除草稿；releaseId 非空时要求草稿属于该发布版本（提交时消费草稿）。
     */
    public int softDelete(long tenantId, long applicantId, long draftId, Long releaseId, LocalDateTime now) {
        StringBuilder sql = new StringBuilder("""
            UPDATE oa_application_draft
               SET deleted = id, update_time = ?
             WHERE tenant_id = ? AND applicant_id = ? AND id = ? AND deleted = 0
            """);
        List<Object> args = new ArrayList<>(List.of(now, tenantId, applicantId, draftId));
        if (releaseId != null) {
            sql.append(" AND release_id = ?");
            args.add(releaseId);
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    private static void appendKeyword(StringBuilder sql, List<Object> args, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (d.title LIKE ? OR m.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public record DraftRow(long id, long releaseId, String title, String formDataJson, String assigneesJson,
                           String ccUserIdsJson, int version, LocalDateTime createTime, LocalDateTime updateTime,
                           Integer releaseVersion, String launchScope, String modelName, String formName,
                           boolean releaseActive) {
    }
}
