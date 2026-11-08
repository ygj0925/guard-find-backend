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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaNotificationResp;
import com.guardfind.backend.oa.model.OaOutboxEventResp;
import com.guardfind.backend.oa.repository.OaNotificationRepository;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知：outbox 投递落库与当前用户的收件箱查询。
 */
@Service
public class OaNotificationService {

    private final OaNotificationRepository repository;
    private final ObjectMapper objectMapper;

    public OaNotificationService(OaNotificationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 把一个 outbox 事件投递为每个接收人的站内通知；重复投递由唯一键吸收。
     */
    public int deliver(OaOutboxEventResp event) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.payload());
        } catch (Exception e) {
            throw new IllegalStateException("通知负载不是合法 JSON", e);
        }
        JsonNode recipients = payload.path("recipients");
        if (!recipients.isArray() || recipients.isEmpty()) {
            return 0;
        }
        String type = text(payload, "notificationType", event.eventType());
        String title = text(payload, "title", "OA 审批通知");
        String content = text(payload, "content", null);
        Long applicationId = nullableLong(payload, "applicationId");
        String taskId = text(payload, "taskId", null);
        String dedupKey = "OUTBOX:" + event.id();
        LocalDateTime now = LocalDateTime.now();
        int delivered = 0;
        for (JsonNode recipient : recipients) {
            long userId = recipient.asLong();
            if (userId <= 0) {
                continue;
            }
            if (repository.insert(event
                .tenantId(), userId, type, title, content, applicationId, taskId, dedupKey, now)) {
                delivered++;
            }
        }
        return delivered;
    }

    public NotificationPage page(long tenantId, long userId, boolean unreadOnly, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<OaNotificationResp> items = repository
            .page(tenantId, userId, unreadOnly, (safePage - 1) * safeSize, safeSize);
        return new NotificationPage(items, repository.count(tenantId, userId, unreadOnly));
    }

    public long unreadCount(long tenantId, long userId) {
        return repository.count(tenantId, userId, true);
    }

    public void markRead(long tenantId, long userId, long id) {
        if (!repository.markRead(tenantId, userId, id, LocalDateTime.now())) {
            throw new BusinessException("通知不存在、已读或无权操作");
        }
    }

    public int markAllRead(long tenantId, long userId) {
        return repository.markAllRead(tenantId, userId, LocalDateTime.now());
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        long parsed = value.asLong();
        return parsed <= 0 ? null : parsed;
    }

    public record NotificationPage(List<OaNotificationResp> items, long total) {
    }
}
