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

import com.guardfind.backend.oa.model.OaOutboxEventResp;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 投递器：原子认领、站内通知投递、指数退避重试与人工重排。
 *
 * <p>
 * 投递不在业务事务内；处理中的事件超时会被重新排队，避免 worker 中断后永久卡住。
 * </p>
 */
@Service
public class OaOutboxDispatcher {

    private static final long MAX_BACKOFF_SECONDS = 1800;

    private final OaOutboxRepository repository;
    private final OaNotificationService notificationService;
    private final int maxRetries;
    private final int batchSize;
    private final long retryBaseSeconds;
    private final long processingTimeoutSeconds;

    public OaOutboxDispatcher(OaOutboxRepository repository,
                              OaNotificationService notificationService,
                              @Value("${oa.outbox.max-retries:5}") int maxRetries,
                              @Value("${oa.outbox.batch-size:50}") int batchSize,
                              @Value("${oa.outbox.retry-base-seconds:30}") long retryBaseSeconds,
                              @Value("${oa.outbox.processing-timeout-seconds:600}") long processingTimeoutSeconds) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.maxRetries = Math.max(1, maxRetries);
        this.batchSize = Math.max(1, batchSize);
        this.retryBaseSeconds = Math.max(1, retryBaseSeconds);
        this.processingTimeoutSeconds = Math.max(1, processingTimeoutSeconds);
    }

    public DispatchResult dispatchDue() {
        return dispatchDue(null);
    }

    /** tenantId 为空时投递全部租户（定时任务），否则只投递该租户的到期事件（运维手动触发）。 */
    public DispatchResult dispatchDue(Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        repository.recoverStuck(now.minusSeconds(processingTimeoutSeconds), now);
        List<OaOutboxEventResp> events = repository.findDue(tenantId, now, maxRetries, batchSize);
        int sent = 0;
        int failed = 0;
        for (OaOutboxEventResp event : events) {
            if (!repository.claim(event.id(), now)) {
                continue;
            }
            try {
                int delivered = notificationService.deliver(event);
                repository.markSent(event.id(), delivered == 0 ? "无新增通知（无接收人或已投递过），事件已归档" : null, now);
                sent++;
            } catch (RuntimeException e) {
                int nextRetryCount = event.retryCount() + 1;
                LocalDateTime nextRetryTime = nextRetryCount >= maxRetries
                    ? null
                    : now.plusSeconds(backoffSeconds(nextRetryCount));
                repository.markFailed(event.id(), nextRetryCount, nextRetryTime, errorMessage(e), now);
                failed++;
            }
        }
        return new DispatchResult(events.size(), sent, failed);
    }

    public void retry(long tenantId, long id) {
        if (!repository.retry(tenantId, id, LocalDateTime.now())) {
            throw new BusinessException("事件不存在、仍在处理中或已成功，无法重试");
        }
    }

    public OutboxPage page(long tenantId, String status, String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<OaOutboxEventResp> items = repository.page(tenantId, status, keyword, (safePage - 1) * safeSize, safeSize);
        return new OutboxPage(items, repository.count(tenantId, status, keyword));
    }

    private long backoffSeconds(int retryCount) {
        long multiplier = 1L << Math.min(retryCount - 1, 10);
        return Math.min(MAX_BACKOFF_SECONDS, retryBaseSeconds * multiplier);
    }

    private static String errorMessage(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public record DispatchResult(int scanned, int sent, int failed) {
    }

    public record OutboxPage(List<OaOutboxEventResp> items, long total) {
    }
}
