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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaDocumentAudience;
import com.guardfind.backend.oa.model.OaDocumentInboxResp;
import com.guardfind.backend.oa.model.OaDocumentReceiptResp;
import com.guardfind.backend.oa.model.OaDocumentResp;
import com.guardfind.backend.oa.model.OaDocumentVersionResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.guardfind.backend.oa.repository.OaPurchaseRepository.nullableLong;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.timestamp;

/**
 * 公文/知识台账、版本与阅读回执的显式租户 SQL；受众在发布时按在职员工快照成回执，深链访问以回执为准。
 */
@Repository
public class OaDocumentRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOCUMENT_FROM = """
          FROM oa_document d
          LEFT JOIN (SELECT document_id, version_no, COUNT(*) AS total, COUNT(read_time) AS read_count,
                            COUNT(ack_time) AS ack_count
                       FROM oa_document_receipt
                      WHERE tenant_id = ?
                      GROUP BY document_id, version_no) rc
            ON rc.document_id = d.id AND rc.version_no = d.published_version
          LEFT JOIN sys_user o ON o.tenant_id = d.tenant_id AND o.id = d.owner_id
          LEFT JOIN sys_user rb ON rb.tenant_id = d.tenant_id AND rb.id = d.revoke_by
         WHERE d.tenant_id = ?
        """;

    private static final String DOCUMENT_SELECT = """
        SELECT d.id, d.doc_no, d.doc_type, d.title, d.category, d.owner_id,
               COALESCE(NULLIF(o.nickname, ''), o.username) AS owner_name, d.status, d.published_version,
               d.latest_version, d.pending_application_id, d.require_ack, d.publish_time, d.revoke_by,
               COALESCE(NULLIF(rb.nickname, ''), rb.username) AS revoke_by_name, d.revoke_time, d.revoke_reason,
               COALESCE(rc.total, 0) AS receipt_count, COALESCE(rc.read_count, 0) AS read_count,
               COALESCE(rc.ack_count, 0) AS ack_count, d.create_time, d.update_time
        """ + DOCUMENT_FROM;

    private static final RowMapper<OaDocumentResp> DOCUMENT_MAPPER = (rs, rowNum) -> new OaDocumentResp(rs
        .getLong("id"), rs.getString("doc_no"), rs.getString("doc_type"), rs.getString("title"), rs
            .getString("category"), rs.getLong("owner_id"), rs.getString("owner_name"), rs.getString("status"), rs
                .getInt("published_version"), rs
                    .getInt("latest_version"), nullableLong(rs, "pending_application_id"), rs
                        .getBoolean("require_ack"), timestamp(rs, "publish_time"), nullableLong(rs, "revoke_by"), rs
                            .getString("revoke_by_name"), timestamp(rs, "revoke_time"), rs
                                .getString("revoke_reason"), rs.getInt("receipt_count"), rs.getInt("read_count"), rs
                                    .getInt("ack_count"), timestamp(rs, "create_time"), timestamp(rs, "update_time"));

    private static final String INBOX_FROM = """
          FROM oa_document_receipt r
          JOIN oa_document d
            ON d.tenant_id = r.tenant_id AND d.id = r.document_id AND d.status = 'PUBLISHED'
           AND d.published_version = r.version_no
          LEFT JOIN sys_user o ON o.tenant_id = d.tenant_id AND o.id = d.owner_id
         WHERE r.tenant_id = ? AND r.user_id = ?
        """;

    private static final String INBOX_SELECT = """
        SELECT d.id, d.doc_no, d.doc_type, d.title, d.category, r.version_no,
               COALESCE(NULLIF(o.nickname, ''), o.username) AS owner_name, d.publish_time, d.require_ack,
               r.read_time, r.ack_time
        """ + INBOX_FROM;

    private static final RowMapper<OaDocumentInboxResp> INBOX_MAPPER = (rs, rowNum) -> new OaDocumentInboxResp(rs
        .getLong("id"), rs.getString("doc_no"), rs.getString("doc_type"), rs.getString("title"), rs
            .getString("category"), rs.getInt("version_no"), rs
                .getString("owner_name"), timestamp(rs, "publish_time"), rs
                    .getBoolean("require_ack"), timestamp(rs, "read_time"), timestamp(rs, "ack_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertDocument(long tenantId,
                               String docType,
                               String title,
                               String category,
                               long ownerId,
                               boolean requireAck,
                               long applicationId,
                               LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_document (tenant_id, doc_type, title, category, owner_id, status, published_version,
                                         latest_version, pending_application_id, require_ack, create_time)
                VALUES (?, ?, ?, ?, ?, 'APPROVING', 0, 1, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, docType);
            ps.setString(3, title);
            ps.setString(4, category);
            ps.setLong(5, ownerId);
            ps.setLong(6, applicationId);
            ps.setBoolean(7, requireAck);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public void assignDocNo(long tenantId, long id, String docNo) {
        jdbcTemplate.update("UPDATE oa_document SET doc_no = ? WHERE tenant_id = ? AND id = ?", docNo, tenantId, id);
    }

    /**
     * 修订送审：没有在途修订、版本号与加锁时一致才能占用；未发布过的公文同时刷新标题与状态。
     */
    public int reviseDocument(long tenantId,
                              long id,
                              int fromLatest,
                              long applicationId,
                              String title,
                              String category,
                              LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document
               SET latest_version = latest_version + 1, pending_application_id = ?,
                   status = CASE WHEN published_version = 0 THEN 'APPROVING' ELSE status END,
                   title = CASE WHEN published_version = 0 THEN ? ELSE title END,
                   category = CASE WHEN published_version = 0 THEN ? ELSE category END,
                   update_time = ?
             WHERE tenant_id = ? AND id = ? AND latest_version = ? AND pending_application_id IS NULL
               AND status IN ('PUBLISHED', 'CLOSED')
            """, applicationId, title, category, now, tenantId, id, fromLatest);
    }

    public void insertVersion(long tenantId,
                              long documentId,
                              int versionNo,
                              long applicationId,
                              String title,
                              String category,
                              String content,
                              String changeNote,
                              String audienceJson,
                              boolean requireAck,
                              long applicantId,
                              LocalDateTime now) {
        jdbcTemplate
            .update("""
                INSERT INTO oa_document_version (tenant_id, document_id, version_no, application_id, title, category, content,
                                                 change_note, audience_json, require_ack, status, applicant_id,
                                                 audience_count, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVING', ?, 0, ?)
                """, tenantId, documentId, versionNo, applicationId, title, category, content, changeNote, audienceJson, requireAck, applicantId, now);
    }

    public DocumentRow lockDocument(long tenantId, long id) {
        return lockDocument("id = ?", tenantId, id);
    }

    public DocumentRow lockDocumentByNo(long tenantId, String docNo) {
        return lockDocument("doc_no = ?", tenantId, docNo);
    }

    private DocumentRow lockDocument(String condition, long tenantId, Object key) {
        List<DocumentRow> rows = jdbcTemplate
            .query("""
                SELECT id, doc_no, doc_type, title, owner_id, status, published_version, latest_version, pending_application_id
                  FROM oa_document
                 WHERE tenant_id = ? AND %s
                 FOR UPDATE
                """
                .formatted(condition), (rs, rowNum) -> new DocumentRow(rs.getLong("id"), rs.getString("doc_no"), rs
                    .getString("doc_type"), rs.getString("title"), rs.getLong("owner_id"), rs.getString("status"), rs
                        .getInt("published_version"), rs
                            .getInt("latest_version"), nullableLong(rs, "pending_application_id")), tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public VersionRow lockVersionByApplication(long tenantId, long applicationId) {
        List<VersionRow> rows = jdbcTemplate.query("""
            SELECT id, document_id, version_no, title, category, audience_json, require_ack, status
              FROM oa_document_version
             WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new VersionRow(rs.getLong("id"), rs.getLong("document_id"), rs.getInt("version_no"), rs
            .getString("title"), rs.getString("category"), rs.getString("audience_json"), rs
                .getBoolean("require_ack"), rs.getString("status")), tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionVersion(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document_version SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int publishVersion(long tenantId, long id, int audienceCount, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document_version
               SET status = 'PUBLISHED', audience_count = ?, publish_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'APPROVING'
            """, audienceCount, now, now, tenantId, id);
    }

    public int publishDocument(long tenantId,
                               long id,
                               long applicationId,
                               int versionNo,
                               String title,
                               String category,
                               boolean requireAck,
                               LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document
               SET title = ?, category = ?, require_ack = ?, published_version = ?, status = 'PUBLISHED',
                   pending_application_id = NULL, publish_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND pending_application_id = ?
            """, title, category, requireAck, versionNo, now, now, tenantId, id, applicationId);
    }

    /**
     * 修订未通过：释放在途占用；从未发布过的公文整体关闭，已发布的继续保持旧版本。
     */
    public int closePending(long tenantId, long id, long applicationId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document
               SET pending_application_id = NULL,
                   status = CASE WHEN published_version = 0 THEN 'CLOSED' ELSE status END, update_time = ?
             WHERE tenant_id = ? AND id = ? AND pending_application_id = ?
            """, now, tenantId, id, applicationId);
    }

    public void insertReceipts(long tenantId, long documentId, int versionNo, List<Long> userIds, LocalDateTime now) {
        jdbcTemplate.batchUpdate("""
            INSERT INTO oa_document_receipt (tenant_id, document_id, version_no, user_id, create_time)
            VALUES (?, ?, ?, ?, ?)
            """, userIds, 200, (ps, userId) -> {
            ps.setLong(1, tenantId);
            ps.setLong(2, documentId);
            ps.setInt(3, versionNo);
            ps.setLong(4, userId);
            ps.setObject(5, now);
        });
    }

    public List<DeptRow> depts(long tenantId) {
        return jdbcTemplate.query("""
            SELECT id, parent_id, name FROM sys_dept WHERE tenant_id = ? AND deleted = 0 ORDER BY id
            """, (rs, rowNum) -> new DeptRow(rs.getLong("id"), rs.getLong("parent_id"), rs
            .getString("name")), tenantId);
    }

    /**
     * 当前在职（员工档案在职且账号启用）的受众用户。
     */
    public List<Long> activeAudience(long tenantId, boolean all, Collection<Long> deptIds, Collection<Long> userIds) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT u.id
              FROM sys_user u
              JOIN oa_employee e
                ON e.tenant_id = u.tenant_id AND e.user_id = u.id AND e.deleted = 0 AND e.employment_status = 'ACTIVE'
             WHERE u.tenant_id = ? AND u.deleted = 0 AND u.status = 1
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (!all) {
            List<String> conditions = new ArrayList<>();
            if (deptIds != null && !deptIds.isEmpty()) {
                conditions.add("u.dept_id IN (" + placeholders(deptIds.size()) + ")");
                args.addAll(deptIds);
            }
            if (userIds != null && !userIds.isEmpty()) {
                conditions.add("u.id IN (" + placeholders(userIds.size()) + ")");
                args.addAll(userIds);
            }
            if (conditions.isEmpty()) {
                return List.of();
            }
            sql.append(" AND (").append(String.join(" OR ", conditions)).append(")");
        }
        sql.append(" ORDER BY u.id");
        return jdbcTemplate.queryForList(sql.toString(), Long.class, args.toArray());
    }

    public List<OaDocumentResp> documents(long tenantId,
                                          String status,
                                          String docType,
                                          String keyword,
                                          int offset,
                                          int limit) {
        StringBuilder sql = new StringBuilder(DOCUMENT_SELECT);
        List<Object> args = new ArrayList<>(List.of(tenantId, tenantId));
        appendFilters(sql, args, status, docType, keyword);
        sql.append(" ORDER BY d.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), DOCUMENT_MAPPER, args.toArray());
    }

    public long countDocuments(long tenantId, String status, String docType, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + DOCUMENT_FROM);
        List<Object> args = new ArrayList<>(List.of(tenantId, tenantId));
        appendFilters(sql, args, status, docType, keyword);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaDocumentResp document(long tenantId, long id) {
        List<OaDocumentResp> rows = jdbcTemplate
            .query(DOCUMENT_SELECT + " AND d.id = ?", DOCUMENT_MAPPER, tenantId, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaDocumentVersionResp> versions(long tenantId, long documentId) {
        return jdbcTemplate.query("""
            SELECT v.id, v.version_no, v.application_id, a.status AS application_status, v.title, v.category, v.content,
                   v.change_note, v.audience_json, v.require_ack, v.status, v.applicant_id,
                   COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, v.audience_count, v.publish_time,
                   v.create_time
              FROM oa_document_version v
              JOIN oa_application a ON a.tenant_id = v.tenant_id AND a.id = v.application_id
              LEFT JOIN sys_user u ON u.tenant_id = v.tenant_id AND u.id = v.applicant_id
             WHERE v.tenant_id = ? AND v.document_id = ?
             ORDER BY v.version_no DESC
            """, (rs, rowNum) -> new OaDocumentVersionResp(rs.getLong("id"), rs.getInt("version_no"), rs
            .getLong("application_id"), rs.getString("application_status"), rs.getString("title"), rs
                .getString("category"), rs.getString("content"), rs.getString("change_note"), audience(rs
                    .getString("audience_json")), rs.getBoolean("require_ack"), rs.getString("status"), rs
                        .getLong("applicant_id"), rs.getString("applicant_name"), rs
                            .getInt("audience_count"), timestamp(rs, "publish_time"), timestamp(rs, "create_time")), tenantId, documentId);
    }

    public List<Long> versionApplicationIds(long tenantId, long documentId) {
        return jdbcTemplate.queryForList("""
            SELECT application_id FROM oa_document_version WHERE tenant_id = ? AND document_id = ?
            """, Long.class, tenantId, documentId);
    }

    public PublishedVersion publishedVersion(long tenantId, long documentId) {
        List<PublishedVersion> rows = jdbcTemplate.query("""
            SELECT v.application_id, v.content, v.change_note
              FROM oa_document_version v
              JOIN oa_document d ON d.tenant_id = v.tenant_id AND d.id = v.document_id
             WHERE v.tenant_id = ? AND v.document_id = ? AND v.version_no = d.published_version
            """, (rs, rowNum) -> new PublishedVersion(rs.getLong("application_id"), rs.getString("content"), rs
            .getString("change_note")), tenantId, documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaDocumentReceiptResp> receipts(long tenantId, long documentId, int versionNo) {
        return jdbcTemplate.query("""
            SELECT r.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, dp.name AS dept_name,
                   r.read_time, r.ack_time
              FROM oa_document_receipt r
              LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.user_id
              LEFT JOIN sys_dept dp ON dp.tenant_id = u.tenant_id AND dp.id = u.dept_id AND dp.deleted = 0
             WHERE r.tenant_id = ? AND r.document_id = ? AND r.version_no = ?
             ORDER BY r.read_time IS NULL DESC, r.user_id
            """, (rs, rowNum) -> new OaDocumentReceiptResp(rs.getLong("user_id"), rs.getString("user_name"), rs
            .getString("dept_name"), timestamp(rs, "read_time"), timestamp(rs, "ack_time")), tenantId, documentId, versionNo);
    }

    public List<OaDocumentInboxResp> inbox(long tenantId,
                                           long userId,
                                           String keyword,
                                           boolean pendingOnly,
                                           int offset,
                                           int limit) {
        StringBuilder sql = new StringBuilder(INBOX_SELECT);
        List<Object> args = new ArrayList<>(List.of(tenantId, userId));
        appendInboxFilters(sql, args, keyword, pendingOnly);
        sql.append(" ORDER BY d.publish_time DESC, d.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), INBOX_MAPPER, args.toArray());
    }

    public long countInbox(long tenantId, long userId, String keyword, boolean pendingOnly) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + INBOX_FROM);
        List<Object> args = new ArrayList<>(List.of(tenantId, userId));
        appendInboxFilters(sql, args, keyword, pendingOnly);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaDocumentInboxResp inboxItem(long tenantId, long userId, long documentId) {
        List<OaDocumentInboxResp> rows = jdbcTemplate
            .query(INBOX_SELECT + " AND d.id = ?", INBOX_MAPPER, tenantId, userId, documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int markRead(long tenantId, long documentId, int versionNo, long userId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document_receipt SET read_time = ?
             WHERE tenant_id = ? AND document_id = ? AND version_no = ? AND user_id = ? AND read_time IS NULL
            """, now, tenantId, documentId, versionNo, userId);
    }

    public int markAck(long tenantId, long documentId, int versionNo, long userId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document_receipt SET ack_time = ?, read_time = COALESCE(read_time, ?)
             WHERE tenant_id = ? AND document_id = ? AND version_no = ? AND user_id = ? AND ack_time IS NULL
            """, now, now, tenantId, documentId, versionNo, userId);
    }

    public int revoke(long tenantId, long id, long userId, String reason, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_document
               SET status = 'REVOKED', revoke_by = ?, revoke_time = ?, revoke_reason = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PUBLISHED' AND pending_application_id IS NULL
            """, userId, now, reason, now, tenantId, id);
    }

    public static OaDocumentAudience audience(String json) {
        try {
            OaDocumentAudience audience = MAPPER.readValue(json, OaDocumentAudience.class);
            return new OaDocumentAudience(audience.all(), audience.depts() == null
                ? List.of()
                : audience.depts(), audience.users() == null ? List.of() : audience.users());
        } catch (Exception e) {
            return new OaDocumentAudience(false, List.of(), List.of());
        }
    }

    private static void appendFilters(StringBuilder sql,
                                      List<Object> args,
                                      String status,
                                      String docType,
                                      String keyword) {
        if (status != null) {
            sql.append(" AND d.status = ?");
            args.add(status);
        }
        if (docType != null) {
            sql.append(" AND d.doc_type = ?");
            args.add(docType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (d.doc_no LIKE ? OR d.title LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
    }

    private static void appendInboxFilters(StringBuilder sql, List<Object> args, String keyword, boolean pendingOnly) {
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (d.doc_no LIKE ? OR d.title LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (pendingOnly) {
            sql.append(" AND (r.read_time IS NULL OR (d.require_ack = TRUE AND r.ack_time IS NULL))");
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public record DocumentRow(long id, String docNo, String docType, String title, long ownerId, String status,
                              int publishedVersion, int latestVersion, Long pendingApplicationId) {
    }

    public record VersionRow(long id, long documentId, int versionNo, String title, String category,
                             String audienceJson, boolean requireAck, String status) {
    }

    public record DeptRow(long id, long parentId, String name) {
    }

    public record PublishedVersion(long applicationId, String content, String changeNote) {
    }
}
