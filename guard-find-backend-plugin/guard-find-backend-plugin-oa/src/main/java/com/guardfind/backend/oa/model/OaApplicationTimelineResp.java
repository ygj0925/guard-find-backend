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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 申请审批轨迹，来自不可变 oa_approval_audit。
 *
 * <p>
 * originalAssignee 为任务原处理人（代理时即委托人）；target 仅转交类动作有值。
 * </p>
 */
public record OaApplicationTimelineResp(String action, Long operatorId, String operatorName, String taskId,
                                        String reason, LocalDateTime createTime, Long originalAssigneeId,
                                        String originalAssigneeName, Long targetUserId, String targetUserName) {

    public OaApplicationTimelineResp withNames(Map<Long, String> names) {
        return new OaApplicationTimelineResp(action, operatorId, operatorName, taskId, reason, createTime, originalAssigneeId, originalAssigneeId == null
            ? null
            : names.get(originalAssigneeId), targetUserId, targetUserId == null ? null : names.get(targetUserId));
    }
}
