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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务事务内的通知 outbox 写入。
 *
 * <p>
 * 只写库不投递；投递由 {@link OaOutboxDispatcher} 在事务外重试，
 * 业务成功与通知事件要么一起提交，要么一起回滚。
 * </p>
 */
@Service
public class OaOutboxService {

    public static final String AGGREGATE_APPLICATION = "APPLICATION";
    public static final String AGGREGATE_TASK = "TASK";

    public static final String EVENT_APPLICATION_SUBMITTED = "APPLICATION_SUBMITTED";
    public static final String EVENT_TASK_ASSIGNED = "TASK_ASSIGNED";
    public static final String EVENT_TASK_TRANSFERRED = "TASK_TRANSFERRED";
    public static final String EVENT_TASK_URGED = "TASK_URGED";
    public static final String EVENT_TASK_OVERDUE = "TASK_OVERDUE";
    public static final String EVENT_APPLICATION_APPROVED = "APPLICATION_APPROVED";
    public static final String EVENT_APPLICATION_REJECTED = "APPLICATION_REJECTED";
    public static final String EVENT_APPLICATION_RETURNED = "APPLICATION_RETURNED";
    public static final String EVENT_APPLICATION_TERMINATED = "APPLICATION_TERMINATED";
    public static final String EVENT_APPLICATION_CC = "APPLICATION_CC";

    private final OaOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OaOutboxService(OaOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public boolean recordNotification(long tenantId,
                                      String aggregateType,
                                      String aggregateId,
                                      String eventType,
                                      String dedupKey,
                                      Long applicationId,
                                      String taskId,
                                      String notificationType,
                                      String title,
                                      String content,
                                      Collection<Long> recipients) {
        List<Long> targetUsers = recipients == null
            ? List.of()
            : recipients.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (targetUsers.isEmpty()) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", applicationId);
        payload.put("taskId", taskId);
        payload.put("notificationType", notificationType);
        payload.put("title", title);
        payload.put("content", content);
        payload.put("recipients", new LinkedHashSet<>(targetUsers));
        try {
            return repository.insert(tenantId, UUID.randomUUID()
                .toString(), aggregateType, aggregateId, eventType, objectMapper
                    .writeValueAsString(payload), dedupKey, LocalDateTime.now());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通知事件序列化失败", e);
        }
    }
}
