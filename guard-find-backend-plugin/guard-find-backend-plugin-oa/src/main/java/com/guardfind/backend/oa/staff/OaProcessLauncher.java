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

package com.guardfind.backend.oa.staff;

import com.guardfind.backend.oa.engine.OaEngineTemplate;
import org.flowable.engine.runtime.ProcessInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发起流程前的审批人守卫：先解析再启动，任何候选为空或失效都在写引擎前失败。
 *
 * <p>
 * 首版按发起时已知信息解析流程变量；后续节点若依赖届时数据，需在任务创建监听中复用
 * {@link OaAssigneeResolver}，不能退回默认同意。
 * </p>
 */
public class OaProcessLauncher {

    private final OaEngineTemplate engineTemplate;
    private final OaAssigneeResolver assigneeResolver;

    public OaProcessLauncher(OaEngineTemplate engineTemplate, OaAssigneeResolver assigneeResolver) {
        this.engineTemplate = engineTemplate;
        this.assigneeResolver = assigneeResolver;
    }

    public ProcessInstance start(String processDefinitionKey,
                                 long tenantId,
                                 String businessKey,
                                 long applicantId,
                                 Map<String, Object> variables,
                                 Map<String, OaAssigneeSpec> candidateVariables) {
        return start(processDefinitionKey, tenantId, businessKey, applicantId, variables, candidateVariables, Set.of());
    }

    public ProcessInstance start(String processDefinitionKey,
                                 long tenantId,
                                 String businessKey,
                                 long applicantId,
                                 Map<String, Object> variables,
                                 Map<String, OaAssigneeSpec> candidateVariables,
                                 Set<String> singleAssigneeVariables) {
        Map<String, Object> processVariables = variables == null ? new HashMap<>() : new HashMap<>(variables);
        OaAssigneeContext context = new OaAssigneeContext(tenantId, applicantId, formData(processVariables));
        assigneeResolver.requireActiveApplicant(context);
        if (candidateVariables != null) {
            candidateVariables.forEach((variableName, spec) -> {
                if (variableName == null || variableName.isBlank()) {
                    throw new OaAssigneeUnavailableException("审批人变量名不能为空");
                }
                List<Long> candidates;
                try {
                    candidates = assigneeResolver.resolve(spec, context);
                } catch (OaAssigneeUnavailableException e) {
                    throw new OaAssigneeUnavailableException("审批人变量 %s 解析失败: %s".formatted(variableName, e
                        .getMessage()), e);
                }
                if (singleAssigneeVariables != null && singleAssigneeVariables.contains(variableName) && candidates
                    .size() != 1) {
                    throw new OaAssigneeUnavailableException("审批人变量 %s 用于 assignee，只能解析出一名员工，实际 %d 名"
                        .formatted(variableName, candidates.size()));
                }
                processVariables.put(variableName, String.join(",", candidates.stream().map(String::valueOf).toList()));
            });
        }
        return engineTemplate.start(processDefinitionKey, Long.toString(tenantId), businessKey, processVariables);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formData(Map<String, Object> processVariables) {
        Object raw = processVariables.get(OaAssigneeContext.FORM_DATA_VARIABLE);
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>)map;
        }
        return processVariables;
    }
}
