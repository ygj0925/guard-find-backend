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

package com.guardfind.backend.oa.engine;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.InclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.Process;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.history.HistoricIdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;

import java.util.List;

/**
 * 审批动作的租户显式引擎入口。
 *
 * <p>
 * 所有任务查询都同时限定 tenantId 与当前处理人，避免拿到他人或他租户的任务；
 * 退回只允许单一活动任务回到最近完成的人工任务，存在并行/包容分支时直接拒绝。
 * </p>
 */
public class OaApprovalEngine {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;

    public OaApprovalEngine(RuntimeService runtimeService,
                            TaskService taskService,
                            HistoryService historyService,
                            RepositoryService repositoryService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
    }

    public Task authorizedTask(String taskId, String tenantId, String userId) {
        requireText(taskId, "任务 ID 不能为空");
        requireText(tenantId, "租户不能为空");
        requireText(userId, "用户不能为空");
        return taskService.createTaskQuery()
            .taskId(taskId)
            .taskTenantId(tenantId)
            .taskCandidateOrAssigned(userId)
            .singleResult();
    }

    /**
     * 当前用户是否为流程实例的待处理人（含候选）。
     */
    public boolean hasTaskAccess(String processInstanceId, String tenantId, String userId) {
        if (processInstanceId == null || processInstanceId.isBlank() || tenantId == null || userId == null) {
            return false;
        }
        return taskService.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskTenantId(tenantId)
            .taskCandidateOrAssigned(userId)
            .count() > 0;
    }

    public List<Task> todoTasks(String tenantId, String userId, int limit) {
        requireText(tenantId, "租户不能为空");
        requireText(userId, "用户不能为空");
        return taskService.createTaskQuery()
            .taskTenantId(tenantId)
            .taskCandidateOrAssigned(userId)
            .orderByTaskCreateTime()
            .desc()
            .listPage(0, Math.max(1, limit));
    }

    public void claimIfUnassigned(Task task, String userId) {
        if (task.getAssignee() == null) {
            taskService.claim(task.getId(), userId);
            return;
        }
        if (!task.getAssignee().equals(userId)) {
            throw new IllegalStateException("任务已被其他审批人领取");
        }
    }

    /**
     * 候选领取：未领取时归当前用户；已被本人领取时幂等；被他人领取时拒绝。
     */
    public void claim(Task task, String userId) {
        if (task.getAssignee() != null && !task.getAssignee().equals(userId)) {
            throw new IllegalStateException("任务已被其他处理人领取");
        }
        if (task.getAssignee() == null) {
            taskService.claim(task.getId(), userId);
        }
    }

    /**
     * 转交任务：清理全部候选身份链接后写入唯一处理人，避免原候选人继续看到任务。
     */
    public void reassign(Task task, String targetUserId) {
        removeCandidateLinks(task);
        taskService.setAssignee(task.getId(), targetUserId);
    }

    public void removeCandidateLinks(Task task) {
        for (org.flowable.identitylink.api.IdentityLink link : taskService.getIdentityLinksForTask(task.getId())) {
            if (link.getUserId() != null) {
                taskService.deleteCandidateUser(task.getId(), link.getUserId());
            } else if (link.getGroupId() != null) {
                taskService.deleteCandidateGroup(task.getId(), link.getGroupId());
            }
        }
    }

    /**
     * 员工名下全部运行中任务（含候选），供离职转交使用。
     */
    public List<Task> runningTasksOfUser(String tenantId, String userId, int limit) {
        requireText(tenantId, "租户不能为空");
        requireText(userId, "用户不能为空");
        return taskService.createTaskQuery()
            .taskTenantId(tenantId)
            .taskCandidateOrAssigned(userId)
            .orderByTaskCreateTime()
            .asc()
            .listPage(0, Math.max(1, limit));
    }

    public void complete(String taskId) {
        taskService.complete(taskId);
    }

    public boolean instanceExists(String processInstanceId, String tenantId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return false;
        }
        return runtimeService.createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .processInstanceTenantId(tenantId)
            .count() > 0;
    }

    public List<Task> activeTasks(String processInstanceId, String tenantId) {
        return taskService.createTaskQuery()
            .processInstanceId(processInstanceId)
            .taskTenantId(tenantId)
            .orderByTaskCreateTime()
            .asc()
            .list();
    }

    public void deleteInstance(String processInstanceId, String tenantId, String reason) {
        if (!instanceExists(processInstanceId, tenantId)) {
            throw new IllegalStateException("流程实例不存在或已结束");
        }
        runtimeService.deleteProcessInstance(processInstanceId, reason);
    }

    /**
     * 把当前任务退回最近一个已完成的人工任务，返回新生成的任务 ID。
     */
    public String returnToPreviousUserTask(Task task, String tenantId) {
        List<Task> active = activeTasks(task.getProcessInstanceId(), tenantId);
        if (active.size() != 1) {
            throw new IllegalStateException("存在多个待处理任务，暂不支持退回");
        }
        String currentKey = task.getTaskDefinitionKey();
        HistoricTaskInstance previous = historyService.createHistoricTaskInstanceQuery()
            .processInstanceId(task.getProcessInstanceId())
            .taskTenantId(tenantId)
            .finished()
            .orderByHistoricTaskInstanceEndTime()
            .desc()
            .listPage(0, 1)
            .stream()
            .findFirst()
            .orElse(null);
        if (previous == null || previous.getTaskDefinitionKey() == null) {
            throw new IllegalStateException("没有可退回的上一人工节点");
        }
        requireNoParallelBranching(task.getProcessDefinitionId());

        runtimeService.createChangeActivityStateBuilder()
            .processInstanceId(task.getProcessInstanceId())
            .moveActivityIdTo(currentKey, previous.getTaskDefinitionKey())
            .changeState();

        List<Task> moved = activeTasks(task.getProcessInstanceId(), tenantId);
        if (moved.size() != 1) {
            throw new IllegalStateException("退回后未生成唯一待办任务");
        }
        Task target = moved.get(0);
        restoreAssignee(target, previous);
        return target.getId();
    }

    private void requireNoParallelBranching(String processDefinitionId) {
        BpmnModel model = processDefinitionId == null ? null : repositoryService.getBpmnModel(processDefinitionId);
        if (model == null) {
            throw new IllegalStateException("流程定义不存在，无法退回");
        }
        for (Process process : model.getProcesses()) {
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof ParallelGateway || element instanceof InclusiveGateway) {
                    throw new IllegalStateException("并行流程暂不支持退回");
                }
            }
        }
    }

    private void restoreAssignee(Task target, HistoricTaskInstance previous) {
        if (target.getAssignee() != null) {
            return;
        }
        if (previous.getAssignee() != null) {
            taskService.setAssignee(target.getId(), previous.getAssignee());
            return;
        }
        List<HistoricIdentityLink> links = historyService.getHistoricIdentityLinksForTask(previous.getId());
        for (HistoricIdentityLink link : links) {
            if (link.getUserId() != null) {
                taskService.addCandidateUser(target.getId(), link.getUserId());
            } else if (link.getGroupId() != null) {
                taskService.addCandidateGroup(target.getId(), link.getGroupId());
            }
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
