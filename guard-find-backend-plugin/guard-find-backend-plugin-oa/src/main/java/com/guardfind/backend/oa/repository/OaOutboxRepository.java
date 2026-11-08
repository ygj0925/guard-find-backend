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

import com.guardfind.backend.oa.model.OaOutboxEventResp;
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
 * outbox 的显式租户 SQL：事务内写入、后台认领、失败退避和运维重试。
 */
@Repository
public class OaOutboxRepository {

    private static final RowMapper<OaOutboxEventResp> MAPPER = (rs, rowNum) -> new OaOutboxEventResp(rs
        .getLong("id"), rs.getLong("tenant_id"), rs.getString("event_id"), rs.getString("aggregate_type"), rs
            .getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"), rs.getString("status"), rs
                .getInt("retry_count"), timestamp(rs, "next_retry_time"), rs
                    .getString("last_error"), timestamp(rs, "create_time"), timestamp(rs, "sent_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 业务事务内写入事件；dedup_key 冲突表示同一业务事件已记录，直接忽略。
     */
    public boolean insert(long tenantId,
                          String eventId,
                          String aggregateType,
                          String aggregateId,
                          String eventType,
                          String payload,
                          String dedupKey,
                          LocalDateTime now) {
        try {
            return jdbcTemplate.update("""
                INSERT INTO oa_outbox
                  (tenant_id, event_id, aggregate_type, aggregate_id, event_type, payload, dedup_key,
                   status, retry_count, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                """, tenantId, eventId, aggregateType, aggregateId, eventType, payload, dedupKey, now) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public List<OaOutboxEventResp> findDue(LocalDateTime now, int maxRetries, int limit) {
        return jdbcTemplate.query("""
            SELECT id, tenant_id, event_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count,
                   next_retry_time, last_error, create_time, sent_time
              FROM oa_outbox
             WHERE status IN ('PENDING', 'FAILED')
               AND retry_count < ?
               AND (next_retry_time IS NULL OR next_retry_time <= ?)
             ORDER BY id ASC
             LIMIT ?
            """, MAPPER, maxRetries, now, limit);
    }

    /**
     * 原子认领，避免多副本重复投递同一事件。
     */
    public boolean claim(long id, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_outbox
               SET status = 'PROCESSING', update_time = ?
             WHERE id = ? AND status IN ('PENDING', 'FAILED')
            """, now, id) == 1;
    }

    public void markSent(long id, String note, LocalDateTime now) {
        jdbcTemplate.update("""
            UPDATE oa_outbox
               SET status = 'SENT', sent_time = ?, update_time = ?, last_error = ?
             WHERE id = ?
            """, now, now, note, id);
    }

    public void markFailed(long id, int retryCount, LocalDateTime nextRetryTime, String error, LocalDateTime now) {
        jdbcTemplate.update("""
            UPDATE oa_outbox
               SET status = 'FAILED', retry_count = ?, next_retry_time = ?, last_error = ?, update_time = ?
             WHERE id = ?
            """, retryCount, nextRetryTime, truncate(error, 1000), now, id);
    }

    /**
     * 处理中超过阈值的事件视为 worker 中断，重新排队而不是永久卡住。
     */
    public int recoverStuck(LocalDateTime threshold, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_outbox
               SET status = 'FAILED', next_retry_time = ?, last_error = '处理超时，已重新排队', update_time = ?
             WHERE status = 'PROCESSING' AND update_time < ?
            """, now, now, threshold);
    }

    public OaOutboxEventResp findById(long tenantId, long id) {
        List<OaOutboxEventResp> rows = jdbcTemplate.query("""
            SELECT id, tenant_id, event_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count,
                   next_retry_time, last_error, create_time, sent_time
              FROM oa_outbox
             WHERE tenant_id = ? AND id = ?
            """, MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean retry(long tenantId, long id, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_outbox
               SET status = 'PENDING', next_retry_time = ?, last_error = '运维手动重试', update_time = ?
             WHERE tenant_id = ? AND id = ? AND status IN ('FAILED', 'PENDING')
            """, now, now, tenantId, id) == 1;
    }

    public List<OaOutboxEventResp> page(long tenantId, String status, String keyword, int offset, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, tenant_id, event_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count,
                   next_retry_time, last_error, create_time, sent_time
              FROM oa_outbox
             WHERE tenant_id = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, status, keyword);
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(size);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(long tenantId, String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM oa_outbox WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, status, keyword);
        Long total = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public LocalDateTime lastEventTime(long tenantId, String aggregateType, String aggregateId, String eventType) {
        Timestamp time = jdbcTemplate.queryForObject("""
            SELECT MAX(create_time)
              FROM oa_outbox
             WHERE tenant_id = ? AND aggregate_type = ? AND aggregate_id = ? AND event_type = ?
            """, Timestamp.class, tenantId, aggregateType, aggregateId, eventType);
        return time == null ? null : time.toLocalDateTime();
    }

    public long countEventsSince(long tenantId,
                                 String aggregateType,
                                 String aggregateId,
                                 String eventType,
                                 LocalDateTime since) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
              FROM oa_outbox
             WHERE tenant_id = ? AND aggregate_type = ? AND aggregate_id = ? AND event_type = ?
               AND create_time >= ?
            """, Long.class, tenantId, aggregateType, aggregateId, eventType, since);
        return count == null ? 0 : count;
    }

    public boolean hasEvent(long tenantId, String dedupKey) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_outbox WHERE tenant_id = ? AND dedup_key = ?
            """, Long.class, tenantId, dedupKey);
        return count != null && count > 0;
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, String status, String keyword) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (event_type LIKE ? OR aggregate_id LIKE ? OR last_error LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
