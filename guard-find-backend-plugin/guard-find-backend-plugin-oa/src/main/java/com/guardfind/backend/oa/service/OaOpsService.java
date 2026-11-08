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

package com.guardfind.backend.oa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.business.OaBusinessHooks;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.model.OaApprovalActionResp;
import com.guardfind.backend.oa.model.OaOpsTerminateReq;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApprovalApplication;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApplicationBrief;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository.DedupRecord;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.task.api.Task;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维干预：独立授权的流程终止、outbox 人工重试与立即投递。
 *
 * <p>
 * 终止不复用审批动作权限，必须填写原因并写不可变审计；管理员默认没有代签能力。
 * </p>
 */
@Service
public class OaOpsService {

    public static final String COMMAND_TERMINATE = "TERMINATE";
    private static final int DEDUP_TTL_HOURS = 24;

    private final OaApprovalRepository approvalRepository;
    private final OaLaunchRepository launchRepository;
    private final OaOutboxDispatcher dispatcher;
    private final OaOutboxService outboxService;
    private final OaApprovalEngine engine;
    private final OaBusinessHooks businessHooks;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaOpsService(OaApprovalRepository approvalRepository,
                        OaLaunchRepository launchRepository,
                        OaOutboxDispatcher dispatcher,
                        OaOutboxService outboxService,
                        OaApprovalEngine engine,
                        OaBusinessHooks businessHooks,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.launchRepository = launchRepository;
        this.dispatcher = dispatcher;
        this.outboxService = outboxService;
        this.engine = engine;
        this.businessHooks = businessHooks;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OaApprovalActionResp terminate(long tenantId, long actorId, long applicationId, OaOpsTerminateReq req) {
        if (req == null) {
            throw new BusinessException("终止参数不能为空");
        }
        String requestId = req.requestId() == null || req.requestId().isBlank() ? null : req.requestId().trim();
        if (requestId == null) {
            throw new BusinessException("请求 ID 不能为空");
        }
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if (reason == null) {
            throw new BusinessException("终止原因不能为空");
        }
        DedupRecord existing = launchRepository.dedup(tenantId, actorId, COMMAND_TERMINATE, requestId);
        if (existing != null) {
            return replay(existing);
        }
        try {
            return transactionTemplate
                .execute(status -> doTerminate(tenantId, actorId, applicationId, requestId, reason));
        } catch (DuplicateKeyException e) {
            DedupRecord concurrent = launchRepository.dedup(tenantId, actorId, COMMAND_TERMINATE, requestId);
            if (concurrent != null) {
                return replay(concurrent);
            }
            throw new BusinessException("请求处理冲突，请重试");
        }
    }

    private OaApprovalActionResp doTerminate(long tenantId,
                                             long actorId,
                                             long applicationId,
                                             String requestId,
                                             String reason) {
        LocalDateTime now = LocalDateTime.now();
        launchRepository.insertDedup(tenantId, actorId, COMMAND_TERMINATE, requestId, now, now
            .plusHours(DEDUP_TTL_HOURS));
        ApprovalApplication application = approvalRepository.lockApplication(tenantId, applicationId);
        if (application == null) {
            throw new BusinessException("申请不存在");
        }
        if (!"RUNNING".equals(application.status())) {
            throw new BusinessException("仅进行中的申请可以终止");
        }
        ApplicationBrief brief = approvalRepository.applicationBrief(tenantId, applicationId);
        List<Task> cancelled = engine.activeTasks(application.processInstanceId(), Long.toString(tenantId));
        try {
            engine.deleteInstance(application.processInstanceId(), Long.toString(tenantId), "TERMINATE:" + reason);
        } catch (FlowableException | IllegalStateException e) {
            throw new BusinessException("流程已结束或状态已变化，无法终止");
        }
        if (approvalRepository.updateStatus(tenantId, applicationId, "RUNNING", "CANCELLED", now, actorId, now) != 1) {
            throw new BusinessException("申请状态已变化，请刷新后重试");
        }
        businessHooks.finished(tenantId, applicationId, "CANCELLED", actorId, now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("applicationStatus", "CANCELLED");
        detail.put("cancelledTaskIds", cancelled.stream().map(Task::getId).toList());
        detail.put("cancelledAssignees", cancelled.stream()
            .map(Task::getAssignee)
            .map(OaOpsService::parseLongOrNull)
            .filter(java.util.Objects::nonNull)
            .toList());
        detail.put("reason", reason);
        approvalRepository.insertAudit(tenantId, applicationId, application
            .processInstanceId(), null, COMMAND_TERMINATE, actorId, operatorName(tenantId, actorId), null, reason, requestId, writeJson(detail), now);
        if (brief != null) {
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long
                .toString(applicationId), OaOutboxService.EVENT_APPLICATION_TERMINATED, "TERMINATE:" + requestId, applicationId, null, "CANCELLED", "申请已被终止", "您的申请「" + brief
                    .title() + "」已被运维终止：" + reason, List.of(brief.applicantId()));
        }
        OaApprovalActionResp resp = new OaApprovalActionResp(applicationId, "CANCELLED", null, false);
        launchRepository.markDedupSucceeded(tenantId, actorId, COMMAND_TERMINATE, requestId, writeJson(resp));
        return resp;
    }

    public OaOutboxDispatcher.OutboxPage outbox(long tenantId, String status, String keyword, int page, int size) {
        return dispatcher.page(tenantId, status, keyword, page, size);
    }

    public void retryOutbox(long tenantId, long id) {
        dispatcher.retry(tenantId, id);
    }

    public OaOutboxDispatcher.DispatchResult dispatchNow() {
        return dispatcher.dispatchDue();
    }

    private OaApprovalActionResp replay(DedupRecord record) {
        if (!"SUCCEEDED".equals(record.status())) {
            throw new BusinessException("请求正在处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(record.resultJson(), OaApprovalActionResp.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果快照损坏", e);
        }
    }

    private String operatorName(long tenantId, long userId) {
        String name = approvalRepository.userName(tenantId, userId);
        return name == null || name.isBlank() ? Long.toString(userId) : name;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
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
            throw new IllegalStateException("结果序列化失败", e);
        }
    }
}
