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
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.model.OaApplicationDetailResp;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaApprovalActionResp;
import com.guardfind.backend.oa.model.OaApprovalDetailResp;
import com.guardfind.backend.oa.model.OaDoneRecordResp;
import com.guardfind.backend.oa.model.OaTodoTaskResp;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApprovalApplication;
import com.guardfind.backend.oa.repository.OaApprovalRepository.TodoApplication;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 审批中心：待办/已办查询、通过/驳回/退回与发起人撤回。
 *
 * <p>
 * 每个命令都以 requestId 幂等，成功结果写入 {@code oa_request_dedup} 供重试回放；
 * 引擎动作、申请状态与审计记录在同一事务内提交，失败不会留下半完成的审批。
 * </p>
 */
@Service
public class OaApprovalService {

    public static final String COMMAND_APPROVE = "APPROVE";
    public static final String COMMAND_REJECT = "REJECT";
    public static final String COMMAND_RETURN = "RETURN";
    public static final String COMMAND_WITHDRAW = "WITHDRAW";

    private static final int DEDUP_TTL_HOURS = 24;
    private static final int TODO_SCAN_LIMIT = 200;
    private static final int MAX_REASON_LENGTH = 500;

    private final OaApprovalRepository approvalRepository;
    private final OaLaunchRepository launchRepository;
    private final OaApprovalEngine engine;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaApprovalService(OaApprovalRepository approvalRepository,
                             OaLaunchRepository launchRepository,
                             OaApprovalEngine engine,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.launchRepository = launchRepository;
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ApprovalPage<OaTodoTaskResp> todo(long tenantId, long userId, String keyword, int page, int size) {
        List<Task> tasks = engine.todoTasks(Long.toString(tenantId), Long.toString(userId), TODO_SCAN_LIMIT);
        Set<String> instanceIds = new LinkedHashSet<>();
        for (Task task : tasks) {
            if (task.getProcessInstanceId() != null) {
                instanceIds.add(task.getProcessInstanceId());
            }
        }
        Map<String, TodoApplication> applications = approvalRepository.todoApplications(tenantId, instanceIds);
        List<OaTodoTaskResp> items = new ArrayList<>();
        for (Task task : tasks) {
            TodoApplication application = applications.get(task.getProcessInstanceId());
            if (application == null) {
                continue;
            }
            if (!matchesKeyword(keyword, application.title(), application.businessKey())) {
                continue;
            }
            items.add(new OaTodoTaskResp(application.id(), application.title(), application.businessKey(), application
                .modelName(), application.formName(), application.applicantName(), application.applicantDeptName(), task
                    .getId(), task.getName(), localTime(task.getCreateTime()), application.submittedTime()));
        }
        return paginate(items, page, size);
    }

    public ApprovalPage<OaDoneRecordResp> done(long tenantId, long userId, String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<OaDoneRecordResp> items = approvalRepository
            .donePage(tenantId, userId, keyword, (safePage - 1) * safeSize, safeSize);
        return new ApprovalPage<>(items, approvalRepository.doneCount(tenantId, userId, keyword));
    }

    public OaApprovalDetailResp detail(long tenantId, long userId, String taskId) {
        Task task = engine.authorizedTask(taskId, Long.toString(tenantId), Long.toString(userId));
        if (task != null) {
            ApprovalApplication application = requireApplication(tenantId, task.getProcessInstanceId());
            OaApplicationDetailResp detail = launchRepository.applicationDetailById(tenantId, application.id());
            if (detail == null) {
                throw new BusinessException("申请不存在");
            }
            return new OaApprovalDetailResp(task.getId(), task.getName(), task.getTaskDefinitionKey(), task
                .getAssignee(), true, detail);
        }
        Long applicationId = approvalRepository.auditApplicationId(tenantId, taskId, userId);
        if (applicationId == null) {
            throw new BusinessException("任务不存在或无权查看");
        }
        OaApplicationDetailResp detail = launchRepository.applicationDetailById(tenantId, applicationId);
        if (detail == null) {
            throw new BusinessException("申请不存在");
        }
        return new OaApprovalDetailResp(taskId, null, null, null, false, detail);
    }

    public OaApprovalActionResp approve(long tenantId, long userId, String taskId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = optionalReason(req.reason());
        return command(tenantId, userId, COMMAND_APPROVE, requestId, () -> {
            ActionContext context = prepareAction(tenantId, userId, taskId);
            touch(tenantId, userId, context.application().id());
            try {
                engine.claimIfUnassigned(context.task(), Long.toString(userId));
                engine.complete(context.task().getId());
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException("任务状态已变化，请刷新待办后重试");
            }
            boolean ended = !engine.instanceExists(context.task().getProcessInstanceId(), Long.toString(tenantId));
            LocalDateTime now = LocalDateTime.now();
            String status = ended ? "APPROVED" : "RUNNING";
            if (ended) {
                updateStatus(tenantId, userId, context.application().id(), "APPROVED", now);
            }
            Map<String, Object> detail = actionDetail(requestId, context.task());
            detail.put("processEnded", ended);
            detail.put("applicationStatus", status);
            approvalRepository.insertAudit(tenantId, context.application().id(), context.task()
                .getProcessInstanceId(), context.task()
                    .getId(), COMMAND_APPROVE, userId, operatorName(tenantId, userId), null, reason, requestId, writeJson(detail), now);
            return new OaApprovalActionResp(context.application().id(), status, taskId, false);
        });
    }

    public OaApprovalActionResp reject(long tenantId, long userId, String taskId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = requiredReason(req, "驳回");
        return command(tenantId, userId, COMMAND_REJECT, requestId, () -> {
            ActionContext context = prepareAction(tenantId, userId, taskId);
            touch(tenantId, userId, context.application().id());
            try {
                engine.claimIfUnassigned(context.task(), Long.toString(userId));
                engine.deleteInstance(context.task().getProcessInstanceId(), Long
                    .toString(tenantId), "REJECT:" + reason);
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException("任务状态已变化，请刷新待办后重试");
            }
            LocalDateTime now = LocalDateTime.now();
            updateStatus(tenantId, userId, context.application().id(), "REJECTED", now);
            Map<String, Object> detail = actionDetail(requestId, context.task());
            detail.put("processEnded", true);
            detail.put("applicationStatus", "REJECTED");
            approvalRepository.insertAudit(tenantId, context.application().id(), context.task()
                .getProcessInstanceId(), context.task()
                    .getId(), COMMAND_REJECT, userId, operatorName(tenantId, userId), null, reason, requestId, writeJson(detail), now);
            return new OaApprovalActionResp(context.application().id(), "REJECTED", taskId, false);
        });
    }

    public OaApprovalActionResp returnToPrevious(long tenantId, long userId, String taskId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = requiredReason(req, "退回");
        return command(tenantId, userId, COMMAND_RETURN, requestId, () -> {
            ActionContext context = prepareAction(tenantId, userId, taskId);
            touch(tenantId, userId, context.application().id());
            String targetTaskId;
            try {
                engine.claimIfUnassigned(context.task(), Long.toString(userId));
                targetTaskId = engine.returnToPreviousUserTask(context.task(), Long.toString(tenantId));
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException(e.getMessage());
            }
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> detail = actionDetail(requestId, context.task());
            detail.put("targetTaskId", targetTaskId);
            detail.put("applicationStatus", "RUNNING");
            approvalRepository.insertAudit(tenantId, context.application().id(), context.task()
                .getProcessInstanceId(), context.task()
                    .getId(), COMMAND_RETURN, userId, operatorName(tenantId, userId), null, reason, requestId, writeJson(detail), now);
            return new OaApprovalActionResp(context.application().id(), "RUNNING", taskId, false);
        });
    }

    public OaApprovalActionResp withdraw(long tenantId, long userId, long applicationId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = optionalReason(req.reason());
        return command(tenantId, userId, COMMAND_WITHDRAW, requestId, () -> {
            ApprovalApplication application = approvalRepository.lockApplication(tenantId, applicationId);
            if (application == null || application.applicantId() != userId) {
                throw new BusinessException("申请不存在或无权撤回");
            }
            if (!"RUNNING".equals(application.status())) {
                throw new BusinessException("仅进行中的申请可以撤回");
            }
            if (approvalRepository.effectiveApprovalCount(tenantId, applicationId) > 0) {
                throw new BusinessException("流程已有审批动作，无法撤回");
            }
            try {
                engine.deleteInstance(application.processInstanceId(), Long
                    .toString(tenantId), "WITHDRAW:" + (reason == null ? "" : reason));
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException("流程已结束或状态已变化，无法撤回");
            }
            LocalDateTime now = LocalDateTime.now();
            updateStatus(tenantId, userId, applicationId, "WITHDRAWN", now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("requestId", requestId);
            detail.put("applicationStatus", "WITHDRAWN");
            approvalRepository.insertAudit(tenantId, applicationId, application
                .processInstanceId(), null, COMMAND_WITHDRAW, userId, operatorName(tenantId, userId), null, reason, requestId, writeJson(detail), now);
            return new OaApprovalActionResp(applicationId, "WITHDRAWN", null, false);
        });
    }

    private OaApprovalActionResp command(long tenantId,
                                         long userId,
                                         String command,
                                         String requestId,
                                         Supplier<OaApprovalActionResp> body) {
        DedupRecord existing = launchRepository.dedup(tenantId, userId, command, requestId);
        if (existing != null) {
            return replay(existing);
        }
        try {
            return transactionTemplate.execute(status -> {
                LocalDateTime now = LocalDateTime.now();
                launchRepository.insertDedup(tenantId, userId, command, requestId, now, now.plusHours(DEDUP_TTL_HOURS));
                OaApprovalActionResp resp = body.get();
                launchRepository.markDedupSucceeded(tenantId, userId, command, requestId, writeJson(resp));
                return resp;
            });
        } catch (DuplicateKeyException e) {
            DedupRecord concurrent = launchRepository.dedup(tenantId, userId, command, requestId);
            if (concurrent != null) {
                return replay(concurrent);
            }
            throw new BusinessException("请求处理冲突，请重试");
        }
    }

    private ActionContext prepareAction(long tenantId, long userId, String taskId) {
        Task task = engine.authorizedTask(taskId, Long.toString(tenantId), Long.toString(userId));
        if (task == null) {
            throw new BusinessException("任务不存在或无权处理");
        }
        ApprovalApplication application = requireApplication(tenantId, task.getProcessInstanceId());
        if (!"RUNNING".equals(application.status())) {
            throw new BusinessException("申请已结束，无法继续审批");
        }
        return new ActionContext(task, application);
    }

    private ApprovalApplication requireApplication(long tenantId, String processInstanceId) {
        ApprovalApplication application = approvalRepository.applicationByInstanceId(tenantId, processInstanceId);
        if (application == null) {
            throw new BusinessException("申请不存在");
        }
        return application;
    }

    private void touch(long tenantId, long userId, long applicationId) {
        if (approvalRepository.touchRunning(tenantId, applicationId, userId, LocalDateTime.now()) != 1) {
            throw new BusinessException("申请状态已变化，请刷新后重试");
        }
    }

    private void updateStatus(long tenantId, long userId, long applicationId, String status, LocalDateTime now) {
        if (approvalRepository.updateStatus(tenantId, applicationId, "RUNNING", status, now, userId, now) != 1) {
            throw new BusinessException("申请状态已变化，请刷新后重试");
        }
    }

    private String operatorName(long tenantId, long userId) {
        String name = approvalRepository.userName(tenantId, userId);
        return name == null || name.isBlank() ? Long.toString(userId) : name;
    }

    private Map<String, Object> actionDetail(String requestId, Task task) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("taskName", task.getName());
        detail.put("taskDefinitionKey", task.getTaskDefinitionKey());
        return detail;
    }

    private OaApprovalActionResp replay(DedupRecord record) {
        if ("SUCCEEDED".equals(record.status())) {
            try {
                OaApprovalActionResp stored = objectMapper.readValue(record.resultJson(), OaApprovalActionResp.class);
                return new OaApprovalActionResp(stored.applicationId(), stored.status(), stored.taskId(), true);
            } catch (Exception e) {
                throw new IllegalStateException("幂等结果快照损坏", e);
            }
        }
        if ("PROCESSING".equals(record.status())) {
            throw new BusinessException("请求正在处理中，请稍后重试");
        }
        throw new BusinessException("上一次请求未成功，请重试");
    }

    private static String requireRequestId(OaApprovalActionReq req) {
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BusinessException("请求 ID 不能为空");
        }
        String requestId = req.requestId().trim();
        if (requestId.length() > 64) {
            throw new BusinessException("请求 ID 不能超过 64 个字符");
        }
        return requestId;
    }

    private static String optionalReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String reason = raw.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("原因不能超过 500 个字符");
        }
        return reason;
    }

    private static String requiredReason(OaApprovalActionReq req, String action) {
        String reason = optionalReason(req == null ? null : req.reason());
        if (reason == null) {
            throw new BusinessException("请填写" + action + "原因");
        }
        return reason;
    }

    private static boolean matchesKeyword(String keyword, String title, String businessKey) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String value = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        return (title != null && title.toLowerCase(java.util.Locale.ROOT)
            .contains(value)) || (businessKey != null && businessKey.toLowerCase(java.util.Locale.ROOT)
                .contains(value));
    }

    private static LocalDateTime localTime(java.util.Date date) {
        return date == null ? null : LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private static <T> ApprovalPage<T> paginate(List<T> items, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int from = Math.min(items.size(), (safePage - 1) * safeSize);
        int to = Math.min(items.size(), from + safeSize);
        return new ApprovalPage<>(items.subList(from, to), items.size());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private record ActionContext(Task task, ApprovalApplication application) {

        private ActionContext {
            Objects.requireNonNull(task);
            Objects.requireNonNull(application);
        }
    }

    public record ApprovalPage<T>(List<T> items, long total) {

        public ApprovalPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
