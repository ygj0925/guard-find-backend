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

import com.guardfind.backend.oa.model.OaNotificationResp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 站内通知的显式租户/接收人 SQL；同一事件重复投递由唯一键吸收。
 */
@Repository
public class OaNotificationRepository {

    private static final RowMapper<OaNotificationResp> MAPPER = (rs, rowNum) -> new OaNotificationResp(rs
        .getLong("id"), rs.getString("type"), rs.getString("title"), rs
            .getString("content"), nullableLong(rs, "application_id"), rs.getString("task_id"), rs
                .getString("status"), timestamp(rs, "read_time"), timestamp(rs, "create_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaNotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insert(long tenantId,
                          long userId,
                          String type,
                          String title,
                          String content,
                          Long applicationId,
                          String taskId,
                          String dedupKey,
                          LocalDateTime now) {
        try {
            return jdbcTemplate.update("""
                INSERT INTO oa_notification
                  (tenant_id, user_id, type, title, content, application_id, task_id, dedup_key, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNREAD', ?)
                """, tenantId, userId, type, title, content, applicationId, taskId, dedupKey, now) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<OaNotificationResp> page(long tenantId, long userId, boolean unreadOnly, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, type, title, content, application_id, task_id, status, read_time, create_time
              FROM oa_notification
             WHERE tenant_id = ? AND user_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (unreadOnly) {
            sql.append(" AND status = 'UNREAD'");
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(long tenantId, long userId, boolean unreadOnly) {
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*) FROM oa_notification WHERE tenant_id = ? AND user_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (unreadOnly) {
            sql.append(" AND status = 'UNREAD'");
        }
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public boolean markRead(long tenantId, long userId, long id, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_notification
               SET status = 'READ', read_time = ?
             WHERE tenant_id = ? AND user_id = ? AND id = ? AND status = 'UNREAD'
            """, now, tenantId, userId, id) == 1;
    }

    public int markAllRead(long tenantId, long userId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_notification
               SET status = 'READ', read_time = ?
             WHERE tenant_id = ? AND user_id = ? AND status = 'UNREAD'
            """, now, tenantId, userId);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
