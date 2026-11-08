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

/**
 * 审批任务详情。pending 为 false 时表示任务已结束，仅允许查看历史。
 */
public record OaApprovalDetailResp(String taskId, String taskName, String taskDefinitionKey, String assignee,
                                   boolean pending, OaApplicationDetailResp application, Long proxyForId,
                                   String proxyForName) {

    public OaApprovalDetailResp(String taskId,
                                String taskName,
                                String taskDefinitionKey,
                                String assignee,
                                boolean pending,
                                OaApplicationDetailResp application) {
        this(taskId, taskName, taskDefinitionKey, assignee, pending, application, null, null);
    }
}
