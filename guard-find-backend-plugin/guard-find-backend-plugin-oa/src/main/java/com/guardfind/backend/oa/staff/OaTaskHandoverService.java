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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApprovalApplication;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 离职/调岗待办转交：逐条写入 TRANSFER 审计并清空原候选链接，保留可追溯的原处理人。
 */
@Service
public class OaTaskHandoverService implements OaTaskHandover {

    private static final int MAX_TASKS = 2000;

    private final OaApprovalEngine engine;
    private final OaApprovalRepository approvalRepository;
    private final OaStaffDirectory staffDirectory;
    private final ObjectMapper objectMapper;

    public OaTaskHandoverService(OaApprovalEngine engine,
                                 OaApprovalRepository approvalRepository,
                                 OaStaffDirectory staffDirectory,
                                 ObjectMapper objectMapper) {
        this.engine = engine;
        this.approvalRepository = approvalRepository;
        this.staffDirectory = staffDirectory;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public int transferRunningTasks(long tenantId, long operatorId, long fromUserId, long targetUserId, String reason) {
        if (targetUserId <= 0 || targetUserId == fromUserId) {
            throw new IllegalArgumentException("目标处理人无效");
        }
        if (!staffDirectory.activeEmployee(tenantId, targetUserId)) {
            throw new IllegalArgumentException("目标处理人不是本租户在职员工");
        }
        var tasks = engine.runningTasksOfUser(Long.toString(tenantId), Long.toString(fromUserId), MAX_TASKS);
        String operatorName = name(tenantId, operatorId);
        int transferred = 0;
        for (Task task : tasks) {
            String originalAssignee = task.getAssignee();
            engine.reassign(task, Long.toString(targetUserId));
            ApprovalApplication application = approvalRepository.applicationByInstanceId(tenantId, task
                .getProcessInstanceId());
            if (application != null) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("fromUserId", fromUserId);
                detail.put("targetUserId", targetUserId);
                detail.put("taskName", task.getName());
                approvalRepository.insertAudit(tenantId, application.id(), task.getProcessInstanceId(), task
                    .getId(), "TRANSFER", operatorId, operatorName, originalAssignee == null
                        ? fromUserId
                        : parseLong(originalAssignee), reason, handoverRequestId(task), writeJson(detail), LocalDateTime
                            .now());
            }
            transferred++;
        }
        return transferred;
    }

    private static String handoverRequestId(Task task) {
        return "HANDOVER-" + task.getId() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String name(long tenantId, long userId) {
        String name = approvalRepository.userName(tenantId, userId);
        return name == null || name.isBlank() ? Long.toString(userId) : name;
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
