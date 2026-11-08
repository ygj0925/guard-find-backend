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

import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.business.OaBusinessHooks;
import com.guardfind.backend.oa.leave.OaLeaveHook;
import com.guardfind.backend.oa.repository.OaAttendanceRepository;
import com.guardfind.backend.oa.repository.OaLeaveRepository;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaProcessModelUpdateReq;
import com.guardfind.backend.oa.model.OaProcessPublishReq;
import com.guardfind.backend.oa.model.OaProcessPublishResp;
import com.guardfind.backend.oa.repository.OaProcessModelRepository;
import com.guardfind.backend.oa.service.OaProcessModelService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.7 发布/停用与版本绑定验证：发布事务、不可变版本、旧实例不迁移、Key 稳定性与停用。
 */
class OaProcessModelServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaPublish" name="发布验证" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
            <bpmn:userTask id="approve" name="审批" flowable:assignee="${approver}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="approve" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String INVALID_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="broken" isExecutable="true">
            <bpmn:startEvent id="start"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String STATIC_ASSIGNEE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaStatic" name="静态审批人" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
            <bpmn:userTask id="approve" name="审批" flowable:assignee="employee:999"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="approve" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:oa-publish-" + DB_SEQ
                .incrementAndGet() + ";DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    @Test
    void createsDraftAndRejectsStaleUpdate() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", "hr", "说明", BPMN, null));
            assertThat(created.status()).isEqualTo("DRAFT");
            assertThat(created.version()).isZero();
            assertThat(created.xmlDigest()).hasSize(64);

            OaProcessModelResp updated = fixture.service.update(101, 7, created
                .id(), new OaProcessModelUpdateReq("请假流程", "hr", "说明", BPMN, 0));
            assertThat(updated.name()).isEqualTo("请假流程");
            assertThat(updated.version()).isEqualTo(1);

            assertThatThrownBy(() -> fixture.service.update(101, 7, created
                .id(), new OaProcessModelUpdateReq("旧版本", null, null, BPMN, 0))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被其他人修改");
        });
    }

    @Test
    void publishesWithFormBindingAndActivatesRelease() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", null, null, BPMN, null));

            assertThat(fixture.service.validate(BPMN).valid()).isTrue();
            OaProcessPublishResp published = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, "{\"depts\":[1]}", "首次发布"));

            assertThat(published.valid()).isTrue();
            assertThat(published.releaseVersion()).isEqualTo(1);
            assertThat(published.processDefinitionVersion()).isEqualTo(1);
            assertThat(published.engineDefinitionId()).isNotBlank();

            OaProcessModelResp detail = fixture.service.detail(101, created.id());
            assertThat(detail.status()).isEqualTo("PUBLISHED");
            assertThat(detail.currentReleaseVersion()).isEqualTo(1);
            assertThat(detail.currentReleaseId()).isEqualTo(published.releaseId());
            assertThat(detail.formVersionId()).isEqualTo(9001L);

            var releases = fixture.service.releases(101, created.id());
            assertThat(releases).hasSize(1);
            assertThat(releases.get(0).status()).isEqualTo("ACTIVE");
            assertThat(releases.get(0).formVersion()).isEqualTo(3);
            assertThat(releases.get(0).formId()).isEqualTo(9000L);
            assertThat(releases.get(0).launchScope()).isEqualTo("{\"depts\":[1]}");
            assertThat(releases.get(0).publishedBy()).isEqualTo(7);

            var page = fixture.service.page(101, "请假", "PUBLISHED", 1, 10);
            assertThat(page).hasSize(1);
            // 列表不返回 XML，详情才下发完整内容
            assertThat(page.get(0).bpmnXml()).isNull();
            assertThat(detail.bpmnXml()).contains("bpmn:process");
            assertThat(fixture.service.page(202, "请假", null, 1, 10)).isEmpty();
        });
    }

    @Test
    void leaveModelRejectsFormWithoutLeaveFields() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", null, null, BPMN, "LEAVE"));
            assertThat(created.businessType()).isEqualTo("LEAVE");

            OaProcessPublishResp result = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, null, null));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(error -> error.contains("leaveType"))
                .anyMatch(error -> error.contains("startDate"));
            assertThat(fixture.service.releases(101, created.id())).isEmpty();
            assertThat(fixture.service.detail(101, created.id()).status()).isEqualTo("DRAFT");
        });
    }

    @Test
    void rejectsPublishWithoutFormVersion() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", null, null, BPMN, null));

            OaProcessPublishResp result = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(null, null, null));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).containsExactly("发布必须绑定表单版本");
            assertThat(fixture.service.releases(101, created.id())).isEmpty();
            assertThat(fixture.service.detail(101, created.id()).status()).isEqualTo("DRAFT");
            assertThat(fixture.repositoryService.createDeploymentQuery().deploymentTenantId("101").count()).isZero();
        });
    }

    @Test
    void republishDoesNotMigrateRunningInstance() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", null, null, BPMN, null));
            OaProcessPublishResp v1 = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, null, null));
            ProcessInstance running = fixture.runtimeService
                .startProcessInstanceByKeyAndTenantId("oaPublish", "biz-1", Map.of("approver", "7"), "101");

            fixture.service.update(101, 7, created.id(), new OaProcessModelUpdateReq("请假流程", null, null, BPMN, 1));
            OaProcessPublishResp v2 = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, null, "第二版"));

            assertThat(v2.releaseVersion()).isEqualTo(2);
            assertThat(v2.processDefinitionVersion()).isEqualTo(2);
            assertThat(v1.engineDefinitionId()).isNotEqualTo(v2.engineDefinitionId());

            ProcessInstance stillRunning = fixture.runtimeService.createProcessInstanceQuery()
                .processInstanceId(running.getId())
                .singleResult();
            assertThat(stillRunning.getProcessDefinitionId()).isEqualTo(v1.engineDefinitionId());
            List<Task> tasks = fixture.taskService.createTaskQuery()
                .processInstanceId(running.getId())
                .taskTenantId("101")
                .list();
            assertThat(tasks).hasSize(1);
            fixture.taskService.complete(tasks.get(0).getId());
            assertThat(fixture.runtimeService.createProcessInstanceQuery().processInstanceId(running.getId()).count())
                .isZero();

            ProcessInstance fresh = fixture.runtimeService
                .startProcessInstanceByKeyAndTenantId("oaPublish", "biz-2", Map.of("approver", "7"), "101");
            assertThat(fresh.getProcessDefinitionId()).isEqualTo(v2.engineDefinitionId());

            assertThat(fixture.service.releases(101, created.id())).extracting("status")
                .containsExactly("ACTIVE", "STOPPED");
        });
    }

    @Test
    void rejectsInvalidXmlWithoutDeploymentOrRelease() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("broken", "坏流程", null, null, INVALID_BPMN, null));

            OaProcessPublishResp result = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, null, null));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).isNotEmpty();
            assertThat(fixture.service.releases(101, created.id())).isEmpty();
            assertThat(fixture.service.detail(101, created.id()).status()).isEqualTo("DRAFT");
            assertThat(fixture.repositoryService.createDeploymentQuery().deploymentTenantId("101").count()).isZero();
        });
    }

    @Test
    void disablesAndRepublishesWhileRejectingSharedProcessKey() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", null, null, BPMN, null));
            fixture.service.publish(101, 7, created.id(), new OaProcessPublishReq(9001L, null, null));

            OaProcessModelResp disabled = fixture.service.disable(101, 7, created.id());
            assertThat(disabled.status()).isEqualTo("DISABLED");
            assertThat(fixture.service.releases(101, created.id())).extracting("status").containsExactly("STOPPED");

            OaProcessPublishResp again = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, null, "重新启用"));
            assertThat(again.valid()).isTrue();
            assertThat(again.releaseVersion()).isEqualTo(2);
            assertThat(fixture.service.detail(101, created.id()).status()).isEqualTo("PUBLISHED");

            OaProcessModelResp other = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave-other", "另一个请假", null, null, BPMN, null));
            OaProcessPublishResp conflict = fixture.service.publish(101, 7, other
                .id(), new OaProcessPublishReq(9001L, null, null));
            assertThat(conflict.valid()).isFalse();
            assertThat(conflict.errors()).anyMatch(error -> error.contains("已被其他模型占用"));
        });
    }

    @Test
    void rejectsInvalidLaunchScopeBeforeDeployment() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("leave", "请假", null, null, BPMN, null));

            OaProcessPublishResp result = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, "{\"users\":[\"abc\"]}", null));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(error -> error.contains("可发起范围无效"));
            assertThat(fixture.repositoryService.createDeploymentQuery().deploymentTenantId("101").count()).isZero();
            assertThat(fixture.service.releases(101, created.id())).isEmpty();
            assertThat(fixture.service.detail(101, created.id()).status()).isEqualTo("DRAFT");
        });
    }

    @Test
    void rejectsUnavailableStaticAssigneeBeforeDeployment() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            insertFormVersion(fixture.jdbc);
            OaProcessModelResp created = fixture.service
                .create(101, 7, new OaProcessModelCreateReq("static", "静态审批人", null, null, STATIC_ASSIGNEE_BPMN, null));

            OaProcessPublishResp result = fixture.service.publish(101, 7, created
                .id(), new OaProcessPublishReq(9001L, null, null));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(error -> error.contains("不是本租户在职员工"));
            assertThat(fixture.repositoryService.createDeploymentQuery().deploymentTenantId("101").count()).isZero();
            assertThat(fixture.service.releases(101, created.id())).isEmpty();
            assertThat(fixture.service.detail(101, created.id()).status()).isEqualTo("DRAFT");
        });
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        OaProcessModelRepository repository = new OaProcessModelRepository(jdbc);
        OaProcessModelService service = new OaProcessModelService(repository, new OaBpmnValidator(List
            .of()), new OaAssigneeResolver(new StubStaffDirectory()), new OaBusinessHooks(List
                .of(new OaLeaveHook(new OaLeaveRepository(jdbc), new OaAttendanceRepository(jdbc))), null), context
                    .getBean(RepositoryService.class), context.getBean(PlatformTransactionManager.class));
        return new Fixture(service, repository, jdbc, context.getBean(RuntimeService.class), context
            .getBean(TaskService.class), context.getBean(RepositoryService.class));
    }

    /** 静态审批人校验桩：只认 7 号员工与 FINANCE 岗位。 */
    private static final class StubStaffDirectory implements OaStaffDirectory {

        @Override
        public boolean activeEmployee(long tenantId, long userId) {
            return userId == 7L;
        }

        @Override
        public Long departmentId(long tenantId, long userId) {
            return 10L;
        }

        @Override
        public Long supervisorId(long tenantId, long userId) {
            return 7L;
        }

        @Override
        public boolean enabledPosition(long tenantId, String code) {
            return "FINANCE".equals(code);
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            return "FINANCE".equals(code) ? List.of(7L) : List.of();
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(7L);
        }
    }

    private static void insertFormVersion(JdbcTemplate jdbc) {
        jdbc.update("""
            INSERT INTO oa_form_version (id, tenant_id, form_id, version, schema_json, schema_digest,
                                         field_count, published_by, publish_time)
            VALUES (9001, 101, 9000, 3, '{}', REPEAT('a', 64), 0, 7, CURRENT_TIMESTAMP)
            """);
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_process_model (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              model_key VARCHAR(64) NOT NULL,
              name VARCHAR(100) NOT NULL,
              category VARCHAR(64),
              description VARCHAR(500),
              business_type VARCHAR(32) NOT NULL DEFAULT 'GENERIC',
              bpmn_xml CLOB,
              xml_digest VARCHAR(64),
              status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
              current_release_id BIGINT,
              current_release_version INT NOT NULL DEFAULT 0,
              form_id BIGINT,
              form_version_id BIGINT,
              version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL,
              update_user BIGINT,
              update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_oa_model_key UNIQUE (tenant_id, model_key, deleted)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_process_release (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              model_id BIGINT NOT NULL,
              release_version INT NOT NULL,
              bpmn_xml CLOB NOT NULL,
              xml_digest VARCHAR(64) NOT NULL,
              engine_definition_id VARCHAR(128) NOT NULL,
              process_definition_key VARCHAR(128) NOT NULL,
              process_definition_version INT NOT NULL,
              form_id BIGINT,
              form_version_id BIGINT,
              form_version INT,
              launch_scope VARCHAR(2000),
              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
              published_by BIGINT NOT NULL,
              publish_time TIMESTAMP NOT NULL,
              remark VARCHAR(500),
              CONSTRAINT uk_oa_release_version UNIQUE (tenant_id, model_id, release_version),
              CONSTRAINT uk_oa_engine_definition UNIQUE (engine_definition_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_form_version (
              id BIGINT PRIMARY KEY,
              tenant_id BIGINT NOT NULL,
              form_id BIGINT NOT NULL,
              version INT NOT NULL,
              schema_json CLOB NOT NULL,
              schema_digest VARCHAR(64) NOT NULL,
              field_count INT NOT NULL DEFAULT 0,
              published_by BIGINT NOT NULL,
              publish_time TIMESTAMP NOT NULL
            )
            """);
    }

    private record Fixture(OaProcessModelService service, OaProcessModelRepository repository, JdbcTemplate jdbc,
                           RuntimeService runtimeService, TaskService taskService,
                           RepositoryService repositoryService) {
    }
}
