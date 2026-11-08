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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaAssigneeBindingReq;
import com.guardfind.backend.oa.model.OaLaunchDetailResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitResp;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.service.OaApplicationService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
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
 * 10.1 发起闭环：范围拦截、表单校验、审批人自动绑定、幂等回放与申请轨迹。
 */
class OaLaunchServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final String PROCESS_KEY = "oaLaunch";

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaLaunch" name="发起验证" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
            <bpmn:userTask id="approve" name="审批" flowable:assignee="${approver}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="approve" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    private static final String STATIC_BPMN = BPMN
        .replace("flowable:assignee=\"${approver}\"", "flowable:assignee=\"employee:102\"");

    private static final String SCHEMA = """
        {"version":1,"fields":[
          {"id":"reason","key":"reason","label":"事由","type":"text","required":true},
          {"id":"approver","key":"approver","label":"审批人","type":"person","required":false},
          {"id":"proof","key":"proof","label":"附件","type":"attachment","required":false}
        ]}
        """;

    @Test
    void listsOnlyReleasesInLaunchScope() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            assertThat(fixture.launchService.page(TENANT, 101, null, null, 1, 10).items()).hasSize(1);
            assertThat(fixture.launchService.page(TENANT, 103, null, null, 1, 10).items()).isEmpty();
            assertThat(fixture.launchService.page(202, 101, null, null, 1, 10).items()).isEmpty();
        });
    }

    @Test
    void submitsWithAutoFormFieldAssigneeAndReplaysRequest() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            OaLaunchDetailResp detail = fixture.launchService.detail(TENANT, 101, 10);
            assertThat(detail.launchable()).isTrue();
            assertThat(detail.assigneeVariables()).hasSize(1);
            assertThat(detail.assigneeVariables().get(0).name()).isEqualTo("approver");
            assertThat(detail.assigneeVariables().get(0).suggestedType()).isEqualTo("FORM_FIELD");
            assertThat(detail.assigneeVariables().get(0).suggestedValue()).isEqualTo("approver");

            OaLaunchSubmitReq req = new OaLaunchSubmitReq("req-1", "张三的请假", Map.of("reason", "事假", "approver", List
                .of(102)), List.of());
            OaLaunchSubmitResp resp = fixture.launchService.submit(TENANT, 101, 10, req);

            assertThat(resp.replayed()).isFalse();
            assertThat(resp.businessKey()).startsWith("OA-10-");
            assertThat(resp.processInstanceId()).isNotBlank();
            assertThat(fixture.taskService.createTaskQuery()
                .taskTenantId(Long.toString(TENANT))
                .taskAssignee("102")
                .count()).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, resp
                .applicationId())).isEqualTo("RUNNING");
            assertThat(fixture.jdbc
                .queryForObject("SELECT applicant_name FROM oa_application WHERE id = ?", String.class, resp
                    .applicationId())).isEqualTo("Alice");
            assertThat(fixture.jdbc
                .queryForObject("SELECT applicant_dept_name FROM oa_application WHERE id = ?", String.class, resp
                    .applicationId())).isEqualTo("研发部");
            assertThat(fixture.jdbc
                .queryForObject("SELECT form_version FROM oa_application WHERE id = ?", Integer.class, resp
                    .applicationId())).isEqualTo(1);
            assertThat(fixture.jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_approval_audit WHERE application_id = ? AND action = 'SUBMIT'", Integer.class, resp
                    .applicationId())).isEqualTo(1);
            assertThat(fixture.jdbc
                .queryForObject("SELECT status FROM oa_request_dedup WHERE request_id = 'req-1'", String.class))
                .isEqualTo("SUCCEEDED");

            OaLaunchSubmitResp replayed = fixture.launchService.submit(TENANT, 101, 10, req);
            assertThat(replayed.replayed()).isTrue();
            assertThat(replayed.applicationId()).isEqualTo(resp.applicationId());
            assertThat(replayed.processInstanceId()).isEqualTo(resp.processInstanceId());
            assertThat(fixture.runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(Long.toString(TENANT))
                .count()).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_application", Integer.class)).isEqualTo(1);

            var applications = fixture.applicationService.page(TENANT, 101, null, null, 1, 10);
            assertThat(applications).hasSize(1);
            var application = fixture.applicationService.detail(TENANT, 101, applications.get(0).id());
            assertThat(application.formData()).containsEntry("reason", "事假");
            assertThat(application.timeline()).hasSize(1);
            assertThat(application.timeline().get(0).action()).isEqualTo("SUBMIT");
            assertThat(application.timeline().get(0).operatorName()).isEqualTo("Alice");
            assertThatThrownBy(() -> fixture.applicationService.detail(TENANT, 103, applications.get(0).id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("申请不存在");
        });
    }

    @Test
    void rejectsInvalidFormWithoutStartingProcess() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            OaLaunchSubmitReq req = new OaLaunchSubmitReq("req-invalid", null, Map.of("approver", List.of(102)), List
                .of());

            assertThatThrownBy(() -> fixture.launchService.submit(TENANT, 101, 10, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("表单校验失败");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_application", Integer.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_request_dedup", Integer.class)).isZero();
        });
    }

    @Test
    void rejectsMultiUserAssigneeAndStaticAssignments() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            OaLaunchSubmitReq req = new OaLaunchSubmitReq("req-position", null, Map.of("reason", "事假"), List
                .of(new OaAssigneeBindingReq("approver", "POSITION", "FINANCE")));

            assertThatThrownBy(() -> fixture.launchService.submit(TENANT, 101, 10, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能解析出一名员工");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
        });
    }

    @Test
    void rejectsStaticAssigneeBpmnAtLaunch() {
        runner().run(context -> {
            Fixture fixture = fixture(context, STATIC_BPMN);
            assertThatThrownBy(() -> fixture.launchService.detail(TENANT, 101, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("静态审批人");
        });
    }

    @Test
    void submitsAttachmentAndLinksItToApplication() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            seedFile(fixture.jdbc, 501, TENANT, 101, "stored.pdf", "报告.pdf", 1024);
            OaLaunchSubmitReq req = new OaLaunchSubmitReq("req-attach", null, Map.of("reason", "事假", "approver", List
                .of(102), "proof", List.of(Map.of("uid", "u1", "name", "客户端名.pdf", "fileId", 501))), List.of());
            OaLaunchSubmitResp resp = fixture.launchService.submit(TENANT, 101, 10, req);

            Map<String, Object> link = fixture.jdbc
                .queryForMap("SELECT * FROM oa_attachment_link WHERE tenant_id = ? AND application_id = ?", TENANT, resp
                    .applicationId());
            assertThat(((Number)link.get("file_id")).longValue()).isEqualTo(501L);
            assertThat(link.get("file_name")).isEqualTo("报告.pdf");
            assertThat(link.get("scan_status")).isEqualTo("SKIPPED");
            assertThat(((Number)link.get("available")).intValue()).isEqualTo(1);
            assertThat(link.get("business_type")).isEqualTo("leave");

            var detail = fixture.applicationService.detail(TENANT, 101, resp.applicationId());
            var stored = (List<?>)detail.formData().get("proof");
            assertThat(stored).hasSize(1);
            assertThat(((Map<?, ?>)stored.get(0)).get("fileId")).isEqualTo("501");
            assertThat(((Map<?, ?>)stored.get(0)).get("name")).isEqualTo("报告.pdf");
        });
    }

    @Test
    void rejectsAttachmentUploadedByAnotherUserWithoutSideEffects() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            seedFile(fixture.jdbc, 502, TENANT, 103, "other.pdf", "他人附件.pdf", 1024);
            OaLaunchSubmitReq req = new OaLaunchSubmitReq("req-foreign", null, Map.of("reason", "事假", "approver", List
                .of(102), "proof", List.of(Map.of("fileId", 502))), List.of());

            assertThatThrownBy(() -> fixture.launchService.submit(TENANT, 101, 10, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不存在或无权使用");
            assertThat(fixture.runtimeService.createProcessInstanceQuery().count()).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_application", Integer.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_request_dedup", Integer.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_attachment_link", Integer.class)).isZero();
        });
    }

    @Test
    void rejectsAttachmentAlreadyLinkedOrNotUploaded() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            seedFile(fixture.jdbc, 503, TENANT, 101, "used.pdf", "已用.pdf", 1024);
            fixture.jdbc
                .update("""
                    INSERT INTO oa_attachment_link
                      (tenant_id, application_id, business_type, business_key, file_id, file_name, file_size,
                       content_type, category, scan_status, available, create_user, create_time, deleted)
                    VALUES (101, 999, 'leave', 'OA-OLD', 503, '已用.pdf', 1024, 'application/pdf', 'FORM', 'SKIPPED', 1, 101, CURRENT_TIMESTAMP, 0)
                    """);
            OaLaunchSubmitReq linked = new OaLaunchSubmitReq("req-linked", null, Map.of("reason", "事假", "approver", List
                .of(102), "proof", List.of(Map.of("fileId", 503))), List.of());
            assertThatThrownBy(() -> fixture.launchService.submit(TENANT, 101, 10, linked))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件已被其他申请使用");

            OaLaunchSubmitReq legacy = new OaLaunchSubmitReq("req-legacy", null, Map.of("reason", "事假", "approver", List
                .of(102), "proof", List.of("报告.pdf")), List.of());
            assertThatThrownBy(() -> fixture.launchService.submit(TENANT, 101, 10, legacy))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件未完成上传");
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM oa_attachment_link", Integer.class))
                .isEqualTo(1);
        });
    }

    @Test
    void downloadIsLimitedToApplicationParticipants() {
        runner().run(context -> {
            Fixture fixture = fixture(context, BPMN);
            seedFile(fixture.jdbc, 501, TENANT, 101, "stored.pdf", "报告.pdf", 1024);
            OaLaunchSubmitReq req = new OaLaunchSubmitReq("req-download", null, Map.of("reason", "事假", "approver", List
                .of(102), "proof", List.of(Map.of("fileId", 501))), List.of());
            OaLaunchSubmitResp resp = fixture.launchService.submit(TENANT, 101, 10, req);

            OaAttachmentService.DownloadAttachment applicant = fixture.attachmentService
                .authorizeDownload(TENANT, 101, 501);
            assertThat(applicant.fileName()).isEqualTo("报告.pdf");
            assertThat(applicant.fileInfo().getPlatform()).isEqualTo("local");
            assertThat(applicant.fileInfo().getPath()).isEqualTo("2026/11/08/");
            assertThat(fixture.attachmentService.authorizeDownload(TENANT, 102, 501)).isNotNull();

            fixture.jdbc.update("""
                INSERT INTO oa_approval_audit
                  (tenant_id, application_id, process_instance_id, task_id, action, operator_id, operator_name,
                   request_id, detail_json, create_time)
                VALUES (101, ?, ?, NULL, 'APPROVE', 104, '历史审批人', 'req-history', '{}', CURRENT_TIMESTAMP)
                """, resp.applicationId(), resp.processInstanceId());
            assertThat(fixture.attachmentService.authorizeDownload(TENANT, 104, 501)).isNotNull();

            assertThatThrownBy(() -> fixture.attachmentService.authorizeDownload(TENANT, 103, 501))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不存在或无权访问");
            assertThatThrownBy(() -> fixture.attachmentService.authorizeDownload(202, 101, 501))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不存在或无权访问");

            fixture.jdbc.update("UPDATE oa_attachment_link SET available = 0 WHERE tenant_id = 101 AND file_id = 501");
            assertThatThrownBy(() -> fixture.attachmentService.authorizeDownload(TENANT, 101, 501))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不存在或无权访问");
        });
    }

    private static void seedFile(JdbcTemplate jdbc,
                                 long id,
                                 long tenantId,
                                 long uploaderId,
                                 String name,
                                 String originalName,
                                 long size) {
        jdbc.update("MERGE INTO sys_storage KEY (id) VALUES (1, 'local', 0)");
        jdbc.update("""
            INSERT INTO sys_file
              (id, tenant_id, name, original_name, size, parent_path, path, extension, content_type,
               type, storage_id, create_user, deleted)
            VALUES (?, ?, ?, ?, ?, '/2026/11/08', ?, 'pdf', 'application/pdf', 1, 1, ?, 0)
            """, id, tenantId, name, originalName, size, "/2026/11/08/" + name, uploaderId);
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-launch-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context, String bpmn) {
        DataSource dataSource = context.getBean(DataSource.class);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createTables(jdbc);
        seedAccounts(jdbc);
        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-launch")
            .tenantId(Long.toString(TENANT))
            .addString("oa-launch.bpmn20.xml", bpmn)
            .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(PROCESS_KEY)
            .singleResult();
        seedRelease(jdbc, bpmn, definition.getId());

        OaLaunchRepository repository = new OaLaunchRepository(jdbc);
        OaEngineTemplate engineTemplate = new OaEngineTemplate(context.getBean(RuntimeService.class), context
            .getBean(TaskService.class));
        OaProcessLauncher launcher = new OaProcessLauncher(engineTemplate, new OaAssigneeResolver(new StubStaffDirectory()));
        OaApprovalEngine approvalEngine = new OaApprovalEngine(context.getBean(RuntimeService.class), context
            .getBean(TaskService.class), context.getBean(HistoryService.class), repositoryService);
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), approvalEngine);
        OaLaunchService launchService = new OaLaunchService(repository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, new ObjectMapper(), context
                .getBean(PlatformTransactionManager.class));
        OaApplicationService applicationService = new OaApplicationService(repository);
        return new Fixture(jdbc, launchService, applicationService, attachmentService, context
            .getBean(RuntimeService.class), context.getBean(TaskService.class));
    }

    private static void seedAccounts(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (10, 101, '研发部', 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (20, 101, '市场部', 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (101, 101, 'alice', 'Alice', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (102, 101, 'bob', 'Bob', 10, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (103, 101, 'carol', 'Carol', 20, 1, 0)");
        jdbc.update("INSERT INTO sys_role (id, tenant_id, code, deleted) VALUES (1, 101, 'employee', 0)");
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (1, 101, 101, 1)");
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (2, 101, 103, 1)");
    }

    private static void seedRelease(JdbcTemplate jdbc, String bpmn, String engineDefinitionId) {
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (1, 101, 'leave', '请假流程', 'hr', '说明', 'PUBLISHED', 10, 1, 20, 30, 0, 7, CURRENT_TIMESTAMP, 0)
            """);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (10, 101, 1, 1, ?, ?, ?, 'oaLaunch', 1, 20, 30, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, bpmn, "a"
            .repeat(64), engineDefinitionId);
        jdbc.update("""
            INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted)
            VALUES (20, 101, 'leave_form', '请假单', 'PUBLISHED', 0)
            """);
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (30, 101, 20, 1, ?, ?, 2, 7, CURRENT_TIMESTAMP)
            """, SCHEMA, "b".repeat(64));
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(64) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_user_role (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL)");
        jdbc.execute("""
            CREATE TABLE oa_process_model (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, model_key VARCHAR(64) NOT NULL,
              name VARCHAR(100) NOT NULL, category VARCHAR(64), description VARCHAR(500), status VARCHAR(20) NOT NULL,
              current_release_id BIGINT, current_release_version INT NOT NULL DEFAULT 0, form_id BIGINT,
              form_version_id BIGINT, version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL,
              create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_process_release (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, model_id BIGINT NOT NULL, release_version INT NOT NULL,
              bpmn_xml CLOB NOT NULL, xml_digest VARCHAR(64) NOT NULL, engine_definition_id VARCHAR(128) NOT NULL,
              process_definition_key VARCHAR(128) NOT NULL, process_definition_version INT NOT NULL, form_id BIGINT,
              form_version_id BIGINT, form_version INT, launch_scope VARCHAR(2000), status VARCHAR(20) NOT NULL,
              published_by BIGINT NOT NULL, publish_time TIMESTAMP NOT NULL, remark VARCHAR(500)
            )
            """);
        jdbc.execute("CREATE TABLE oa_form (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, form_key VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE oa_form_version (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, form_id BIGINT NOT NULL, version INT NOT NULL,
              schema_json CLOB NOT NULL, schema_digest VARCHAR(64) NOT NULL, field_count INT NOT NULL DEFAULT 0,
              published_by BIGINT NOT NULL, publish_time TIMESTAMP NOT NULL
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_application (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              business_type VARCHAR(64) NOT NULL, business_key VARCHAR(128) NOT NULL, title VARCHAR(200) NOT NULL,
              applicant_id BIGINT NOT NULL, applicant_name VARCHAR(64), applicant_dept_id BIGINT,
              applicant_dept_name VARCHAR(100), release_id BIGINT NOT NULL, form_version_id BIGINT,
              form_version INT, form_data CLOB, process_instance_id VARCHAR(64), status VARCHAR(20) NOT NULL,
              submitted_time TIMESTAMP, finished_time TIMESTAMP, version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_request_dedup (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              actor_id BIGINT NOT NULL, command VARCHAR(64) NOT NULL, request_id VARCHAR(64) NOT NULL,
              status VARCHAR(20) NOT NULL, result_json CLOB, create_time TIMESTAMP NOT NULL,
              expire_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_dedup UNIQUE (tenant_id, actor_id, command, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_approval_audit (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, process_instance_id VARCHAR(64) NOT NULL, task_id VARCHAR(64),
              action VARCHAR(32) NOT NULL, operator_id BIGINT NOT NULL, operator_name VARCHAR(64),
              original_assignee_id BIGINT, reason VARCHAR(500), request_id VARCHAR(64) NOT NULL,
              detail_json CLOB, create_time TIMESTAMP NOT NULL
            )
            """);
        jdbc.execute("CREATE TABLE sys_storage (id BIGINT PRIMARY KEY, code VARCHAR(30) NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE sys_file (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(255) NOT NULL,
              original_name VARCHAR(255) NOT NULL, size BIGINT, parent_path VARCHAR(512) NOT NULL DEFAULT '/',
              path VARCHAR(512) NOT NULL, extension VARCHAR(32), content_type VARCHAR(255),
              type INT NOT NULL DEFAULT 1, storage_id BIGINT NOT NULL, create_user BIGINT NOT NULL,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_attachment_link (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT, business_type VARCHAR(64), business_key VARCHAR(128),
              file_id BIGINT NOT NULL, file_name VARCHAR(255), file_size BIGINT, content_type VARCHAR(100),
              category VARCHAR(32) DEFAULT 'FORM', scan_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
              available TINYINT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP, deleted BIGINT NOT NULL DEFAULT 0,
              CONSTRAINT uk_attachment_file UNIQUE (tenant_id, file_id, deleted)
            )
            """);
    }

    /** 员工目录桩：101/102/103 在职，研发部负责人与直属主管均为 102。 */
    private static final class StubStaffDirectory implements OaStaffDirectory {

        @Override
        public boolean activeEmployee(long tenantId, long userId) {
            return tenantId == TENANT && (userId == 101 || userId == 102 || userId == 103);
        }

        @Override
        public Long departmentId(long tenantId, long userId) {
            return userId == 103 ? 20L : 10L;
        }

        @Override
        public Long supervisorId(long tenantId, long userId) {
            return userId == 101 ? 102L : null;
        }

        @Override
        public boolean enabledPosition(long tenantId, String code) {
            return "FINANCE".equals(code);
        }

        @Override
        public List<Long> activeUsersByPosition(long tenantId, String code) {
            return "FINANCE".equals(code) ? List.of(102L, 103L) : List.of();
        }

        @Override
        public List<Long> departmentLeaders(long tenantId, long departmentId) {
            return List.of(102L);
        }
    }

    private record Fixture(JdbcTemplate jdbc, OaLaunchService launchService, OaApplicationService applicationService,
                           OaAttachmentService attachmentService, RuntimeService runtimeService,
                           TaskService taskService) {
    }
}
