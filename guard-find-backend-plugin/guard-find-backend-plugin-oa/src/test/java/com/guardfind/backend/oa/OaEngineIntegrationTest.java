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
import org.flowable.app.engine.AppEngine;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration;
import org.flowable.spring.boot.app.AppEngineAutoConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.2 生产接入验证：引擎只建流程表、租户显式隔离、事务回滚不落流程实例、
 * 关闭自动建表后重启仍可读取并办结既有任务。
 */
class OaEngineIntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://guardfind/backend/oa">
          <process id="oaApproval" name="OA Approval" isExecutable="true">
            <startEvent id="start"/>
            <sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
            <userTask id="approve" name="审批" flowable:assignee="${assignee}"/>
            <sequenceFlow id="flow2" sourceRef="approve" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    private static final String BPMN_TIMER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     targetNamespace="http://guardfind/backend/oa">
          <process id="oaTimer" name="OA Timer" isExecutable="true">
            <startEvent id="start"/>
            <sequenceFlow id="flow1" sourceRef="start" targetRef="wait"/>
            <intermediateCatchEvent id="wait">
              <timerEventDefinition>
                <timeDuration>PT10M</timeDuration>
              </timerEventDefinition>
            </intermediateCatchEvent>
            <sequenceFlow id="flow2" sourceRef="wait" targetRef="approve"/>
            <userTask id="approve" name="审批" flowable:assignee="${assignee}"/>
            <sequenceFlow id="flow3" sourceRef="approve" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    private static final String[] TENANTS = {"tenant-a", "tenant-b", "tenant-restart"};

    @Configuration(proxyBeanMethods = false)
    static class DeploymentConfiguration {

        @Bean
        InitializingBean oaTestDeployments(RepositoryService repositoryService) {
            return () -> {
                for (String tenant : TENANTS) {
                    repositoryService.createDeployment()
                        .name("oa-approval-" + tenant)
                        .tenantId(tenant)
                        .addString("oa-approval.bpmn20.xml", BPMN)
                        .deploy();
                }
                repositoryService.createDeployment()
                    .name("oa-timer")
                    .tenantId("tenant-timer")
                    .addString("oa-timer.bpmn20.xml", BPMN_TIMER)
                    .deploy();
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LiquibaseStandInConfiguration {

        @Bean(name = "liquibase")
        Object liquibaseStandIn() {
            return new Object();
        }
    }

    private ApplicationContextRunner runner(String url, String schemaUpdate) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, ProcessEngineAutoConfiguration.class, ProcessEngineServicesAutoConfiguration.class, AppEngineAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class, DeploymentConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=" + schemaUpdate, "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private ApplicationContextRunner h2Runner() {
        return runner("jdbc:h2:mem:oa-engine-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "true");
    }

    @Test
    void startsProcessEngineWithoutStartingOtherEngines() {
        h2Runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProcessEngine.class);
            assertThat(context).hasSingleBean(OaEngineTemplate.class);
            assertThat(context.getBeansOfType(AppEngine.class)).isEmpty();
            assertThat(context.getBean(RepositoryService.class)
                .createProcessDefinitionQuery()
                .processDefinitionKey("oaApproval")
                .count()).isEqualTo(TENANTS.length);
            assertThat(hasTable(context.getBean(DataSource.class), "ACT_ID_%")).isFalse();
        });
    }

    @Test
    void isolatesTenantsAndRejectsCrossTenantCompletion() {
        h2Runner().run(context -> {
            OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
            ProcessInstance instanceA = template.start("oaApproval", "tenant-a", "biz-a", Map.of("assignee", "alice"));
            template.start("oaApproval", "tenant-b", "biz-b", Map.of("assignee", "alice"));
            Task taskA = template.listCandidateOrAssigned("tenant-a", "alice").get(0);
            assertThat(template.listCandidateOrAssigned("tenant-b", "alice")).hasSize(1);
            assertThatThrownBy(() -> template.complete(taskA.getId(), "tenant-b", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户");
            assertThat(template.countRunning("tenant-a")).isEqualTo(1);
            template.complete(taskA.getId(), "tenant-a", Map.of("approved", true));
            assertThat(template.countRunning("tenant-a")).isZero();
            assertThat(template.countRunning("tenant-b")).isEqualTo(1);
            HistoryService historyService = context.getBean(HistoryService.class);
            assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instanceA.getId())
                .variableName("approved")
                .count()).isEqualTo(1);
        });
    }

    @Test
    void claimIsTenantScopedAndIdempotent() {
        h2Runner().run(context -> {
            OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
            template.start("oaApproval", "tenant-a", "biz-claim-a", Map.of("assignee", "alice"));
            template.start("oaApproval", "tenant-b", "biz-claim-b", Map.of("assignee", "alice"));
            Task taskA = template.listCandidateOrAssigned("tenant-a", "alice").get(0);
            assertThatThrownBy(() -> template.claim(taskA.getId(), "tenant-b", "alice"))
                .isInstanceOf(IllegalStateException.class);
            template.claim(taskA.getId(), "tenant-a", "alice");
            assertThat(template.task(taskA.getId(), "tenant-a").getAssignee()).isEqualTo("alice");
        });
    }

    @Test
    void rollbackLeavesNoProcessInstance() {
        h2Runner().run(context -> {
            OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
            TransactionTemplate transaction = new TransactionTemplate(context
                .getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                template.start("oaApproval", "tenant-a", "biz-rollback", Map.of("assignee", "alice"));
                throw new IllegalStateException("强制回滚");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(template.countRunning("tenant-a")).isZero();
            assertThat(context.getBean(org.flowable.engine.TaskService.class)
                .createTaskQuery()
                .taskTenantId("tenant-a")
                .count()).isZero();
        });
    }

    @Test
    void keepsTasksAcrossRestartWhenSchemaUpdateDisabled(@TempDir Path tempDir) {
        String file = tempDir.resolve("oa-engine").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + file + ";DB_CLOSE_ON_EXIT=FALSE";
        AtomicReference<String> taskId = new AtomicReference<>();
        runner(url, "true").run(context -> {
            OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
            template.start("oaApproval", "tenant-a", "biz-restart", Map.of("assignee", "alice"));
            taskId.set(template.listCandidateOrAssigned("tenant-a", "alice").get(0).getId());
        });
        runner(url, "false").run(context -> {
            assertThat(context).hasNotFailed();
            OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
            assertThat(template.task(taskId.get(), "tenant-a")).isNotNull();
            template.complete(taskId.get(), "tenant-a", Map.of("approved", true));
            assertThat(template.countRunning("tenant-a")).isZero();
        });
    }

    @Test
    void recoversTimerJobAfterRestart(@TempDir Path tempDir) {
        String file = tempDir.resolve("oa-timer").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + file + ";DB_CLOSE_ON_EXIT=FALSE";
        AtomicReference<String> instanceId = new AtomicReference<>();
        runner(url, "true").run(context -> {
            instanceId.set(context.getBean(OaEngineTemplate.class)
                .start("oaTimer", "tenant-timer", "biz-timer", Map.of("assignee", "alice"))
                .getId());
            assertThat(context.getBean(ManagementService.class)
                .createTimerJobQuery()
                .processInstanceId(instanceId.get())
                .count()).isEqualTo(1);
        });
        runner(url, "false").run(context -> {
            assertThat(context).hasNotFailed();
            ManagementService managementService = context.getBean(ManagementService.class);
            Job timerJob = managementService.createTimerJobQuery().processInstanceId(instanceId.get()).singleResult();
            assertThat(timerJob).as("重启后定时器任务应仍在库中").isNotNull();
            Job executableJob = managementService.moveTimerToExecutableJob(timerJob.getId());
            managementService.executeJob(executableJob.getId());
            OaEngineTemplate template = context.getBean(OaEngineTemplate.class);
            assertThat(template.listCandidateOrAssigned("tenant-timer", "alice")).hasSize(1);
            template.complete(template.listCandidateOrAssigned("tenant-timer", "alice")
                .get(0)
                .getId(), "tenant-timer", Map.of("approved", true));
            assertThat(template.countRunning("tenant-timer")).isZero();
        });
    }

    @Test
    void processEngineWaitsForLiquibaseWhenBeanPresent() {
        h2Runner().withUserConfiguration(LiquibaseStandInConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            ConfigurableApplicationContext applicationContext = (ConfigurableApplicationContext)context
                .getSourceApplicationContext();
            BeanDefinition definition = applicationContext.getBeanFactory().getBeanDefinition("processEngine");
            assertThat(definition.getDependsOn()).contains("liquibase");
        });
    }

    private static boolean hasTable(DataSource dataSource, String pattern) throws Exception {
        try (Connection connection = dataSource.getConnection(); ResultSet tables = connection.getMetaData()
            .getTables(null, null, pattern, new String[] {"TABLE"})) {
            return tables.next();
        }
    }
}
