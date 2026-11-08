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

import com.guardfind.backend.oa.model.OaDelegateRuleResp;
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
import java.util.List;

/**
 * 限时代理规则的显式租户 SQL。所有匹配都要求规则启用且窗口覆盖当前时间。
 */
@Repository
public class OaDelegateRepository {

    private static final RowMapper<OaDelegateRuleResp> RULE_MAPPER = (rs, rowNum) -> new OaDelegateRuleResp(rs
        .getLong("id"), rs.getLong("delegator_id"), rs.getString("delegator_name"), rs.getLong("delegate_id"), rs
            .getString("delegate_name"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                .getString("status"), rs.getString("reason"), false, timestamp(rs, "create_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaDelegateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaDelegateRuleResp> rules(long tenantId, long delegatorId) {
        return jdbcTemplate.query("""
            SELECT r.id, r.delegator_id, du.nickname AS delegator_name, r.delegate_id,
                   gu.nickname AS delegate_name, r.start_time, r.end_time, r.status, r.reason, r.create_time
              FROM oa_delegate_rule r
              JOIN sys_user du ON du.id = r.delegator_id AND du.tenant_id = r.tenant_id AND du.deleted = 0
              JOIN sys_user gu ON gu.id = r.delegate_id AND gu.tenant_id = r.tenant_id AND gu.deleted = 0
             WHERE r.tenant_id = ? AND r.delegator_id = ? AND r.deleted = 0
             ORDER BY r.start_time DESC, r.id DESC
            """, RULE_MAPPER, tenantId, delegatorId);
    }

    /**
     * 当前用户作为代理人可接管的委托人（仅启用且窗口覆盖现在）。
     */
    public List<Delegation> activeDelegations(long tenantId, long delegateId, LocalDateTime now) {
        return jdbcTemplate.query("""
            SELECT r.delegator_id, u.nickname AS delegator_name
              FROM oa_delegate_rule r
              JOIN sys_user u ON u.id = r.delegator_id AND u.tenant_id = r.tenant_id AND u.deleted = 0
             WHERE r.tenant_id = ? AND r.delegate_id = ? AND r.status = 'ENABLED' AND r.deleted = 0
               AND r.start_time <= ? AND r.end_time > ?
             ORDER BY r.start_time, r.id
            """, (rs, rowNum) -> new Delegation(rs.getLong("delegator_id"), rs
            .getString("delegator_name")), tenantId, delegateId, now, now);
    }

    public boolean overlappingRuleExists(long tenantId,
                                         long delegatorId,
                                         long delegateId,
                                         LocalDateTime startTime,
                                         LocalDateTime endTime) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
              FROM oa_delegate_rule
             WHERE tenant_id = ? AND delegator_id = ? AND delegate_id = ? AND deleted = 0
               AND status = 'ENABLED' AND start_time < ? AND end_time > ?
            """, Integer.class, tenantId, delegatorId, delegateId, endTime, startTime);
        return count != null && count > 0;
    }

    /**
     * 代理链扩展：找出某委托人在给定窗口内已授权的代理人，用于环检测。
     */
    public List<Long> activeDelegatesOf(long tenantId,
                                        long delegatorId,
                                        LocalDateTime startTime,
                                        LocalDateTime endTime) {
        return jdbcTemplate.queryForList("""
            SELECT delegate_id
              FROM oa_delegate_rule
             WHERE tenant_id = ? AND delegator_id = ? AND deleted = 0 AND status = 'ENABLED'
               AND start_time < ? AND end_time > ?
            """, Long.class, tenantId, delegatorId, endTime, startTime);
    }

    public long insertRule(long tenantId,
                           long delegatorId,
                           long delegateId,
                           LocalDateTime startTime,
                           LocalDateTime endTime,
                           String reason,
                           long operatorId,
                           LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_delegate_rule
                  (tenant_id, delegator_id, delegate_id, start_time, end_time, status, reason,
                   create_user, create_time, deleted)
                VALUES (?, ?, ?, ?, ?, 'ENABLED', ?, ?, ?, 0)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, delegatorId);
            ps.setLong(3, delegateId);
            ps.setObject(4, startTime);
            ps.setObject(5, endTime);
            ps.setString(6, reason);
            ps.setLong(7, operatorId);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回代理规则 ID");
        }
        return key.longValue();
    }

    public int deleteRule(long tenantId, long id, long delegatorId, long operatorId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_delegate_rule
               SET status = 'DISABLED', deleted = id, update_user = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND delegator_id = ? AND deleted = 0
            """, operatorId, now, tenantId, id, delegatorId);
    }

    public OaDelegateRuleResp rule(long tenantId, long id, long delegatorId) {
        List<OaDelegateRuleResp> rules = jdbcTemplate.query("""
            SELECT r.id, r.delegator_id, du.nickname AS delegator_name, r.delegate_id,
                   gu.nickname AS delegate_name, r.start_time, r.end_time, r.status, r.reason, r.create_time
              FROM oa_delegate_rule r
              JOIN sys_user du ON du.id = r.delegator_id AND du.tenant_id = r.tenant_id AND du.deleted = 0
              JOIN sys_user gu ON gu.id = r.delegate_id AND gu.tenant_id = r.tenant_id AND gu.deleted = 0
             WHERE r.tenant_id = ? AND r.id = ? AND r.delegator_id = ? AND r.deleted = 0
            """, RULE_MAPPER, tenantId, id, delegatorId);
        return rules.isEmpty() ? null : rules.get(0);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record Delegation(long delegatorId, String delegatorName) {
    }
}
