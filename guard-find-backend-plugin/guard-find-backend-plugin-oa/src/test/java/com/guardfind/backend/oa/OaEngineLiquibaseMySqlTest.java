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
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用真实 MySQL 验证 Liquibase 托管的 OA schema：Flowable 7.2.0 引擎表在
 * database-schema-update=false 下可启动，流程模型/表单版本表的租户唯一约束生效。
 *
 * 需要显式提供 OA_MYSQL_URL（可带 createDatabaseIfNotExist=true），避免误连开发库。
 */
@EnabledIfEnvironmentVariable(named = "OA_MYSQL_URL", matches = ".+")
class OaEngineLiquibaseMySqlTest {

    private static final long TEST_TENANT_A = 9101L;
    private static final long TEST_TENANT_B = 9102L;

    @Test
    void liquibaseManagedSchemaStartsEngine() throws Exception {
        migrate();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, ProcessEngineAutoConfiguration.class, ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url(), "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "spring.datasource.username=" + user(), "spring.datasource.password=" + password(), "flowable.process.enabled=true", "flowable.database-schema-update=false", "flowable.async-executor-activate=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ProcessEngine.class);
                RepositoryService repositoryService = context.getBean(RepositoryService.class);
                repositoryService.createDeployment()
                    .tenantId("tenant-mysql")
                    .addString("oa-approval.bpmn20.xml", OaEngineIntegrationTest.BPMN)
                    .deploy();
                OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
                template.start("oaApproval", "tenant-mysql", "biz-mysql", Map.of("assignee", "alice"));
                String taskId = template.listCandidateOrAssigned("tenant-mysql", "alice").get(0).getId();
                template.complete(taskId, "tenant-mysql", Map.of("approved", true));
                assertThat(template.countRunning("tenant-mysql")).isZero();
            });
    }

    @Test
    void businessSchemaEnforcesTenantScopedVersionUniqueness() throws Exception {
        migrate();
        try (Connection connection = DriverManager.getConnection(url(), user(), password())) {
            assertBusinessSchema(connection);
            clearTestData(connection);
            execute(connection, model(TEST_TENANT_A, "oa-test-leave"));
            assertThatThrownBy(() -> execute(connection, model(TEST_TENANT_A, "oa-test-leave")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, model(TEST_TENANT_B, "oa-test-leave"));

            execute(connection, form(TEST_TENANT_A, "oa-test-form"));
            assertThatThrownBy(() -> execute(connection, form(TEST_TENANT_A, "oa-test-form")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, form(TEST_TENANT_B, "oa-test-form"));

            execute(connection, formVersion(TEST_TENANT_A, 9901L, 1));
            assertThatThrownBy(() -> execute(connection, formVersion(TEST_TENANT_A, 9901L, 1)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, formVersion(TEST_TENANT_B, 9901L, 1));

            execute(connection, release(TEST_TENANT_A, 9901L, 1, "oa-test:1:9901"));
            assertThatThrownBy(() -> execute(connection, release(TEST_TENANT_B, 9902L, 1, "oa-test:1:9901")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");

            execute(connection, application(TEST_TENANT_A, "oa-test-1", "oa-test-instance-1"));
            assertThatThrownBy(() -> execute(connection, application(TEST_TENANT_A, "oa-test-1", "oa-test-instance-2")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, application(TEST_TENANT_B, "oa-test-1", "oa-test-instance-3"));
            assertThatThrownBy(() -> execute(connection, application(TEST_TENANT_A, "oa-test-2", "oa-test-instance-1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");

            execute(connection, audit(TEST_TENANT_A, 9901L, "oa-test-request-1"));
            assertThatThrownBy(() -> execute(connection, audit(TEST_TENANT_A, 9901L, "oa-test-request-1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, audit(TEST_TENANT_B, 9901L, "oa-test-request-1"));

            execute(connection, ccReceipt(TEST_TENANT_A, 9901L, 1L));
            assertThatThrownBy(() -> execute(connection, ccReceipt(TEST_TENANT_A, 9901L, 1L)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, ccReceipt(TEST_TENANT_B, 9901L, 1L));

            execute(connection, attachment(TEST_TENANT_A, 9901L, 8801L));
            assertThatThrownBy(() -> execute(connection, attachment(TEST_TENANT_A, 9901L, 8801L)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, attachment(TEST_TENANT_B, 9902L, 8801L));

            execute(connection, outbox(TEST_TENANT_A, "oa-test-event-1"));
            assertThatThrownBy(() -> execute(connection, outbox(TEST_TENANT_B, "oa-test-event-1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");

            execute(connection, dedup(TEST_TENANT_A, 1L, "APPROVE", "oa-test-dedup-1"));
            assertThatThrownBy(() -> execute(connection, dedup(TEST_TENANT_A, 1L, "APPROVE", "oa-test-dedup-1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate entry");
            execute(connection, dedup(TEST_TENANT_A, 1L, "SUBMIT", "oa-test-dedup-1"));
            execute(connection, dedup(TEST_TENANT_B, 1L, "APPROVE", "oa-test-dedup-1"));
        }
    }

    private static void migrate() throws Exception {
        try (Connection connection = DriverManager.getConnection(url(), user(), password())) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase("db/changelog/oa-verify.yaml", new ClassLoaderResourceAccessor(), database)
                .update(new Contexts(), new LabelExpression());
        }
    }

    private static void assertBusinessSchema(Connection connection) throws SQLException {
        assertThat(columns(connection, "oa_process_model"))
            .contains("tenant_id", "model_key", "status", "bpmn_xml", "current_release_id", "form_version_id", "version", "deleted");
        assertThat(columns(connection, "oa_process_release"))
            .contains("engine_definition_id", "process_definition_key", "process_definition_version", "form_version_id", "launch_scope", "status", "published_by", "publish_time");
        assertThat(columns(connection, "oa_form")).contains("form_key", "current_version_id", "version");
        assertThat(columns(connection, "oa_form_version")).contains("schema_json", "schema_digest", "field_count");
        assertThat(indexes(connection, "oa_process_model")).contains("uk_tenant_model_key", "idx_tenant_status");
        assertThat(indexes(connection, "oa_process_release"))
            .contains("uk_tenant_model_version", "uk_engine_definition", "idx_tenant_key_status");
        assertThat(indexes(connection, "oa_form")).contains("uk_tenant_form_key");
        assertThat(indexes(connection, "oa_form_version")).contains("uk_tenant_form_version");
        assertThat(columns(connection, "oa_application"))
            .contains("tenant_id", "business_type", "business_key", "applicant_id", "applicant_dept_id", "release_id", "form_version_id", "form_data", "process_instance_id", "status", "version", "deleted");
        assertThat(indexes(connection, "oa_application"))
            .contains("uk_tenant_business", "uk_process_instance", "idx_tenant_applicant", "idx_tenant_status_time");
        assertThat(columns(connection, "oa_approval_audit"))
            .contains("application_id", "process_instance_id", "task_id", "action", "operator_id", "original_assignee_id", "request_id", "detail_json", "create_time");
        assertThat(indexes(connection, "oa_approval_audit"))
            .contains("uk_tenant_request", "idx_tenant_application_time", "idx_tenant_operator");
        assertThat(columns(connection, "oa_cc_receipt"))
            .contains("application_id", "receiver_id", "source_task_id", "status", "read_time");
        assertThat(indexes(connection, "oa_cc_receipt"))
            .contains("uk_tenant_application_receiver", "idx_tenant_receiver_status");
        assertThat(columns(connection, "oa_attachment_link"))
            .contains("tenant_id", "application_id", "business_type", "business_key", "file_id", "file_name", "file_size", "content_type", "category", "scan_status", "available", "create_user", "deleted");
        assertThat(indexes(connection, "oa_attachment_link"))
            .contains("uk_tenant_file", "idx_tenant_application", "idx_tenant_business", "idx_tenant_scan");
        assertThat(columns(connection, "oa_outbox"))
            .contains("tenant_id", "event_id", "aggregate_type", "aggregate_id", "event_type", "payload", "status", "retry_count", "next_retry_time", "last_error", "sent_time");
        assertThat(indexes(connection, "oa_outbox"))
            .contains("uk_event_id", "idx_status_retry", "idx_tenant_aggregate");
        assertThat(columns(connection, "oa_request_dedup"))
            .contains("tenant_id", "actor_id", "command", "request_id", "status", "result_json", "expire_time");
        assertThat(indexes(connection, "oa_request_dedup"))
            .contains("uk_tenant_actor_command_request", "idx_expire_time");
    }

    private static void clearTestData(Connection connection) throws SQLException {
        for (String table : new String[] {"oa_request_dedup", "oa_outbox", "oa_attachment_link", "oa_cc_receipt",
            "oa_approval_audit", "oa_application", "oa_process_release", "oa_form_version", "oa_form",
            "oa_process_model"}) {
            execute(connection, "DELETE FROM " + table + " WHERE tenant_id IN (" + TEST_TENANT_A + "," + TEST_TENANT_B + ")");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static Set<String> indexes(Connection connection, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet indexes = connection.getMetaData()
            .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static String model(long tenantId, String key) {
        return "INSERT INTO oa_process_model (tenant_id, model_key, name, status, version, create_user, create_time, deleted) VALUES (%d, '%s', '测试模型', 'DRAFT', 0, 1, NOW(), 0)"
            .formatted(tenantId, key);
    }

    private static String form(long tenantId, String key) {
        return "INSERT INTO oa_form (tenant_id, form_key, name, status, version, create_user, create_time, deleted) VALUES (%d, '%s', '测试表单', 'DRAFT', 0, 1, NOW(), 0)"
            .formatted(tenantId, key);
    }

    private static String formVersion(long tenantId, long formId, int version) {
        return "INSERT INTO oa_form_version (tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time) VALUES (%d, %d, %d, '{\"fields\":[]}', 'digest', 0, 1, NOW())"
            .formatted(tenantId, formId, version);
    }

    private static String release(long tenantId, long modelId, int releaseVersion, String definitionId) {
        return "INSERT INTO oa_process_release (tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id, process_definition_key, process_definition_version, form_version_id, form_version, status, published_by, publish_time) VALUES (%d, %d, %d, '<definitions/>', 'digest', '%s', 'oaApproval', 1, 9901, 1, 'ACTIVE', 1, NOW())"
            .formatted(tenantId, modelId, releaseVersion, definitionId);
    }

    private static String application(long tenantId, String businessKey, String instanceId) {
        return "INSERT INTO oa_application (tenant_id, business_type, business_key, title, applicant_id, release_id, form_version_id, form_version, process_instance_id, status, version, create_user, create_time, deleted) VALUES (%d, 'LEAVE', '%s', '测试申请', 1, 9901, 9901, 1, '%s', 'RUNNING', 0, 1, NOW(), 0)"
            .formatted(tenantId, businessKey, instanceId);
    }

    private static String audit(long tenantId, long applicationId, String requestId) {
        return "INSERT INTO oa_approval_audit (tenant_id, application_id, process_instance_id, task_id, action, operator_id, request_id, create_time) VALUES (%d, %d, 'oa-test-instance-%d', 'task-1', 'APPROVE', 1, '%s', NOW())"
            .formatted(tenantId, applicationId, tenantId, requestId);
    }

    private static String ccReceipt(long tenantId, long applicationId, long receiverId) {
        return "INSERT INTO oa_cc_receipt (tenant_id, application_id, receiver_id, status, create_time) VALUES (%d, %d, %d, 'UNREAD', NOW())"
            .formatted(tenantId, applicationId, receiverId);
    }

    private static String attachment(long tenantId, long applicationId, long fileId) {
        return "INSERT INTO oa_attachment_link (tenant_id, application_id, business_type, business_key, file_id, file_name, scan_status, available, create_user, create_time, deleted) VALUES (%d, %d, 'LEAVE', 'oa-test-1', %d, 'test.pdf', 'CLEAN', 1, 1, NOW(), 0)"
            .formatted(tenantId, applicationId, fileId);
    }

    private static String outbox(long tenantId, String eventId) {
        return "INSERT INTO oa_outbox (tenant_id, event_id, aggregate_type, aggregate_id, event_type, payload, status, retry_count, create_time) VALUES (%d, '%s', 'APPLICATION', '9901', 'APPLICATION_SUBMITTED', '{}', 'PENDING', 0, NOW())"
            .formatted(tenantId, eventId);
    }

    private static String dedup(long tenantId, long actorId, String command, String requestId) {
        return "INSERT INTO oa_request_dedup (tenant_id, actor_id, command, request_id, status, create_time, expire_time) VALUES (%d, %d, '%s', '%s', 'PROCESSING', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY))"
            .formatted(tenantId, actorId, command, requestId);
    }

    private static String url() {
        return System.getenv("OA_MYSQL_URL");
    }

    private static String user() {
        return System.getenv().getOrDefault("OA_MYSQL_USER", "root");
    }

    private static String password() {
        return System.getenv().getOrDefault("OA_MYSQL_PASSWORD", "");
    }
}
