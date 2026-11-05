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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 MySQL 验证 Liquibase 托管的 Flowable 7.2.0 建表脚本：迁移完成后，
 * 在 database-schema-update=false 下引擎仍能启动、部署、启动实例并办结任务。
 *
 * 需要显式提供 OA_MYSQL_URL（可带 createDatabaseIfNotExist=true），避免误连开发库。
 */
@EnabledIfEnvironmentVariable(named = "OA_MYSQL_URL", matches = ".+")
class OaEngineLiquibaseMySqlTest {

    @Test
    void liquibaseManagedSchemaStartsEngine() throws Exception {
        String url = System.getenv("OA_MYSQL_URL");
        String user = System.getenv().getOrDefault("OA_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("OA_MYSQL_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase("db/changelog/oa-verify.yaml", new ClassLoaderResourceAccessor(), database)
                .update(new Contexts(), new LabelExpression());
        }
        new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, ProcessEngineAutoConfiguration.class, ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "spring.datasource.username=" + user, "spring.datasource.password=" + password, "flowable.process.enabled=true", "flowable.database-schema-update=false", "flowable.async-executor-activate=false")
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
}
