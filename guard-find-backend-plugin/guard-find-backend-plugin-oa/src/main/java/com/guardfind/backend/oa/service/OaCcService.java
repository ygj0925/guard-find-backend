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

import com.guardfind.backend.oa.model.OaApplicationDetailResp;
import com.guardfind.backend.oa.model.OaCcResp;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发起时抄送：接收人只读查看申请快照与轨迹，阅读状态 UNREAD→READ 单向推进。
 */
@Service
public class OaCcService {

    static final int MAX_RECEIVERS = 20;

    private final OaCcRepository repository;
    private final OaLaunchRepository launchRepository;
    private final OaOutboxService outboxService;

    public OaCcService(OaCcRepository repository, OaLaunchRepository launchRepository, OaOutboxService outboxService) {
        this.repository = repository;
        this.launchRepository = launchRepository;
        this.outboxService = outboxService;
    }

    /**
     * 去重并排除发起人本人；任一接收人不是本租户在职员工即拒绝，避免静默漏抄。
     */
    public Map<Long, String> resolveReceivers(long tenantId, long applicantId, List<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : requested) {
            if (id == null || id <= 0) {
                throw new BusinessException("抄送人无效");
            }
            if (id != applicantId) {
                ids.add(id);
            }
        }
        if (ids.size() > MAX_RECEIVERS) {
            throw new BusinessException("抄送人不能超过 " + MAX_RECEIVERS + " 人");
        }
        Map<Long, String> receivers = repository.activeEmployeeNames(tenantId, ids);
        if (receivers.size() != ids.size()) {
            throw new BusinessException("部分抄送人已离职或不是本租户员工，请重新选择");
        }
        return receivers;
    }

    public void record(long tenantId,
                       long applicationId,
                       String processInstanceId,
                       Map<Long, String> receivers,
                       String requestId,
                       String applicantName,
                       String title,
                       LocalDateTime now) {
        if (receivers.isEmpty()) {
            return;
        }
        repository.insertReceipts(tenantId, applicationId, processInstanceId, receivers, now);
        outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long
            .toString(applicationId), OaOutboxService.EVENT_APPLICATION_CC, "CC:" + requestId, applicationId, null, "CC", "抄送：" + title, applicantName + " 提交的「" + title + "」已抄送给您", new ArrayList<>(receivers
                .keySet()));
    }

    public List<OaCcResp> page(long tenantId, long userId, String keyword, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.page(tenantId, userId, keyword, normalizeStatus(status), (safePage - 1) * safeSize, safeSize);
    }

    public long count(long tenantId, long userId, String keyword, String status) {
        return repository.count(tenantId, userId, keyword, normalizeStatus(status));
    }

    @Transactional(rollbackFor = Exception.class)
    public OaApplicationDetailResp detail(long tenantId, long userId, long applicationId) {
        if (!repository.isReceiver(tenantId, applicationId, userId)) {
            throw new BusinessException("抄送记录不存在");
        }
        repository.markRead(tenantId, applicationId, userId, LocalDateTime.now());
        OaApplicationDetailResp detail = launchRepository.applicationDetailById(tenantId, applicationId);
        if (detail == null) {
            throw new BusinessException("抄送记录不存在");
        }
        return detail;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim();
        if (!"UNREAD".equals(value) && !"READ".equals(value)) {
            throw new BusinessException("抄送状态只支持 UNREAD/READ");
        }
        return value;
    }
}
