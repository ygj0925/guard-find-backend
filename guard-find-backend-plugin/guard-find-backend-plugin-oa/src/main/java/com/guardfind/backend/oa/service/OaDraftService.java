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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaAssigneeBindingReq;
import com.guardfind.backend.oa.model.OaDraftDetailResp;
import com.guardfind.backend.oa.model.OaDraftResp;
import com.guardfind.backend.oa.model.OaDraftSaveReq;
import com.guardfind.backend.oa.model.OaDraftSaveResp;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository.DraftRow;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository.ApplicantProfile;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 申请草稿：只校验发起资格与容量，不做必填校验；提交时由发起服务完整校验并消费草稿。
 */
@Service
public class OaDraftService {

    static final int MAX_DRAFTS_PER_USER = 50;
    static final int MAX_FORM_DATA_CHARS = 64 * 1024;
    private static final int MAX_CC = 20;

    private static final TypeReference<Map<String, Object>> FORM_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<OaAssigneeBindingReq>> ASSIGNEES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Long>> IDS_TYPE = new TypeReference<>() {
    };

    private final OaDraftRepository repository;
    private final OaLaunchRepository launchRepository;
    private final OaLaunchService launchService;
    private final ObjectMapper objectMapper;

    public OaDraftService(OaDraftRepository repository,
                          OaLaunchRepository launchRepository,
                          OaLaunchService launchService,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.launchRepository = launchRepository;
        this.launchService = launchService;
        this.objectMapper = objectMapper;
    }

    public OaDraftSaveResp create(long tenantId, long userId, OaDraftSaveReq req) {
        long releaseId = requireReleaseId(req);
        launchService.launchableRelease(tenantId, userId, releaseId);
        if (repository.count(tenantId, userId, null) >= MAX_DRAFTS_PER_USER) {
            throw new BusinessException("草稿最多保存 " + MAX_DRAFTS_PER_USER + " 份，请先删除不需要的草稿");
        }
        Payload payload = payload(req);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        long id = repository.insert(tenantId, userId, releaseId, payload.title(), payload.formData(), payload
            .assignees(), payload.ccUserIds(), now);
        return new OaDraftSaveResp(id, 0, now);
    }

    public OaDraftSaveResp update(long tenantId, long userId, long draftId, OaDraftSaveReq req) {
        long releaseId = requireReleaseId(req);
        if (req.version() == null) {
            throw new BusinessException("草稿版本不能为空");
        }
        DraftRow existing = requireDraft(tenantId, userId, draftId);
        if (existing.releaseId() != releaseId) {
            throw new BusinessException("草稿不能更换流程版本，请重新发起");
        }
        launchService.launchableRelease(tenantId, userId, releaseId);
        Payload payload = payload(req);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int updated = repository.update(tenantId, userId, draftId, releaseId, req.version(), payload.title(), payload
            .formData(), payload.assignees(), payload.ccUserIds(), now);
        if (updated == 0) {
            throw new BusinessException("草稿已在其他页面修改，请刷新后重试");
        }
        return new OaDraftSaveResp(draftId, req.version() + 1, now);
    }

    public DraftPage page(long tenantId, long userId, String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        ApplicantProfile profile = launchRepository.applicantProfile(tenantId, userId);
        List<OaDraftResp> items = repository.page(tenantId, userId, keyword, (safePage - 1) * safeSize, safeSize)
            .stream()
            .map(row -> new OaDraftResp(row.id(), row.releaseId(), row.title(), row.modelName(), row.formName(), row
                .releaseVersion(), launchable(row, profile), row.version(), row.createTime(), row.updateTime()))
            .toList();
        return new DraftPage(items, repository.count(tenantId, userId, keyword));
    }

    public OaDraftDetailResp detail(long tenantId, long userId, long draftId) {
        DraftRow row = requireDraft(tenantId, userId, draftId);
        ApplicantProfile profile = launchRepository.applicantProfile(tenantId, userId);
        Map<String, Object> formData = row.formDataJson() == null ? Map.of() : read(row.formDataJson(), FORM_TYPE);
        List<OaAssigneeBindingReq> assignees = row.assigneesJson() == null
            ? List.of()
            : read(row.assigneesJson(), ASSIGNEES_TYPE);
        List<String> ccUserIds = row.ccUserIdsJson() == null
            ? List.of()
            : read(row.ccUserIdsJson(), IDS_TYPE).stream().map(String::valueOf).toList();
        return new OaDraftDetailResp(row.id(), row.releaseId(), row
            .title(), formData, assignees, ccUserIds, launchable(row, profile), row.version(), row.updateTime());
    }

    public void delete(long tenantId, long userId, long draftId) {
        if (repository.softDelete(tenantId, userId, draftId, null, LocalDateTime.now().withNano(0)) == 0) {
            throw new BusinessException("草稿不存在或已提交");
        }
    }

    private DraftRow requireDraft(long tenantId, long userId, long draftId) {
        DraftRow row = repository.find(tenantId, userId, draftId);
        if (row == null) {
            throw new BusinessException("草稿不存在或已提交");
        }
        return row;
    }

    private static boolean launchable(DraftRow row, ApplicantProfile profile) {
        return row.releaseActive() && profile != null && OaLaunchService.launchScopeAllows(row.launchScope(), profile);
    }

    private static long requireReleaseId(OaDraftSaveReq req) {
        if (req == null || req.releaseId() == null) {
            throw new BusinessException("流程发布 ID 不能为空");
        }
        return req.releaseId();
    }

    private Payload payload(OaDraftSaveReq req) {
        String title = req.title() == null || req.title().isBlank() ? null : req.title().trim();
        if (title != null && title.length() > 200) {
            throw new BusinessException("申请标题不能超过 200 个字符");
        }
        Map<String, Object> formData = req.formData() == null ? Map.of() : new LinkedHashMap<>(req.formData());
        String formJson = write(formData);
        if (formJson.length() > MAX_FORM_DATA_CHARS) {
            throw new BusinessException("草稿内容过大，请精简后再保存");
        }
        List<OaAssigneeBindingReq> assignees = req.assignees() == null
            ? List.of()
            : req.assignees().stream().filter(Objects::nonNull).toList();
        List<Long> ccUserIds = req.ccUserIds() == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(req.ccUserIds().stream().filter(Objects::nonNull).toList()));
        if (ccUserIds.size() > MAX_CC) {
            throw new BusinessException("抄送人不能超过 " + MAX_CC + " 人");
        }
        return new Payload(title, formJson, write(assignees), write(ccUserIds));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("草稿内容无法保存");
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("草稿数据损坏", e);
        }
    }

    private record Payload(String title, String formData, String assignees, String ccUserIds) {
    }

    public record DraftPage(List<OaDraftResp> items, long total) {

        public DraftPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
