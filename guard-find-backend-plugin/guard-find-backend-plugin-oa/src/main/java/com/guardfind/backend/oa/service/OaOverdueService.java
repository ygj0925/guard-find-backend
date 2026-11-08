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

import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaApprovalRepository.ApplicationBrief;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 超期计时：按任务到达时间计算自然小时，超期后每天最多提醒一次。
 *
 * <p>
 * 首版只支持自然时长，不接工作日历；催办与通知不会改写任务到达时间，
 * 因此不会重置本服务使用的 SLA 起点。
 * </p>
 */
@Service
public class OaOverdueService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final OaApprovalRepository approvalRepository;
    private final OaOutboxRepository outboxRepository;
    private final OaApprovalEngine engine;
    private final OaOutboxService outboxService;
    private final long overdueHours;
    private final int scanLimit;

    public OaOverdueService(OaApprovalRepository approvalRepository,
                            OaOutboxRepository outboxRepository,
                            OaApprovalEngine engine,
                            OaOutboxService outboxService,
                            @Value("${oa.overdue.hours:24}") long overdueHours,
                            @Value("${oa.overdue.scan-limit:200}") int scanLimit) {
        this.approvalRepository = approvalRepository;
        this.outboxRepository = outboxRepository;
        this.engine = engine;
        this.outboxService = outboxService;
        this.overdueHours = Math.max(1, overdueHours);
        this.scanLimit = Math.max(1, scanLimit);
    }

    public int scanAll() {
        int recorded = 0;
        for (Long tenantId : approvalRepository.applicationTenants()) {
            recorded += scanTenant(tenantId);
        }
        return recorded;
    }

    public int scanTenant(long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusHours(overdueHours);
        String day = LocalDate.now().format(DAY);
        List<Task> tasks = engine.activeTasksOfTenant(Long.toString(tenantId), scanLimit);
        int recorded = 0;
        for (Task task : tasks) {
            if (task.getCreateTime() == null) {
                continue;
            }
            LocalDateTime reached = LocalDateTime.ofInstant(task.getCreateTime().toInstant(), ZoneId.systemDefault());
            if (reached.isAfter(threshold)) {
                continue;
            }
            ApplicationBrief brief = approvalRepository.applicationBriefByInstanceId(tenantId, task
                .getProcessInstanceId());
            if (brief == null || !"RUNNING".equals(brief.status())) {
                continue;
            }
            String dedupKey = "OVERDUE:" + task.getId() + ":" + day;
            if (outboxRepository.hasEvent(tenantId, dedupKey)) {
                continue;
            }
            List<Long> recipients = engine.recipientIds(task);
            if (recipients.isEmpty()) {
                continue;
            }
            boolean inserted = outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_TASK, task
                .getId(), OaOutboxService.EVENT_TASK_OVERDUE, dedupKey, brief.id(), task
                    .getId(), "OVERDUE", "审批超期提醒", "「" + brief.title() + "」的「" + task
                        .getName() + "」已超过 " + overdueHours + " 小时未处理", recipients);
            if (inserted) {
                recorded++;
            }
        }
        return recorded;
    }
}
