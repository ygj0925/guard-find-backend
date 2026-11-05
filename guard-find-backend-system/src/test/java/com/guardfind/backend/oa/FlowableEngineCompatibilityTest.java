package com.guardfind.backend.oa;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9.1 隔离兼容性验证：Flowable 7.2.0 在 JDK 21 + H2 上建表、部署、运行、计时器与重启恢复。
 *
 * 不启动 Spring，也不连接项目数据库；用于先证明引擎本身可用，
 * Spring Boot 自动配置的兼容性由 {@link FlowableSpringBootCompatibilityTest} 覆盖。
 */
class FlowableEngineCompatibilityTest {

    private static final String APPROVAL_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     targetNamespace="http://guardfind.com/oa">
          <process id="oaCompat" name="兼容性验证" isExecutable="true">
            <startEvent id="start"/>
            <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
            <userTask id="approve" name="主管审批"/>
            <sequenceFlow id="f2" sourceRef="approve" targetRef="decide"/>
            <exclusiveGateway id="decide"/>
            <sequenceFlow id="f3" sourceRef="decide" targetRef="endApproved">
              <conditionExpression xsi:type="tFormalExpression">${approved}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="f4" sourceRef="decide" targetRef="endRejected">
              <conditionExpression xsi:type="tFormalExpression">${!approved}</conditionExpression>
            </sequenceFlow>
            <endEvent id="endApproved"/>
            <endEvent id="endRejected"/>
          </process>
        </definitions>
        """;

    private static final String TIMER_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     targetNamespace="http://guardfind.com/oa">
          <process id="oaTimer" name="计时器验证" isExecutable="true">
            <startEvent id="start"/>
            <sequenceFlow id="t1" sourceRef="start" targetRef="wait"/>
            <intermediateCatchEvent id="wait">
              <timerEventDefinition>
                <timeDuration>PT1H</timeDuration>
              </timerEventDefinition>
            </intermediateCatchEvent>
            <sequenceFlow id="t2" sourceRef="wait" targetRef="review"/>
            <userTask id="review" name="超时后复核"/>
            <sequenceFlow id="t3" sourceRef="review" targetRef="end"/>
            <endEvent id="end"/>
          </process>
        </definitions>
        """;

    @Test
    void runsDeployStartCompleteAndHistoryOnH2() {
        ProcessEngine engine = buildEngine("jdbc:h2:mem:flowable-compat;DB_CLOSE_DELAY=-1");
        try {
            RepositoryService repositoryService = engine.getRepositoryService();
            RuntimeService runtimeService = engine.getRuntimeService();
            TaskService taskService = engine.getTaskService();
            HistoryService historyService = engine.getHistoryService();

            Deployment deployment = repositoryService.createDeployment()
                .addString("approval.bpmn20.xml", APPROVAL_XML)
                .deploy();
            assertThat(deployment.getId()).isNotBlank();

            ProcessInstance instance = runtimeService.startProcessInstanceByKey("oaCompat", Map.of("amount", 100));
            Task task = taskService.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            assertThat(task).isNotNull();
            assertThat(task.getTaskDefinitionKey()).isEqualTo("approve");

            taskService.complete(task.getId(), Map.of("approved", true));

            assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .count()).isZero();
            HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .singleResult();
            assertThat(historic.getEndTime()).isNotNull();
            assertThat(historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId())
                .activityId("endApproved")
                .count()).isEqualTo(1);
        } finally {
            engine.close();
        }
    }

    @Test
    void timerJobCanBeMovedAndExecutedWithoutAsyncExecutor() {
        ProcessEngine engine = buildEngine("jdbc:h2:mem:flowable-timer;DB_CLOSE_DELAY=-1");
        try {
            engine.getRepositoryService().createDeployment()
                .addString("timer.bpmn20.xml", TIMER_XML)
                .deploy();
            ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("oaTimer");

            List<Job> timers = engine.getManagementService().createTimerJobQuery()
                .processInstanceId(instance.getId())
                .list();
            assertThat(timers).hasSize(1);
            assertThat(engine.getTaskService().createTaskQuery()
                .processInstanceId(instance.getId())
                .count()).isZero();

            Job executable = engine.getManagementService().moveTimerToExecutableJob(timers.get(0).getId());
            engine.getManagementService().executeJob(executable.getId());

            Task review = engine.getTaskService().createTaskQuery()
                .processInstanceId(instance.getId())
                .singleResult();
            assertThat(review).isNotNull();
            assertThat(review.getTaskDefinitionKey()).isEqualTo("review");
        } finally {
            engine.close();
        }
    }

    @Test
    void runtimeStateSurvivesEngineRestart(@TempDir Path tempDir) {
        String jdbcUrl = "jdbc:h2:file:" + tempDir.toAbsolutePath().toString().replace('\\', '/')
            + "/flowable;DB_CLOSE_ON_EXIT=FALSE";
        String instanceId;
        String taskId;

        ProcessEngine first = buildEngine(jdbcUrl);
        try {
            first.getRepositoryService().createDeployment()
                .addString("approval.bpmn20.xml", APPROVAL_XML)
                .deploy();
            instanceId = first.getRuntimeService().startProcessInstanceByKey("oaCompat").getId();
            taskId = first.getTaskService().createTaskQuery()
                .processInstanceId(instanceId)
                .singleResult()
                .getId();
        } finally {
            first.close();
        }

        ProcessEngine second = buildEngine(jdbcUrl);
        try {
            assertThat(second.getRepositoryService().createDeploymentQuery().count()).isEqualTo(1);
            Task pending = second.getTaskService().createTaskQuery().processInstanceId(instanceId).singleResult();
            assertThat(pending).isNotNull();
            assertThat(pending.getId()).isEqualTo(taskId);

            second.getTaskService().complete(taskId, Map.of("approved", false));
            assertThat(second.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId)
                .singleResult()
                .getEndTime()).isNotNull();
        } finally {
            second.close();
        }
    }

    private static ProcessEngine buildEngine(String jdbcUrl) {
        return new StandaloneProcessEngineConfiguration()
            .setJdbcUrl(jdbcUrl)
            .setJdbcDriver("org.h2.Driver")
            .setJdbcUsername("sa")
            .setJdbcPassword("")
            .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
            .setAsyncExecutorActivate(false)
            .buildProcessEngine();
    }
}
