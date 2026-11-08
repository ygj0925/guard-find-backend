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

import com.guardfind.backend.oa.model.OaEmployeeCreateReq;
import com.guardfind.backend.oa.model.OaEmployeeResp;
import com.guardfind.backend.oa.model.OaEmployeeUpdateReq;
import com.guardfind.backend.oa.model.OaPositionCreateReq;
import com.guardfind.backend.oa.model.OaPositionResp;
import com.guardfind.backend.oa.model.OaPositionUpdateReq;
import com.guardfind.backend.oa.repository.OaStaffRepository;
import com.guardfind.backend.oa.service.OaStaffService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.starter.core.exception.BusinessException;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 员工/岗位管理：租户隔离、重复档案、主管环、岗位替换与选项过滤。
 */
class OaStaffServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:oa-staff-" + DB_SEQ
                .incrementAndGet() + ";DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=");
    }

    @Test
    void createsEmployeeWithPositionsAndRejectsDuplicateOrForeignUser() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long p1 = fixture.position("FINANCE", "财务");
            long p2 = fixture.position("HR", "人事");
            fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1003L, "E009", null, "ACTIVE", false, null, null, null));

            OaEmployeeResp created = fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, " E001 ", 1003L, "ACTIVE", true, LocalDate
                    .of(2024, 1, 5), null, List.of(p1, p2)));
            assertThat(created.userId()).isEqualTo(1001);
            assertThat(created.nickname()).isEqualTo("张三");
            assertThat(created.deptName()).isEqualTo("研发部");
            assertThat(created.employeeNo()).isEqualTo("E001");
            assertThat(created.supervisorName()).isEqualTo("王五");
            assertThat(created.employmentStatus()).isEqualTo("ACTIVE");
            assertThat(created.deptLeader()).isTrue();
            assertThat(created.positions()).extracting(position -> position.code())
                .containsExactlyInAnyOrder("HR", "FINANCE");
            assertThat(created.version()).isZero();

            assertThatThrownBy(() -> fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, null, null, "ACTIVE", false, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有 OA 员工档案");
            assertThatThrownBy(() -> fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(2001L, null, null, "ACTIVE", false, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前租户");
            assertThatThrownBy(() -> fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1002L, "E001", null, "ACTIVE", false, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工号已被其他员工使用");
        });
    }

    @Test
    void rejectsSupervisorCycleAndInactiveSupervisor() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaEmployeeResp boss = fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, null, null, "ACTIVE", false, null, null, null));
            fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1002L, null, 1001L, "ACTIVE", false, null, null, null));

            assertThatThrownBy(() -> fixture.service.updateEmployee(101, 7, boss
                .id(), new OaEmployeeUpdateReq(null, 1002L, "ACTIVE", false, null, null, null, boss.version())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("环路");

            OaEmployeeResp subordinate = fixture.service.detailEmployee(101, fixture.employeeId(1002));
            fixture.service.updateEmployee(101, 7, subordinate
                .id(), new OaEmployeeUpdateReq(null, null, "RESIGNED", false, null, null, null, subordinate.version()));
            assertThatThrownBy(() -> fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1003L, null, 1002L, "ACTIVE", false, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是本租户在职员工");
        });
    }

    @Test
    void replacesPositionsWithOptimisticLockAndKeepsTenantIsolation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long p1 = fixture.position("FINANCE", "财务");
            long p2 = fixture.position("HR", "人事");
            OaEmployeeResp created = fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, null, null, "ACTIVE", false, null, null, List
                    .of(p1)));

            OaEmployeeResp updated = fixture.service.updateEmployee(101, 7, created
                .id(), new OaEmployeeUpdateReq("E001", null, "SUSPENDED", false, null, LocalDate.of(2026, 5, 1), List
                    .of(p2), created.version()));
            assertThat(updated.version()).isEqualTo(1);
            assertThat(updated.employmentStatus()).isEqualTo("SUSPENDED");
            assertThat(updated.positions()).extracting(position -> position.code()).containsExactly("HR");

            assertThatThrownBy(() -> fixture.service.updateEmployee(101, 7, created
                .id(), new OaEmployeeUpdateReq(null, null, "ACTIVE", false, null, null, null, created.version())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他人修改");

            assertThat(fixture.service.pageEmployees(202, null, null, 1, 10)).isEmpty();
            assertThatThrownBy(() -> fixture.service.detailEmployee(202, created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("员工不存在");
        });
    }

    @Test
    void employeeOptionsOnlyReturnActiveEmployeesOfTenant() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, "E001", null, "ACTIVE", false, null, null, null));
            OaEmployeeResp suspended = fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1002L, "E002", null, "SUSPENDED", false, null, null, null));

            assertThat(fixture.service.employeeOptions(101, null)).extracting(option -> option.userId())
                .containsExactly(1001L);
            assertThat(fixture.service.employeeOptions(101, "李四")).isEmpty();
            assertThat(fixture.service.employeeOptions(202, null)).isEmpty();

            fixture.service.updateEmployee(101, 7, suspended
                .id(), new OaEmployeeUpdateReq("E002", null, "ACTIVE", false, null, null, null, suspended.version()));
            assertThat(fixture.service.employeeOptions(101, "李四")).extracting(option -> option.userId())
                .containsExactly(1002L);
            assertThat(fixture.service.userOptions(101, null)).extracting(option -> option.userId())
                .containsExactly(1003L);
        });
    }

    @Test
    void resignationHandsOverToSupervisorSetInSameSave() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1003L, null, null, "ACTIVE", false, null, null, null));
            OaEmployeeResp leaver = fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, null, null, "ACTIVE", false, null, null, null));

            fixture.service.updateEmployee(101, 7, leaver
                .id(), new OaEmployeeUpdateReq(null, 1003L, "RESIGNED", false, null, null, null, leaver.version()));

            assertThat(fixture.handovers).containsExactly(new long[] {101, 1001, 1003});
        });
    }

    @Test
    void positionCrudRejectsDuplicateCodeAndDisablesOptions() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaPositionResp finance = fixture.service
                .createPosition(101, 7, new OaPositionCreateReq("FINANCE", " 财务 ", 1));
            assertThat(finance.code()).isEqualTo("FINANCE");
            assertThat(finance.name()).isEqualTo("财务");
            assertThat(finance.status()).isEqualTo("ENABLED");
            assertThatThrownBy(() -> fixture.service
                .createPosition(101, 7, new OaPositionCreateReq("FINANCE", "财务2", 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("岗位编码已存在");

            fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, null, null, "ACTIVE", false, null, null, List
                    .of(finance.id())));
            assertThat(fixture.service.pagePositions(101, null, null, 1, 10).get(0).employeeCount()).isEqualTo(1);

            OaPositionResp disabled = fixture.service.updatePosition(101, 7, finance
                .id(), new OaPositionUpdateReq("财务中心", 5, "DISABLED", finance.version()));
            assertThat(disabled.status()).isEqualTo("DISABLED");
            assertThat(disabled.sort()).isEqualTo(5);
            assertThat(fixture.service.positionOptions(101)).isEmpty();
            assertThatThrownBy(() -> fixture.service.updatePosition(101, 7, finance
                .id(), new OaPositionUpdateReq("财务", 1, "ENABLED", finance.version())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他人修改");

            assertThat(fixture.service.pagePositions(202, null, null, 1, 10)).isEmpty();
            assertThatThrownBy(() -> fixture.service.detailPosition(202, finance.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("岗位不存在");
        });
    }

    @Test
    void rejectsDisabledOrForeignPositionOnEmployeeUpdate() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            long finance = fixture.position("FINANCE", "财务");
            OaEmployeeResp created = fixture.service
                .createEmployee(101, 7, new OaEmployeeCreateReq(1001L, null, null, "ACTIVE", false, null, null, null));

            fixture.jdbc.update("UPDATE oa_position SET status = 'DISABLED' WHERE tenant_id = 101 AND id = ?", finance);
            assertThatThrownBy(() -> fixture.service.updateEmployee(101, 7, created
                .id(), new OaEmployeeUpdateReq(null, null, "ACTIVE", false, null, null, List.of(finance), created
                    .version()))).isInstanceOf(BusinessException.class).hasMessageContaining("岗位不存在、已停用");

            long foreign = fixture.position(202, "FOREIGN", "外部岗位");
            assertThatThrownBy(() -> fixture.service.updateEmployee(101, 7, created
                .id(), new OaEmployeeUpdateReq(null, null, "ACTIVE", false, null, null, List.of(foreign), created
                    .version()))).isInstanceOf(BusinessException.class).hasMessageContaining("岗位不存在、已停用");
        });
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        seedUsers(jdbc);
        OaStaffRepository repository = new OaStaffRepository(jdbc);
        List<long[]> handovers = new java.util.ArrayList<>();
        OaStaffService service = new OaStaffService(repository, (tenantId,
                                                                 operatorId,
                                                                 fromUserId,
                                                                 targetUserId,
                                                                 reason) -> {
            handovers.add(new long[] {tenantId, fromUserId, targetUserId});
            return 0;
        }, context.getBean(PlatformTransactionManager.class));
        return new Fixture(service, jdbc, handovers);
    }

    private static void seedUsers(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_dept (id, name, deleted) VALUES (10, '研发部', 0)");
        insertUser(jdbc, 101, 1001, "zhangsan", "张三", 10);
        insertUser(jdbc, 101, 1002, "lisi", "李四", 10);
        insertUser(jdbc, 101, 1003, "wangwu", "王五", 10);
        insertUser(jdbc, 202, 2001, "other", "其他租户", 10);
    }

    private static void insertUser(JdbcTemplate jdbc,
                                   long tenantId,
                                   long id,
                                   String username,
                                   String nickname,
                                   long deptId) {
        jdbc.update("""
            INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted)
            VALUES (?, ?, ?, ?, ?, 1, 0)
            """, id, tenantId, username, nickname, deptId);
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE sys_dept (
              id BIGINT PRIMARY KEY,
              name VARCHAR(30) NOT NULL,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE sys_user (
              id BIGINT PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              username VARCHAR(64) NOT NULL,
              nickname VARCHAR(30) NOT NULL,
              dept_id BIGINT NOT NULL,
              status INT NOT NULL DEFAULT 1,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_position (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              code VARCHAR(64) NOT NULL,
              name VARCHAR(64) NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
              sort INT NOT NULL DEFAULT 0,
              version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL,
              update_user BIGINT,
              update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_oa_position UNIQUE (tenant_id, code, deleted)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_employee (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              employee_no VARCHAR(64),
              supervisor_id BIGINT,
              employment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
              dept_leader BOOLEAN NOT NULL DEFAULT FALSE,
              hire_date DATE,
              leave_date DATE,
              version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL,
              update_user BIGINT,
              update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_oa_employee_user UNIQUE (tenant_id, user_id, deleted),
              CONSTRAINT uk_oa_employee_no UNIQUE (tenant_id, employee_no, deleted)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_employee_position (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              position_id BIGINT NOT NULL,
              create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL,
              update_user BIGINT,
              update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_oa_employee_position UNIQUE (tenant_id, user_id, position_id, deleted)
            )
            """);
    }

    private record Fixture(OaStaffService service, JdbcTemplate jdbc, List<long[]> handovers) {

        long position(String code, String name) {
            return service.createPosition(101, 7, new OaPositionCreateReq(code, name, 0)).id();
        }

        long position(long tenantId, String code, String name) {
            return service.createPosition(tenantId, 7, new OaPositionCreateReq(code, name, 0)).id();
        }

        long employeeId(long userId) {
            Long id = jdbc
                .queryForObject("SELECT id FROM oa_employee WHERE tenant_id = 101 AND user_id = ? AND deleted = 0", Long.class, userId);
            return id == null ? 0 : id;
        }
    }
}
