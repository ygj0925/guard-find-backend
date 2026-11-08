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
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OA 附件归属的显式租户 SQL。
 *
 * <p>
 * 发起时只接受当前租户、当前上传人上传的未删除文件，避免把他人文件挂到自己的申请；
 * 下载授权以申请参与人身份为准，裸 fileId 不构成授权。
 * </p>
 */
@Repository
public class OaAttachmentRepository {

    private static final int MAX_FILE_IDS = 200;
    private static final String FILE_COLUMNS = """
        f.id, f.name, f.original_name, f.size, f.parent_path, f.path, f.extension,
        f.content_type, f.type, f.storage_id, s.code AS storage_code
        """;

    private static final RowMapper<FileRow> FILE_MAPPER = (rs, rowNum) -> new FileRow(rs.getLong("id"), rs
        .getString("name"), rs.getString("original_name"), nullableLong(rs, "size"), rs.getString("parent_path"), rs
            .getString("path"), rs.getString("extension"), rs.getString("content_type"), rs.getInt("type"), rs
                .getLong("storage_id"), rs.getString("storage_code"));

    private final JdbcTemplate jdbcTemplate;

    public OaAttachmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FileRow> uploadedFiles(long tenantId, long uploaderId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return List.of();
        }
        List<Long> safeIds = fileIds.size() > MAX_FILE_IDS ? fileIds.subList(0, MAX_FILE_IDS) : fileIds;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(uploaderId);
        args.addAll(safeIds);
        String sql = """
            SELECT %s
              FROM sys_file f
              JOIN sys_storage s ON s.id = f.storage_id AND s.deleted = 0
             WHERE f.tenant_id = ? AND f.create_user = ? AND f.deleted = 0
               AND f.id IN (%s)
            """.formatted(FILE_COLUMNS, placeholders(safeIds.size()));
        return jdbcTemplate.query(sql, FILE_MAPPER, args.toArray());
    }

    public Set<Long> linkedFileIds(long tenantId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return Set.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(fileIds);
        List<Long> ids = jdbcTemplate.queryForList("""
            SELECT file_id
              FROM oa_attachment_link
             WHERE tenant_id = ? AND deleted = 0 AND file_id IN (%s)
            """.formatted(placeholders(fileIds.size())), Long.class, args.toArray());
        return new LinkedHashSet<>(ids);
    }

    public void insertLinks(long tenantId,
                            long applicationId,
                            String businessType,
                            String businessKey,
                            long createUser,
                            List<AttachmentRow> attachments,
                            LocalDateTime now) {
        jdbcTemplate.batchUpdate("""
            INSERT INTO oa_attachment_link
              (tenant_id, application_id, business_type, business_key, file_id, file_name, file_size,
               content_type, category, scan_status, available, create_user, create_time, deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'FORM', 'SKIPPED', 1, ?, ?, 0)
            """, attachments, attachments.size(), (ps, attachment) -> {
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setString(3, businessType);
            ps.setString(4, businessKey);
            ps.setLong(5, attachment.fileId());
            ps.setString(6, attachment.name());
            ps.setObject(7, attachment.size());
            ps.setString(8, attachment.contentType());
            ps.setLong(9, createUser);
            ps.setObject(10, now);
        });
    }

    /**
     * 下载元数据：附件必须已归属申请且可用，文件与存储配置仍需存在。
     */
    public LinkedAttachment linkedForDownload(long tenantId, long fileId) {
        List<LinkedAttachment> links = jdbcTemplate.query("""
            SELECT l.application_id, a.applicant_id, a.process_instance_id, %s
              FROM oa_attachment_link l
              JOIN oa_application a
                ON a.tenant_id = l.tenant_id AND a.id = l.application_id AND a.deleted = 0
              JOIN sys_file f
                ON f.tenant_id = l.tenant_id AND f.id = l.file_id AND f.deleted = 0
              JOIN sys_storage s
                ON s.id = f.storage_id AND s.deleted = 0
             WHERE l.tenant_id = ? AND l.file_id = ? AND l.deleted = 0 AND l.available = 1
            """.formatted(FILE_COLUMNS), (rs, rowNum) -> new LinkedAttachment(rs.getLong("application_id"), rs
            .getLong("applicant_id"), rs.getString("process_instance_id"), FILE_MAPPER
                .mapRow(rs, rowNum)), tenantId, fileId);
        return links.isEmpty() ? null : links.get(0);
    }

    public boolean hasAuditRecord(long tenantId, long applicationId, long operatorId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
              FROM oa_approval_audit
             WHERE tenant_id = ? AND application_id = ? AND operator_id = ?
            """, Integer.class, tenantId, applicationId, operatorId);
        return count != null && count > 0;
    }

    public boolean isCcReceiver(long tenantId, long applicationId, long userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
              FROM oa_cc_receipt
             WHERE tenant_id = ? AND application_id = ? AND receiver_id = ? AND status <> 'REVOKED'
            """, Integer.class, tenantId, applicationId, userId);
        return count != null && count > 0;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number)value).longValue();
    }

    public record FileRow(long id, String name, String originalName, Long size, String parentPath, String path,
                          String extension, String contentType, int type, long storageId, String storageCode) {
    }

    public record AttachmentRow(long fileId, String name, Long size, String contentType) {
    }

    public record LinkedAttachment(long applicationId, long applicantId, String processInstanceId, FileRow file) {
    }
}
