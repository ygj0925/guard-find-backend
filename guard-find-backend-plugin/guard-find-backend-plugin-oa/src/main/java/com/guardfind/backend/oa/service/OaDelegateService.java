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

import com.guardfind.backend.oa.model.OaDelegateRuleCreateReq;
import com.guardfind.backend.oa.model.OaDelegateRuleResp;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository.Delegation;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 限时代理：委托人把审批任务委托给同租户在职员工，窗口内代理人可见并可处理。
 *
 * <p>
 * 规则只启用一个时间窗；同一对委托/代理人窗口不可重叠，代理链在窗口内逐级回溯，
 * 出现 A→B→A 的环直接拒绝，避免代理人互相无限接管。
 * </p>
 */
@Service
public class OaDelegateService {

    private static final int MAX_WINDOW_DAYS = 90;
    private static final int MAX_REASON_LENGTH = 500;

    private final OaDelegateRepository repository;
    private final OaStaffDirectory staffDirectory;

    public OaDelegateService(OaDelegateRepository repository, OaStaffDirectory staffDirectory) {
        this.repository = repository;
        this.staffDirectory = staffDirectory;
    }

    public List<OaDelegateRuleResp> list(long tenantId, long delegatorId) {
        LocalDateTime now = LocalDateTime.now();
        return repository.rules(tenantId, delegatorId)
            .stream()
            .map(rule -> new OaDelegateRuleResp(rule.id(), rule.delegatorId(), rule.delegatorName(), rule
                .delegateId(), rule.delegateName(), rule.startTime(), rule.endTime(), rule.status(), rule
                    .reason(), "ENABLED".equals(rule.status()) && rule.startTime() != null && rule
                        .endTime() != null && !now.isBefore(rule.startTime()) && now.isBefore(rule.endTime()), rule
                            .createTime()))
            .toList();
    }

    public OaDelegateRuleResp create(long tenantId, long delegatorId, OaDelegateRuleCreateReq req) {
        if (req == null || req.delegateId() == null) {
            throw new BusinessException("代理人不能为空");
        }
        long delegateId = req.delegateId();
        if (delegateId <= 0) {
            throw new BusinessException("代理人无效");
        }
        if (delegateId == delegatorId) {
            throw new BusinessException("不能把审批任务代理给自己");
        }
        if (!staffDirectory.activeEmployee(tenantId, delegatorId)) {
            throw new BusinessException("委托人不是本租户在职员工");
        }
        if (!staffDirectory.activeEmployee(tenantId, delegateId)) {
            throw new BusinessException("代理人不是本租户在职员工");
        }
        LocalDateTime startTime = req.startTime();
        LocalDateTime endTime = req.endTime();
        if (startTime == null || endTime == null) {
            throw new BusinessException("代理时间不能为空");
        }
        if (!startTime.isBefore(endTime)) {
            throw new BusinessException("代理开始时间必须早于结束时间");
        }
        if (Duration.between(startTime, endTime).toDays() > MAX_WINDOW_DAYS) {
            throw new BusinessException("代理窗口不能超过 " + MAX_WINDOW_DAYS + " 天");
        }
        String reason = normalizeReason(req.reason());
        if (repository.overlappingRuleExists(tenantId, delegatorId, delegateId, startTime, endTime)) {
            throw new BusinessException("该代理人在所选时间窗内已有代理规则");
        }
        requireNoCycle(tenantId, delegatorId, delegateId, startTime, endTime);
        try {
            long id = repository
                .insertRule(tenantId, delegatorId, delegateId, startTime, endTime, reason, delegatorId, LocalDateTime
                    .now());
            OaDelegateRuleResp rule = repository.rule(tenantId, id, delegatorId);
            if (rule == null) {
                throw new IllegalStateException("代理规则写入后无法读取");
            }
            return rule;
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该代理人已有代理规则，请先取消后重试");
        }
    }

    public void delete(long tenantId, long delegatorId, long id) {
        if (repository.deleteRule(tenantId, id, delegatorId, delegatorId, LocalDateTime.now()) != 1) {
            throw new BusinessException("代理规则不存在或无权操作");
        }
    }

    public List<Delegation> activeDelegators(long tenantId, long delegateId, LocalDateTime now) {
        return repository.activeDelegations(tenantId, delegateId, now);
    }

    private void requireNoCycle(long tenantId,
                                long delegatorId,
                                long delegateId,
                                LocalDateTime startTime,
                                LocalDateTime endTime) {
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(delegateId);
        visited.add(delegateId);
        while (!queue.isEmpty()) {
            long cursor = queue.removeFirst();
            for (Long next : repository.activeDelegatesOf(tenantId, cursor, startTime, endTime)) {
                if (next == null) {
                    continue;
                }
                if (next == delegatorId) {
                    throw new BusinessException("代理关系不能形成环路");
                }
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
    }

    private static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String reason = raw.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("代理原因不能超过 500 个字符");
        }
        return reason;
    }
}
