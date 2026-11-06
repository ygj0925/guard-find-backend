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

package com.guardfind.backend.oa;

import com.guardfind.backend.oa.staff.JdbcOaStaffDirectory;
import com.guardfind.backend.oa.staff.OaAssigneeContext;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaAssigneeUnavailableException;
import com.guardfind.backend.oa.staff.OaAssigneeType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.4 候选解析：指定员工、岗位、部门负责人、直属主管、表单人员字段，以及离职/跨租户拦截。
 */
class OaAssigneeResolverTest {

    private static final long TENANT = 9101L;
    private static final long OTHER_TENANT = 9102L;

    private OaAssigneeResolver resolver;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:oa-staff-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_position (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_employee (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, supervisor_id BIGINT, employment_status VARCHAR(20) NOT NULL, dept_leader TINYINT NOT NULL DEFAULT 0, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_employee_position (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, position_id BIGINT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");

        user(jdbc, 101L, TENANT, 10L, 1);
        user(jdbc, 102L, TENANT, 10L, 1);
        user(jdbc, 103L, TENANT, 20L, 1);
        user(jdbc, 104L, TENANT, 10L, 1);
        user(jdbc, 105L, TENANT, 10L, 2);
        user(jdbc, 106L, OTHER_TENANT, 10L, 1);
        user(jdbc, 107L, TENANT, 10L, 1);
        user(jdbc, 108L, TENANT, null, 1);
        user(jdbc, 109L, TENANT, 10L, 1);

        employee(jdbc, 1L, 101L, 102L, "ACTIVE", 0);
        employee(jdbc, 2L, 102L, 103L, "ACTIVE", 1);
        employee(jdbc, 3L, 103L, null, "ACTIVE", 0);
        employee(jdbc, 4L, 104L, 102L, "RESIGNED", 0);
        employee(jdbc, 5L, 105L, 102L, "ACTIVE", 0);
        employee(jdbc, 6L, 106L, null, "ACTIVE", 1);
        employee(jdbc, 7L, 107L, 102L, "ACTIVE", 0);
        employee(jdbc, 8L, 108L, 102L, "ACTIVE", 0);
        employee(jdbc, 9L, 109L, 104L, "ACTIVE", 0);

        position(jdbc, 1L, TENANT, "FINANCE", "ENABLED");
        position(jdbc, 2L, TENANT, "LEGACY", "DISABLED");
        position(jdbc, 3L, TENANT, "EMPTY", "ENABLED");
        position(jdbc, 4L, OTHER_TENANT, "FINANCE", "ENABLED");

        positionAssignment(jdbc, 1L, 101L, 1L);
        positionAssignment(jdbc, 2L, 104L, 1L);
        positionAssignment(jdbc, 3L, 105L, 1L);
        positionAssignment(jdbc, 4L, 107L, 1L);
        positionAssignment(jdbc, 5L, 106L, 4L);
        positionAssignment(jdbc, 6L, 103L, 2L);

        resolver = new OaAssigneeResolver(new JdbcOaStaffDirectory(jdbc));
    }

    @Test
    void resolvesOnlyActiveEmployeesInSameTenant() {
        assertThat(resolver.resolve(OaAssigneeSpec.employee(101L), context(101L, Map.of()))).containsExactly(101L);
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.employee(104L), context(101L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("不是本租户在职员工");
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.employee(106L), context(101L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("不是本租户在职员工");
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.employee(105L), context(101L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("不是本租户在职员工");
    }

    @Test
    void resolvesPositionToActiveMembersOnly() {
        assertThat(resolver.resolve(OaAssigneeSpec.position("FINANCE"), context(101L, Map.of())))
            .containsExactly(101L, 107L);
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.position("LEGACY"), context(101L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("岗位不存在或已停用");
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.position("EMPTY"), context(101L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("未解析到本租户在职员工");
    }

    @Test
    void resolvesDepartmentLeaderAndApplicantSupervisor() {
        assertThat(resolver.resolve(OaAssigneeSpec.departmentLeader(), context(101L, Map.of()))).containsExactly(102L);
        assertThat(resolver.resolve(OaAssigneeSpec.supervisor(), context(101L, Map.of()))).containsExactly(102L);
        assertThat(resolver.resolve(OaAssigneeSpec.supervisor(), context(102L, Map.of()))).containsExactly(103L);
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.supervisor(), context(109L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("直属主管 不是本租户在职员工");
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.departmentLeader(), context(108L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("发起人未归属任何部门");
    }

    @Test
    void resolvesFormFieldAndRejectsInvalidPeople() {
        OaAssigneeSpec spec = OaAssigneeSpec.formField("approvers");
        assertThat(resolver.resolve(spec, context(101L, Map.of("approvers", List.of(101L, 107L, 101L)))))
            .containsExactly(101L, 107L);
        assertThat(resolver.resolve(spec, context(101L, Map.of("approvers", "107,101")))).containsExactly(101L, 107L);
        assertThatThrownBy(() -> resolver.resolve(spec, context(101L, Map.of("approvers", List.of(104L)))))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("表单人员 approvers 不是本租户在职员工");
        assertThatThrownBy(() -> resolver.resolve(spec, context(101L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("表单缺少人员字段");
        assertThatThrownBy(() -> resolver.resolve(spec, context(101L, Map.of("approvers", "abc"))))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("表单人员字段格式非法");
    }

    @Test
    void publishValidationRejectsEmptyOrResignedAssignees() {
        resolver.validateForPublish(OaAssigneeSpec.employee(101L), TENANT);
        resolver.validateForPublish(OaAssigneeSpec.position("FINANCE"), TENANT);
        resolver.validateForPublish(OaAssigneeSpec.supervisor(), TENANT);
        resolver.validateForPublish(OaAssigneeSpec.departmentLeader(), TENANT);
        resolver.validateForPublish(OaAssigneeSpec.formField("approvers"), TENANT);

        assertThatThrownBy(() -> resolver.validateForPublish(OaAssigneeSpec.employee(104L), TENANT))
            .isInstanceOf(OaAssigneeUnavailableException.class);
        assertThatThrownBy(() -> resolver.validateForPublish(OaAssigneeSpec.position("LEGACY"), TENANT))
            .isInstanceOf(OaAssigneeUnavailableException.class);
        assertThatThrownBy(() -> resolver.validateForPublish(OaAssigneeSpec.position("EMPTY"), TENANT))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("岗位下没有在职员工");
        assertThatThrownBy(() -> resolver.validateForPublish(List.of(), TENANT))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("未配置任何审批人");
    }

    @Test
    void rejectsResignedApplicantAndMalformedSpec() {
        assertThatThrownBy(() -> resolver.resolve(OaAssigneeSpec.employee(101L), context(104L, Map.of())))
            .isInstanceOf(OaAssigneeUnavailableException.class)
            .hasMessageContaining("发起人不是本租户在职员工");
        assertThatThrownBy(() -> OaAssigneeSpec.employee(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OaAssigneeSpec.position(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OaAssigneeSpec(OaAssigneeType.SUPERVISOR, "101"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static OaAssigneeContext context(long applicantId, Map<String, Object> formData) {
        return new OaAssigneeContext(TENANT, applicantId, formData);
    }

    private static void user(JdbcTemplate jdbc, long id, long tenantId, Long deptId, int status) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (?, ?, ?, ?, ?, 0)", id, tenantId, "user-" + id, deptId, status);
    }

    private static void employee(JdbcTemplate jdbc,
                                 long id,
                                 long userId,
                                 Long supervisorId,
                                 String status,
                                 int deptLeader) {
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)", id, TENANT, userId, supervisorId, status, deptLeader);
    }

    private static void position(JdbcTemplate jdbc, long id, long tenantId, String code, String status) {
        jdbc.update("INSERT INTO oa_position (id, tenant_id, code, status, deleted) VALUES (?, ?, ?, ?, 0)", id, tenantId, code, status);
    }

    private static void positionAssignment(JdbcTemplate jdbc, long id, long userId, long positionId) {
        jdbc.update("INSERT INTO oa_employee_position (id, tenant_id, user_id, position_id, deleted) VALUES (?, ?, ?, ?, 0)", id, TENANT, userId, positionId);
    }
}
