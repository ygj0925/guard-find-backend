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

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;

import java.util.List;
import java.util.Map;

/**
 * 租户显式的引擎入口：调用方必须传 tenantId，避免依赖全局拦截器而漏掉引擎表。
 */
public class OaEngineTemplate {

    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public OaEngineTemplate(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    public ProcessInstance start(String processDefinitionKey,
                                 String tenantId,
                                 String businessKey,
                                 Map<String, Object> variables) {
        requireText(processDefinitionKey, "流程定义 Key 不能为空");
        requireText(tenantId, "租户不能为空");
        return runtimeService
            .startProcessInstanceByKeyAndTenantId(processDefinitionKey, businessKey, variables, tenantId);
    }

    public Task task(String taskId, String tenantId) {
        requireText(taskId, "任务 ID 不能为空");
        requireText(tenantId, "租户不能为空");
        return taskService.createTaskQuery().taskId(taskId).taskTenantId(tenantId).singleResult();
    }

    public void complete(String taskId, String tenantId, Map<String, Object> variables) {
        taskService.complete(requireTask(taskId, tenantId).getId(), variables);
    }

    public void claim(String taskId, String tenantId, String userId) {
        requireText(userId, "用户不能为空");
        Task task = requireTask(taskId, tenantId);
        if (task.getAssignee() != null) {
            if (task.getAssignee().equals(userId)) {
                return;
            }
            throw new IllegalStateException("任务已被其他审批人领取");
        }
        taskService.claim(task.getId(), userId);
    }

    public List<Task> listCandidateOrAssigned(String tenantId, String userId) {
        requireText(tenantId, "租户不能为空");
        requireText(userId, "用户不能为空");
        return taskService.createTaskQuery()
            .taskTenantId(tenantId)
            .taskCandidateOrAssigned(userId)
            .orderByTaskCreateTime()
            .desc()
            .list();
    }

    public long countRunning(String tenantId) {
        requireText(tenantId, "租户不能为空");
        return runtimeService.createProcessInstanceQuery().processInstanceTenantId(tenantId).count();
    }

    private Task requireTask(String taskId, String tenantId) {
        Task task = task(taskId, tenantId);
        if (task == null) {
            throw new IllegalStateException("任务不存在或不属于当前租户");
        }
        return task;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
