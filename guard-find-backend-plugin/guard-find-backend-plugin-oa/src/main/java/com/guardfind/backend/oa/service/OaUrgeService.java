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
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaUrgeResp;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApplicationBrief;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository.DedupRecord;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 催办：申请人提醒当前处理人，按任务限频且不改变 SLA/任务到达时间。
 *
 * <p>
 * 同一任务在冷却窗口内或达到每日上限时不再发送；催办只写通知 outbox，
 * 不领取、不完成、不重排任务，因此不会重置超期计时。
 * </p>
 */
@Service
public class OaUrgeService {

    public static final String COMMAND_URGE = "URGE";
    private static final int DEDUP_TTL_HOURS = 24;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final OaApprovalRepository approvalRepository;
    private final OaLaunchRepository launchRepository;
    private final OaOutboxRepository outboxRepository;
    private final OaApprovalEngine engine;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final long minMinutes;
    private final int dailyMax;

    public OaUrgeService(OaApprovalRepository approvalRepository,
                         OaLaunchRepository launchRepository,
                         OaOutboxRepository outboxRepository,
                         OaApprovalEngine engine,
                         OaOutboxService outboxService,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager,
                         @Value("${oa.urge.min-minutes:10}") long minMinutes,
                         @Value("${oa.urge.daily-max:5}") int dailyMax) {
        this.approvalRepository = approvalRepository;
        this.launchRepository = launchRepository;
        this.outboxRepository = outboxRepository;
        this.engine = engine;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.minMinutes = Math.max(1, minMinutes);
        this.dailyMax = Math.max(1, dailyMax);
    }

    public OaUrgeResp urge(long tenantId, long userId, long applicationId, OaApprovalActionReq req) {
        String requestId = req == null || req.requestId() == null || req.requestId().isBlank()
            ? null
            : req.requestId().trim();
        if (requestId == null) {
            throw new BusinessException("请求 ID 不能为空");
        }
        DedupRecord existing = launchRepository.dedup(tenantId, userId, COMMAND_URGE, requestId);
        if (existing != null) {
            return replay(existing);
        }
        try {
            return transactionTemplate.execute(status -> doUrge(tenantId, userId, applicationId, requestId));
        } catch (DuplicateKeyException e) {
            DedupRecord concurrent = launchRepository.dedup(tenantId, userId, COMMAND_URGE, requestId);
            if (concurrent != null) {
                return replay(concurrent);
            }
            throw new BusinessException("请求处理冲突，请重试");
        }
    }

    private OaUrgeResp doUrge(long tenantId, long userId, long applicationId, String requestId) {
        LocalDateTime now = LocalDateTime.now();
        launchRepository.insertDedup(tenantId, userId, COMMAND_URGE, requestId, now, now.plusHours(DEDUP_TTL_HOURS));
        ApplicationBrief brief = approvalRepository.applicationBrief(tenantId, applicationId);
        if (brief == null) {
            throw new BusinessException("申请不存在");
        }
        if (brief.applicantId() != userId) {
            throw new BusinessException("仅申请人可以催办");
        }
        if (!"RUNNING".equals(brief.status())) {
            throw new BusinessException("申请已结束，无法催办");
        }
        List<Task> tasks = engine.activeTasks(brief.processInstanceId(), Long.toString(tenantId));
        if (tasks.isEmpty()) {
            throw new BusinessException("当前没有可催办的审批任务");
        }

        LocalDateTime earliest = null;
        int taskCount = 0;
        Set<Long> recipients = new LinkedHashSet<>();
        for (Task task : tasks) {
            LocalDateTime last = outboxRepository.lastEventTime(tenantId, OaOutboxService.AGGREGATE_TASK, task
                .getId(), OaOutboxService.EVENT_TASK_URGED);
            if (last != null) {
                LocalDateTime allowed = last.plusMinutes(minMinutes);
                if (allowed.isAfter(now)) {
                    earliest = earliest == null || allowed.isBefore(earliest) ? allowed : earliest;
                    continue;
                }
            }
            LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
            if (outboxRepository.countEventsSince(tenantId, OaOutboxService.AGGREGATE_TASK, task
                .getId(), OaOutboxService.EVENT_TASK_URGED, dayStart) >= dailyMax) {
                LocalDateTime tomorrow = dayStart.plusDays(1);
                earliest = earliest == null || tomorrow.isBefore(earliest) ? tomorrow : earliest;
                continue;
            }
            List<Long> taskRecipients = engine.recipientIds(task);
            if (taskRecipients.isEmpty()) {
                continue;
            }
            boolean recorded = outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_TASK, task
                .getId(), OaOutboxService.EVENT_TASK_URGED, "URGE:" + task
                    .getId() + ":" + requestId, applicationId, task.getId(), "URGED", "催办提醒", "您有一条待审批：" + brief
                        .title() + "（" + task.getName() + "），请及时处理", taskRecipients);
            if (recorded) {
                taskCount++;
                recipients.addAll(taskRecipients);
            }
        }
        if (taskCount == 0) {
            if (earliest != null) {
                throw new BusinessException("催办过于频繁，请于 " + TIME_FORMAT.format(earliest) + " 后重试");
            }
            throw new BusinessException("当前审批任务没有可提醒的处理人");
        }
        LocalDateTime nextAllowed = earliest == null || earliest.isAfter(now.plusMinutes(minMinutes))
            ? now.plusMinutes(minMinutes)
            : earliest;
        OaUrgeResp resp = new OaUrgeResp(applicationId, taskCount, new ArrayList<>(recipients), now, nextAllowed);
        launchRepository.markDedupSucceeded(tenantId, userId, COMMAND_URGE, requestId, writeJson(resp));
        return resp;
    }

    private OaUrgeResp replay(DedupRecord record) {
        if (!"SUCCEEDED".equals(record.status())) {
            throw new BusinessException("请求正在处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(record.resultJson(), OaUrgeResp.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果快照损坏", e);
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
