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

package com.guardfind.backend.oa.model;

import java.util.List;

/**
 * 发起详情：绑定发布版本的不可变表单 schema 与待绑定审批人变量。
 */
public record OaLaunchDetailResp(long releaseId, long modelId, String modelKey, String modelName, String category,
                                 String description, int releaseVersion, String processDefinitionKey, long formId,
                                 long formVersionId, int formVersion, String formName, String formKey,
                                 String schemaJson, String schemaDigest, String launchScope, boolean launchable,
                                 List<OaAssigneeVariableResp> assigneeVariables, String businessType) {

    public OaLaunchDetailResp {
        assigneeVariables = assigneeVariables == null ? List.of() : List.copyOf(assigneeVariables);
    }
}
