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
import com.guardfind.backend.oa.model.OaTaskTransferReq;
import com.guardfind.backend.oa.model.OaTodoTaskResp;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApprovalApplication;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApplicationBrief;
import com.guardfind.backend.oa.repository.OaApprovalRepository.TodoApplication;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository.Delegation;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository.DedupRecord;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
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
    public static final String COMMAND_CLAIM = "CLAIM";
    public static final String COMMAND_TRANSFER = "TRANSFER";

    private static final int DEDUP_TTL_HOURS = 24;
    private static final int TODO_SCAN_LIMIT = 200;
    private static final int MAX_REASON_LENGTH = 500;

    private final OaApprovalRepository approvalRepository;
    private final OaLaunchRepository launchRepository;
    private final OaDelegateRepository delegateRepository;
    private final OaStaffDirectory staffDirectory;
    private final OaApprovalEngine engine;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaApprovalService(OaApprovalRepository approvalRepository,
                             OaLaunchRepository launchRepository,
                             OaDelegateRepository delegateRepository,
                             OaStaffDirectory staffDirectory,
                             OaApprovalEngine engine,
                             OaOutboxService outboxService,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.approvalRepository = approvalRepository;
        this.launchRepository = launchRepository;
        this.delegateRepository = delegateRepository;
        this.staffDirectory = staffDirectory;
        this.engine = engine;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ApprovalPage<OaTodoTaskResp> todo(long tenantId, long userId, String keyword, int page, int size) {
        Map<String, TaskAccess> accesses = new LinkedHashMap<>();
        for (Task task : engine.todoTasks(Long.toString(tenantId), Long.toString(userId), TODO_SCAN_LIMIT)) {
            accesses.put(task.getId(), new TaskAccess(task, null, null));
        }
        for (Delegation delegation : activeDelegations(tenantId, userId)) {
            for (Task task : engine.todoTasks(Long.toString(tenantId), Long.toString(delegation
                .delegatorId()), TODO_SCAN_LIMIT)) {
                accesses.putIfAbsent(task.getId(), new TaskAccess(task, delegation.delegatorId(), delegation
                    .delegatorName()));
            }
        }
        Set<String> instanceIds = new LinkedHashSet<>();
        for (TaskAccess access : accesses.values()) {
            if (access.task().getProcessInstanceId() != null) {
                instanceIds.add(access.task().getProcessInstanceId());
            }
        }
        Map<String, TodoApplication> applications = approvalRepository.todoApplications(tenantId, instanceIds);
        List<OaTodoTaskResp> items = new ArrayList<>();
        for (TaskAccess access : accesses.values()) {
            Task task = access.task();
            TodoApplication application = applications.get(task.getProcessInstanceId());
            if (application == null) {
                continue;
            }
            if (!matchesKeyword(keyword, application.title(), application.businessKey())) {
                continue;
            }
            items.add(new OaTodoTaskResp(application.id(), application.title(), application.businessKey(), application
                .modelName(), application.formName(), application.applicantName(), application.applicantDeptName(), task
                    .getId(), task.getName(), localTime(task.getCreateTime()), application.submittedTime(), access
                        .proxyForId(), access.proxyForName()));
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
        TaskAccess access = resolveAccess(tenantId, userId, taskId);
        if (access != null) {
            Task task = access.task();
            ApprovalApplication application = requireApplication(tenantId, task.getProcessInstanceId());
            OaApplicationDetailResp detail = launchRepository.applicationDetailById(tenantId, application.id());
            if (detail == null) {
                throw new BusinessException("申请不存在");
            }
            return new OaApprovalDetailResp(task.getId(), task.getName(), task.getTaskDefinitionKey(), task
                .getAssignee(), true, detail, access.proxyForId(), access.proxyForName());
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
            Long originalAssignee = originalAssignee(context);
            boolean countersign = engine.isMultiInstance(context.task());
            String instanceId = context.task().getProcessInstanceId();
            List<Task> before = engine.activeTasks(instanceId, Long.toString(tenantId));
            try {
                claimForAction(context, userId);
                engine.complete(context.task().getId());
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException("任务状态已变化，请刷新待办后重试");
            }
            boolean ended = !engine.instanceExists(instanceId, Long.toString(tenantId));
            List<Task> cancelled = cancelledTasks(tenantId, before, instanceId, context.task().getId(), ended);
            LocalDateTime now = LocalDateTime.now();
            String status = ended ? "APPROVED" : "RUNNING";
            if (ended) {
                updateStatus(tenantId, userId, context.application().id(), "APPROVED", now);
            }
            Map<String, Object> detail = actionDetail(requestId, context.task());
            detail.put("processEnded", ended);
            detail.put("applicationStatus", status);
            detail.put("countersign", countersign);
            putCancelled(detail, cancelled, "会签完成条件已满足，剩余任务自动终止");
            putProxy(detail, context);
            approvalRepository.insertAudit(tenantId, context.application().id(), context.task()
                .getProcessInstanceId(), context.task()
                    .getId(), COMMAND_APPROVE, userId, operatorName(tenantId, userId), originalAssignee, reason, requestId, writeJson(detail), now);
            ApplicationBrief brief = approvalRepository.applicationBrief(tenantId, context.application().id());
            notifyOutcome(tenantId, brief, ended
                ? OaOutboxService.EVENT_APPLICATION_APPROVED
                : null, "APPROVED", "申请已通过", "您的申请「" + titleOf(brief) + "」已审批通过", ended
                    ? "APPROVED:" + context.application().id() + ":" + requestId
                    : null);
            if (!ended) {
                notifyNewTasks(tenantId, brief, instanceId, before, "TODO", "待审批：", "已流转到", "待您审批");
            }
            notifyCancelled(tenantId, brief, cancelled, context.task().getId(), "会签已完成", "会签完成条件已满足，您无需再处理");
            return new OaApprovalActionResp(context.application().id(), status, taskId, false);
        });
    }

    public OaApprovalActionResp reject(long tenantId, long userId, String taskId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = requiredReason(req, "驳回");
        return command(tenantId, userId, COMMAND_REJECT, requestId, () -> {
            ActionContext context = prepareAction(tenantId, userId, taskId);
            touch(tenantId, userId, context.application().id());
            Long originalAssignee = originalAssignee(context);
            boolean countersign = engine.isMultiInstance(context.task());
            String instanceId = context.task().getProcessInstanceId();
            List<Task> before = engine.activeTasks(instanceId, Long.toString(tenantId));
            try {
                claimForAction(context, userId);
                engine.deleteInstance(instanceId, Long.toString(tenantId), "REJECT:" + reason);
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException("任务状态已变化，请刷新待办后重试");
            }
            List<Task> cancelled = before.stream()
                .filter(task -> !task.getId().equals(taskId))
                .filter(task -> engine.wasCancelled(task.getId(), Long.toString(tenantId)))
                .toList();
            LocalDateTime now = LocalDateTime.now();
            updateStatus(tenantId, userId, context.application().id(), "REJECTED", now);
            Map<String, Object> detail = actionDetail(requestId, context.task());
            detail.put("processEnded", true);
            detail.put("applicationStatus", "REJECTED");
            detail.put("countersign", countersign);
            detail.put("veto", true);
            putCancelled(detail, cancelled, "一票否决：驳回后终止流程并取消剩余任务");
            putProxy(detail, context);
            approvalRepository.insertAudit(tenantId, context.application().id(), context.task()
                .getProcessInstanceId(), context.task()
                    .getId(), COMMAND_REJECT, userId, operatorName(tenantId, userId), originalAssignee, reason, requestId, writeJson(detail), now);
            ApplicationBrief brief = approvalRepository.applicationBrief(tenantId, context.application().id());
            notifyOutcome(tenantId, brief, OaOutboxService.EVENT_APPLICATION_REJECTED, "REJECTED", "申请被驳回", "您的申请「" + titleOf(brief) + "」被驳回：" + reason, "REJECTED:" + context
                .application()
                .id() + ":" + requestId);
            notifyCancelled(tenantId, brief, cancelled, taskId, "审批已驳回", "驳回后流程已终止，您无需再处理");
            return new OaApprovalActionResp(context.application().id(), "REJECTED", taskId, false);
        });
    }

    public OaApprovalActionResp returnToPrevious(long tenantId, long userId, String taskId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = requiredReason(req, "退回");
        return command(tenantId, userId, COMMAND_RETURN, requestId, () -> {
            ActionContext context = prepareAction(tenantId, userId, taskId);
            touch(tenantId, userId, context.application().id());
            Long originalAssignee = originalAssignee(context);
            String targetTaskId;
            try {
                claimForAction(context, userId);
                targetTaskId = engine.returnToPreviousUserTask(context.task(), Long.toString(tenantId));
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException(e.getMessage());
            }
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> detail = actionDetail(requestId, context.task());
            detail.put("targetTaskId", targetTaskId);
            detail.put("applicationStatus", "RUNNING");
            putProxy(detail, context);
            approvalRepository.insertAudit(tenantId, context.application().id(), context.task()
                .getProcessInstanceId(), context.task()
                    .getId(), COMMAND_RETURN, userId, operatorName(tenantId, userId), originalAssignee, reason, requestId, writeJson(detail), now);
            ApplicationBrief brief = approvalRepository.applicationBrief(tenantId, context.application().id());
            engine.activeTasks(context.task().getProcessInstanceId(), Long.toString(tenantId))
                .stream()
                .filter(task -> task.getId().equals(targetTaskId))
                .findFirst()
                .ifPresent(task -> notifyTask(tenantId, brief, task, "RETURNED", "退回待处理", "「" + titleOf(brief) + "」已退回至「" + task
                    .getName() + "」，待您重新处理", "ASSIGN:" + task.getId()));
            return new OaApprovalActionResp(context.application().id(), "RUNNING", taskId, false);
        });
    }

    /**
     * 候选领取：候选人或已领取人本人可领取，重复领取幂等。
     */
    public OaApprovalActionResp claim(long tenantId, long userId, String taskId, OaApprovalActionReq req) {
        String requestId = requireRequestId(req);
        String reason = optionalReason(req.reason());
        return command(tenantId, userId, COMMAND_CLAIM, requestId, () -> {
            Task task = engine.authorizedTask(taskId, Long.toString(tenantId), Long.toString(userId));
            if (task == null) {
                throw new BusinessException("任务不存在或无权领取");
            }
            ApprovalApplication application = requireApplication(tenantId, task.getProcessInstanceId());
            if (!"RUNNING".equals(application.status())) {
                throw new BusinessException("申请已结束，无法领取");
            }
            try {
                engine.claim(task, Long.toString(userId));
            } catch (IllegalStateException e) {
                throw new BusinessException(e.getMessage());
            }
            touch(tenantId, userId, application.id());
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> detail = actionDetail(requestId, task);
            detail.put("applicationStatus", "RUNNING");
            approvalRepository.insertAudit(tenantId, application.id(), task.getProcessInstanceId(), task
                .getId(), COMMAND_CLAIM, userId, operatorName(tenantId, userId), null, reason, requestId, writeJson(detail), now);
            return new OaApprovalActionResp(application.id(), "RUNNING", taskId, false);
        });
    }

    /**
     * 转交：仅已领取的处理人可转交给本租户其他在职员工，原处理人从任务身份中移除。
     */
    public OaApprovalActionResp transfer(long tenantId, long userId, String taskId, OaTaskTransferReq req) {
        if (req == null) {
            throw new BusinessException("转交参数不能为空");
        }
        String requestId = requireRequestId(req.requestId());
        String reason = optionalReason(req.reason());
        Long targetUserId = req.targetUserId();
        if (targetUserId == null || targetUserId <= 0) {
            throw new BusinessException("目标处理人无效");
        }
        if (targetUserId == userId) {
            throw new BusinessException("不能转交给自己");
        }
        return command(tenantId, userId, COMMAND_TRANSFER, requestId, () -> {
            Task task = engine.authorizedTask(taskId, Long.toString(tenantId), Long.toString(userId));
            if (task == null) {
                throw new BusinessException("任务不存在或无权转交");
            }
            ApprovalApplication application = requireApplication(tenantId, task.getProcessInstanceId());
            if (!"RUNNING".equals(application.status())) {
                throw new BusinessException("申请已结束，无法转交");
            }
            if (task.getAssignee() == null || !task.getAssignee().equals(Long.toString(userId))) {
                throw new BusinessException("请先领取任务后再转交");
            }
            if (!staffDirectory.activeEmployee(tenantId, targetUserId)) {
                throw new BusinessException("目标处理人不是本租户在职员工");
            }
            Long originalAssignee = parseLong(task.getAssignee());
            touch(tenantId, userId, application.id());
            try {
                engine.reassign(task, Long.toString(targetUserId));
            } catch (FlowableException | IllegalStateException e) {
                throw new BusinessException("任务状态已变化，请刷新待办后重试");
            }
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> detail = actionDetail(requestId, task);
            detail.put("targetUserId", targetUserId);
            detail.put("applicationStatus", "RUNNING");
            approvalRepository.insertAudit(tenantId, application.id(), task.getProcessInstanceId(), task
                .getId(), COMMAND_TRANSFER, userId, operatorName(tenantId, userId), originalAssignee, reason, requestId, writeJson(detail), now);
            ApplicationBrief brief = approvalRepository.applicationBrief(tenantId, application.id());
            notifyUsers(tenantId, brief, task, List
                .of(targetUserId), "TRANSFERRED", "任务已转交", "「" + titleOf(brief) + "」的审批任务已转交给您", "TRANSFER:" + task
                    .getId() + ":" + requestId);
            return new OaApprovalActionResp(application.id(), "RUNNING", taskId, false);
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
        TaskAccess access = resolveAccess(tenantId, userId, taskId);
        if (access == null) {
            throw new BusinessException("任务不存在或无权处理");
        }
        ApprovalApplication application = requireApplication(tenantId, access.task().getProcessInstanceId());
        if (!"RUNNING".equals(application.status())) {
            throw new BusinessException("申请已结束，无法继续审批");
        }
        return new ActionContext(access.task(), application, access.proxyForId());
    }

    private TaskAccess resolveAccess(long tenantId, long userId, String taskId) {
        Task direct = engine.authorizedTask(taskId, Long.toString(tenantId), Long.toString(userId));
        if (direct != null) {
            return new TaskAccess(direct, null, null);
        }
        for (Delegation delegation : activeDelegations(tenantId, userId)) {
            Task proxied = engine.authorizedTask(taskId, Long.toString(tenantId), Long.toString(delegation
                .delegatorId()));
            if (proxied != null) {
                return new TaskAccess(proxied, delegation.delegatorId(), delegation.delegatorName());
            }
        }
        return null;
    }

    private List<Delegation> activeDelegations(long tenantId, long userId) {
        return delegateRepository.activeDelegations(tenantId, userId, LocalDateTime.now());
    }

    private void claimForAction(ActionContext context, long userId) {
        if (context.proxyForId() == null) {
            engine.claimIfUnassigned(context.task(), Long.toString(userId));
        }
    }

    private static Long originalAssignee(ActionContext context) {
        Long assignee = parseLong(context.task().getAssignee());
        return assignee == null ? context.proxyForId() : assignee;
    }

    private static void putProxy(Map<String, Object> detail, ActionContext context) {
        if (context.proxyForId() != null) {
            detail.put("proxyForId", context.proxyForId());
        }
    }

    /**
     * 对比动作前后的活动任务，只把引擎确认“非正常完成”的任务记为取消。
     */
    private List<Task> cancelledTasks(long tenantId,
                                      List<Task> before,
                                      String instanceId,
                                      String currentTaskId,
                                      boolean ended) {
        Set<String> remaining = new LinkedHashSet<>();
        if (!ended) {
            for (Task task : engine.activeTasks(instanceId, Long.toString(tenantId))) {
                remaining.add(task.getId());
            }
        }
        List<Task> cancelled = new ArrayList<>();
        for (Task task : before) {
            if (task.getId().equals(currentTaskId) || remaining.contains(task.getId())) {
                continue;
            }
            if (engine.wasCancelled(task.getId(), Long.toString(tenantId))) {
                cancelled.add(task);
            }
        }
        return cancelled;
    }

    private static void putCancelled(Map<String, Object> detail, List<Task> cancelled, String reason) {
        if (cancelled.isEmpty()) {
            return;
        }
        detail.put("cancelledTaskIds", cancelled.stream().map(Task::getId).toList());
        detail.put("cancelledAssignees", cancelled.stream().map(Task::getAssignee).filter(Objects::nonNull).toList());
        detail.put("cancelReason", reason);
    }

    private void notifyOutcome(long tenantId,
                               ApplicationBrief brief,
                               String eventType,
                               String notificationType,
                               String title,
                               String content,
                               String dedupKey) {
        if (brief == null || eventType == null) {
            return;
        }
        outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(brief
            .id()), eventType, dedupKey, brief.id(), null, notificationType, title, content, List.of(brief
                .applicantId()));
    }

    /**
     * 只提醒本次动作后新产生的任务，避免重复通知仍在等待的审批人。
     */
    private void notifyNewTasks(long tenantId,
                                ApplicationBrief brief,
                                String instanceId,
                                List<Task> before,
                                String notificationType,
                                String titlePrefix,
                                String verb,
                                String suffix) {
        if (brief == null) {
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (Task task : before) {
            existing.add(task.getId());
        }
        for (Task task : engine.activeTasks(instanceId, Long.toString(tenantId))) {
            if (!existing.contains(task.getId())) {
                notifyTask(tenantId, brief, task, notificationType, titlePrefix + brief.title(), "「" + brief
                    .title() + "」" + verb + "「" + task.getName() + "」，" + suffix, "ASSIGN:" + task.getId());
            }
        }
    }

    private void notifyTask(long tenantId,
                            ApplicationBrief brief,
                            Task task,
                            String notificationType,
                            String title,
                            String content,
                            String dedupKey) {
        notifyUsers(tenantId, brief, task, engine.recipientIds(task), notificationType, title, content, dedupKey);
    }

    private void notifyUsers(long tenantId,
                             ApplicationBrief brief,
                             Task task,
                             List<Long> recipients,
                             String notificationType,
                             String title,
                             String content,
                             String dedupKey) {
        if (brief == null || recipients == null || recipients.isEmpty()) {
            return;
        }
        outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_TASK, task
            .getId(), OaOutboxService.EVENT_TASK_ASSIGNED, dedupKey, brief.id(), task
                .getId(), notificationType, title, content, recipients);
    }

    private void notifyCancelled(long tenantId,
                                 ApplicationBrief brief,
                                 List<Task> cancelled,
                                 String currentTaskId,
                                 String title,
                                 String content) {
        if (brief == null) {
            return;
        }
        for (Task task : cancelled) {
            Long assignee = parseLong(task.getAssignee());
            if (assignee == null) {
                continue;
            }
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_TASK, task
                .getId(), OaOutboxService.EVENT_TASK_ASSIGNED, "CANCEL:" + task.getId() + ":" + currentTaskId, brief
                    .id(), task.getId(), "CANCELLED", title, "「" + brief.title() + "」" + content, List.of(assignee));
        }
    }

    private static String titleOf(ApplicationBrief brief) {
        return brief == null ? "申请" : brief.title();
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
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
        return requireRequestId(req == null ? null : req.requestId());
    }

    private static String requireRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("请求 ID 不能为空");
        }
        String requestId = raw.trim();
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

    private record TaskAccess(Task task, Long proxyForId, String proxyForName) {
    }

    private record ActionContext(Task task, ApprovalApplication application, Long proxyForId) {

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
