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
import java.util.LinkedHashMap;
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
    static final int MAX_PER_APPLICATION = 50;
    private static final String STATUS_REVOKED = "REVOKED";

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
                       long applicantId,
                       String requestId,
                       String applicantName,
                       String title,
                       LocalDateTime now) {
        if (receivers.isEmpty()) {
            return;
        }
        repository.insertReceipts(tenantId, applicationId, processInstanceId, receivers, null, applicantId, now);
        outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long
            .toString(applicationId), OaOutboxService.EVENT_APPLICATION_CC, "CC:" + requestId, applicationId, null, "CC", "抄送：" + title, applicantName + " 提交的「" + title + "」已抄送给您", new ArrayList<>(receivers
                .keySet()));
    }

    /**
     * 审批中追加抄送：跳过操作人与已在名单中的人；已撤销的回执复用原行重新激活。调用方须持有申请行锁。
     */
    public Map<Long, String> append(long tenantId,
                                    long applicationId,
                                    String processInstanceId,
                                    long applicantId,
                                    long operatorId,
                                    List<Long> requested,
                                    String sourceTaskId,
                                    LocalDateTime now) {
        if (requested == null || requested.isEmpty()) {
            throw new BusinessException("请选择抄送人");
        }
        Map<Long, String> resolved = resolveReceivers(tenantId, applicantId, requested);
        Map<Long, String> statuses = repository.receiptStatuses(tenantId, applicationId);
        Map<Long, String> fresh = new LinkedHashMap<>();
        Map<Long, String> revived = new LinkedHashMap<>();
        resolved.forEach((id, name) -> {
            if (id == operatorId) {
                return;
            }
            String status = statuses.get(id);
            if (status == null) {
                fresh.put(id, name);
            } else if (STATUS_REVOKED.equals(status)) {
                revived.put(id, name);
            }
        });
        if (fresh.isEmpty() && revived.isEmpty()) {
            throw new BusinessException("所选人员已在抄送名单中，或是申请人/本人，无需抄送");
        }
        long active = statuses.values().stream().filter(s -> !STATUS_REVOKED.equals(s)).count();
        if (active + fresh.size() + revived.size() > MAX_PER_APPLICATION) {
            throw new BusinessException("单个申请的抄送人不能超过 " + MAX_PER_APPLICATION + " 人");
        }
        if (!fresh.isEmpty()) {
            repository.insertReceipts(tenantId, applicationId, processInstanceId, fresh, sourceTaskId, operatorId, now);
        }
        revived.forEach((id, name) -> {
            if (repository.reactivate(tenantId, applicationId, id, name, sourceTaskId, operatorId, now) != 1) {
                throw new BusinessException("抄送状态已变化，请刷新后重试");
            }
        });
        Map<Long, String> added = new LinkedHashMap<>(fresh);
        added.putAll(revived);
        return added;
    }

    /**
     * 只有发起这条抄送的人可以撤销；撤销后接收人立即失去查看与附件权限。调用方须持有申请行锁。
     */
    public OaCcRepository.CcReceipt revoke(long tenantId,
                                           long applicationId,
                                           long receiverId,
                                           long operatorId,
                                           LocalDateTime now) {
        OaCcRepository.CcReceipt receipt = repository.receipt(tenantId, applicationId, receiverId);
        if (receipt == null || STATUS_REVOKED.equals(receipt.status())) {
            throw new BusinessException("抄送记录不存在或已撤销");
        }
        if (receipt.ccBy() == null || receipt.ccBy() != operatorId) {
            throw new BusinessException("只能撤销自己发起的抄送");
        }
        if (repository.revoke(tenantId, applicationId, receiverId, operatorId, now) != 1) {
            throw new BusinessException("抄送状态已变化，请刷新后重试");
        }
        return receipt;
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
