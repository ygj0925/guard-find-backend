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
    }

    private static void clearTestData(Connection connection) throws SQLException {
        for (String table : new String[] {"oa_process_release", "oa_form_version", "oa_form", "oa_process_model"}) {
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
