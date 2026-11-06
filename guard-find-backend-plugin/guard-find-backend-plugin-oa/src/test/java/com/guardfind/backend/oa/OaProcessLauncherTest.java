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

import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.config.OaStaffConfiguration;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaAssigneeUnavailableException;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.4 执行拦截：候选解析失败、发起人离职、跨租户人员都必须在引擎写入前失败。
 */
class OaProcessLauncherTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final String TENANT = "9101";
    private static final String OTHER_TENANT = "9102";

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://guardfind/backend/oa">
          <process id="oaCandidate" name="OA Candidate" isExecutable="true">
            <startEvent id="start"/>
            <sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
            <userTask id="approve" name="审批" flowable:candidateUsers="${oaCandidates}"/>
            <sequenceFlow id="flow2" sourceRef="approve" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    @Configuration(proxyBeanMethods = false)
    static class DeploymentConfiguration {

        @Bean
        InitializingBean oaCandidateDeployments(RepositoryService repositoryService) {
            return () -> {
                repositoryService.createDeployment()
                    .tenantId(TENANT)
                    .addString("oa-candidate.bpmn20.xml", BPMN)
                    .deploy();
                repositoryService.createDeployment()
                    .tenantId(OTHER_TENANT)
                    .addString("oa-candidate.bpmn20.xml", BPMN)
                    .deploy();
            };
        }
    }

    @Test
    void startsWithResolvedCandidates() {
        runner().run(context -> {
            seed(context.getBean(JdbcTemplate.class));
            OaProcessLauncher launcher = context.getBean(OaProcessLauncher.class);
            launcher.start("oaCandidate", 9101L, "biz-valid", 101L, Map.of(), Map.of("oaCandidates", OaAssigneeSpec
                .position("FINANCE")));

            TaskService taskService = context.getBean(TaskService.class);
            assertThat(taskService.createTaskQuery().taskTenantId(TENANT).taskCandidateUser("101").count())
                .isEqualTo(1);
            assertThat(taskService.createTaskQuery().taskTenantId(TENANT).taskCandidateUser("103").count())
                .isEqualTo(1);
            assertThat(taskService.createTaskQuery().taskTenantId(TENANT).taskCandidateUser("102").count()).isZero();
            assertThat(context.getBean(OaEngineTemplate.class).countRunning(TENANT)).isEqualTo(1);
        });
    }

    @Test
    void blocksStartWhenCandidateListIsEmpty() {
        runner().run(context -> {
            seed(context.getBean(JdbcTemplate.class));
            OaProcessLauncher launcher = context.getBean(OaProcessLauncher.class);
            assertThatThrownBy(() -> launcher.start("oaCandidate", 9101L, "biz-empty", 101L, Map.of(), Map
                .of("oaCandidates", OaAssigneeSpec.position("EMPTY"))))
                .isInstanceOf(OaAssigneeUnavailableException.class)
                .hasMessageContaining("审批人变量 oaCandidates 解析失败");
            assertThat(context.getBean(OaEngineTemplate.class).countRunning(TENANT)).isZero();
            assertThat(context.getBean(TaskService.class).createTaskQuery().taskTenantId(TENANT).count()).isZero();
        });
    }

    @Test
    void blocksResignedApplicantAndCrossTenantCandidate() {
        runner().run(context -> {
            seed(context.getBean(JdbcTemplate.class));
            OaProcessLauncher launcher = context.getBean(OaProcessLauncher.class);
            assertThatThrownBy(() -> launcher.start("oaCandidate", 9101L, "biz-resigned", 104L, Map.of(), Map
                .of("oaCandidates", OaAssigneeSpec.position("FINANCE"))))
                .isInstanceOf(OaAssigneeUnavailableException.class)
                .hasMessageContaining("发起人不是本租户在职员工");
            assertThatThrownBy(() -> launcher.start("oaCandidate", 9101L, "biz-cross", 101L, Map.of("approvers", List
                .of(105L)), Map.of("oaCandidates", OaAssigneeSpec.formField("approvers"))))
                .isInstanceOf(OaAssigneeUnavailableException.class)
                .hasMessageContaining("表单人员 approvers 不是本租户在职员工");
            assertThat(context.getBean(OaEngineTemplate.class).countRunning(TENANT)).isZero();
        });
    }

    @Test
    void blocksWhenSupervisorResigned() {
        runner().run(context -> {
            seed(context.getBean(JdbcTemplate.class));
            OaProcessLauncher launcher = context.getBean(OaProcessLauncher.class);
            assertThatThrownBy(() -> launcher.start("oaCandidate", 9101L, "biz-supervisor", 106L, Map.of(), Map
                .of("oaCandidates", OaAssigneeSpec.supervisor()))).isInstanceOf(OaAssigneeUnavailableException.class)
                .hasMessageContaining("直属主管 不是本租户在职员工");
            assertThat(context.getBean(OaEngineTemplate.class).countRunning(TENANT)).isZero();
        });
    }

    @Test
    void exposesStaffBeans() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(OaStaffDirectory.class);
            assertThat(context).hasSingleBean(OaAssigneeResolver.class);
            assertThat(context).hasSingleBean(OaProcessLauncher.class);
        });
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-launcher-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, ProcessEngineAutoConfiguration.class, ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class, OaStaffConfiguration.class, DeploymentConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_position (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, status VARCHAR(20) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_employee (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, supervisor_id BIGINT, employment_status VARCHAR(20) NOT NULL, dept_leader TINYINT NOT NULL DEFAULT 0, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_employee_position (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, position_id BIGINT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");

        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (101, 9101, 'Alice', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (102, 9101, 'Manager', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (103, 9101, 'Finance', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (104, 9101, 'Resigned', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (105, 9102, 'Other', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, nickname, dept_id, status, deleted) VALUES (106, 9101, 'NoSupervisor', 10, 1, 0)");

        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (1, 9101, 101, 102, 'ACTIVE', 0, 0)");
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (2, 9101, 102, NULL, 'ACTIVE', 1, 0)");
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (3, 9101, 103, 102, 'ACTIVE', 0, 0)");
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (4, 9101, 104, 102, 'RESIGNED', 0, 0)");
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (5, 9102, 105, NULL, 'ACTIVE', 0, 0)");
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, employment_status, dept_leader, deleted) VALUES (6, 9101, 106, 104, 'ACTIVE', 0, 0)");

        jdbc.update("INSERT INTO oa_position (id, tenant_id, code, status, deleted) VALUES (1, 9101, 'FINANCE', 'ENABLED', 0)");
        jdbc.update("INSERT INTO oa_position (id, tenant_id, code, status, deleted) VALUES (2, 9101, 'EMPTY', 'ENABLED', 0)");
        jdbc.update("INSERT INTO oa_employee_position (id, tenant_id, user_id, position_id, deleted) VALUES (1, 9101, 101, 1, 0)");
        jdbc.update("INSERT INTO oa_employee_position (id, tenant_id, user_id, position_id, deleted) VALUES (2, 9101, 103, 1, 0)");
        jdbc.update("INSERT INTO oa_employee_position (id, tenant_id, user_id, position_id, deleted) VALUES (3, 9101, 104, 1, 0)");
    }
}
