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

package com.guardfind.backend.oa.staff;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 基于显式 SQL 的员工目录：每条查询都带 tenant_id，避免引擎/后台作业绕过数据拦截器。
 */
public class JdbcOaStaffDirectory implements OaStaffDirectory {

    private static final String ACTIVE_EMPLOYEE_SQL = """
        SELECT COUNT(*)
          FROM oa_employee e
          JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
         WHERE e.tenant_id = ?
           AND e.user_id = ?
           AND e.deleted = 0
           AND e.employment_status = 'ACTIVE'
           AND u.status = 1
        """;

    private static final String DEPARTMENT_SQL = """
        SELECT dept_id
          FROM sys_user
         WHERE tenant_id = ?
           AND id = ?
           AND deleted = 0
        """;

    private static final String SUPERVISOR_SQL = """
        SELECT supervisor_id
          FROM oa_employee
         WHERE tenant_id = ?
           AND user_id = ?
           AND deleted = 0
           AND employment_status = 'ACTIVE'
        """;

    private static final String POSITION_SQL = """
        SELECT COUNT(*)
          FROM oa_position
         WHERE tenant_id = ?
           AND code = ?
           AND status = 'ENABLED'
           AND deleted = 0
        """;

    private static final String POSITION_USERS_SQL = """
        SELECT DISTINCT e.user_id
          FROM oa_employee e
          JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
          JOIN oa_employee_position ep ON ep.tenant_id = e.tenant_id AND ep.user_id = e.user_id AND ep.deleted = 0
          JOIN oa_position p ON p.id = ep.position_id AND p.tenant_id = ep.tenant_id AND p.deleted = 0
         WHERE e.tenant_id = ?
           AND p.code = ?
           AND p.status = 'ENABLED'
           AND e.deleted = 0
           AND e.employment_status = 'ACTIVE'
           AND u.status = 1
         ORDER BY e.user_id
        """;

    private static final String DEPARTMENT_LEADERS_SQL = """
        SELECT DISTINCT e.user_id
          FROM oa_employee e
          JOIN sys_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id AND u.deleted = 0
         WHERE e.tenant_id = ?
           AND u.dept_id = ?
           AND e.dept_leader = 1
           AND e.deleted = 0
           AND e.employment_status = 'ACTIVE'
           AND u.status = 1
         ORDER BY e.user_id
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOaStaffDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean activeEmployee(long tenantId, long userId) {
        Integer count = jdbcTemplate.queryForObject(ACTIVE_EMPLOYEE_SQL, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    @Override
    public Long departmentId(long tenantId, long userId) {
        return firstLong(DEPARTMENT_SQL, tenantId, userId);
    }

    @Override
    public Long supervisorId(long tenantId, long userId) {
        return firstLong(SUPERVISOR_SQL, tenantId, userId);
    }

    @Override
    public boolean enabledPosition(long tenantId, String code) {
        Integer count = jdbcTemplate.queryForObject(POSITION_SQL, Integer.class, tenantId, code);
        return count != null && count > 0;
    }

    @Override
    public List<Long> activeUsersByPosition(long tenantId, String code) {
        return jdbcTemplate.queryForList(POSITION_USERS_SQL, Long.class, tenantId, code);
    }

    @Override
    public List<Long> departmentLeaders(long tenantId, long departmentId) {
        return jdbcTemplate.queryForList(DEPARTMENT_LEADERS_SQL, Long.class, tenantId, departmentId);
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> values = jdbcTemplate.queryForList(sql, Long.class, args);
        return values.isEmpty() ? null : values.get(0);
    }
}
