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

package com.guardfind.backend.oa.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.model.OaDocumentAudience;
import com.guardfind.backend.oa.repository.OaDocumentRepository;
import com.guardfind.backend.oa.repository.OaDocumentRepository.DeptRow;
import com.guardfind.backend.oa.repository.OaDocumentRepository.DocumentRow;
import com.guardfind.backend.oa.repository.OaDocumentRepository.VersionRow;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公文/知识发布钩子：送审时固化正文与发布范围，审批通过才按当时在职受众生成阅读回执并发布；
 * 填写原文号为修订，修订通过后受众需重新阅读（与签收），修订未通过旧版本保持有效。
 */
@Component
public class OaDocumentHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "DOCUMENT";
    public static final String FIELD_TYPE = "docType";
    public static final String FIELD_TITLE = "docTitle";
    public static final String FIELD_CATEGORY = "category";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SCOPE = "audienceScope";
    public static final String FIELD_DEPTS = "audienceDepts";
    public static final String FIELD_USERS = "audienceUsers";
    public static final String FIELD_REQUIRE_ACK = "requireAck";
    public static final String FIELD_FILES = "docFiles";
    public static final String FIELD_BASE_NO = "baseDocNo";
    public static final String FIELD_CHANGE_NOTE = "changeNote";
    public static final String VAR_NOTICE = "docNotice";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String GM_POSITION = "GM";
    public static final String TYPE_NOTICE = "NOTICE";
    public static final String TYPE_KNOWLEDGE = "KNOWLEDGE";
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_CUSTOM = "CUSTOM";
    public static final int MAX_DEPTS = 50;
    public static final int MAX_USERS = 100;

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String EVENT_PUBLISHED = "DOCUMENT_PUBLISHED";

    private static final String FORM_NAME = "公文表单";
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OaDocumentRepository repository;
    private final OaStaffDirectory directory;
    private final OaOutboxService outboxService;

    public OaDocumentHook(OaDocumentRepository repository, OaStaffDirectory directory, OaOutboxService outboxService) {
        this.repository = repository;
        this.directory = directory;
        this.outboxService = outboxService;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        Map<String, JsonNode> fields = OaBusinessFields.fields(schemaJson);
        if (fields == null) {
            return List.of(FORM_NAME + " schema 无法解析");
        }
        List<String> errors = new ArrayList<>();
        OaBusinessFields.requireField(fields, FIELD_TYPE, Set.of("radio"), true, "类型", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_TITLE, Set.of("text"), true, "标题", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_CATEGORY, Set.of("text"), false, "分类", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_CONTENT, Set.of("textarea"), true, "正文", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_SCOPE, Set.of("radio"), true, "发布范围", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_DEPTS, Set.of("checkbox"), false, "发布部门", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_USERS, Set.of("person"), false, "发布人员", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_REQUIRE_ACK, Set.of("radio"), true, "是否需要签收", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_FILES, Set.of("attachment"), false, "附件", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_BASE_NO, Set.of("text"), false, "原文号", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_CHANGE_NOTE, Set
            .of("text", "textarea"), false, "修订说明", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String docType = OaBusinessFields.requireText(formData.get(FIELD_TYPE), "类型", 20);
        if (!TYPE_NOTICE.equals(docType) && !TYPE_KNOWLEDGE.equals(docType)) {
            throw new BusinessException("公文类型无效");
        }
        String title = OaBusinessFields.requireText(formData.get(FIELD_TITLE), "标题", 200);
        String category = OaBusinessFields.text(formData.get(FIELD_CATEGORY));
        if (category != null && category.length() > 64) {
            throw new BusinessException("分类不能超过 64 个字符");
        }
        String content = OaBusinessFields.requireText(formData.get(FIELD_CONTENT), "正文", 5000);
        boolean requireAck = "Y".equals(OaBusinessFields.requireText(formData.get(FIELD_REQUIRE_ACK), "是否需要签收", 1));
        OaDocumentAudience audience = audience(tenantId, formData);
        String changeNote = OaBusinessFields.text(formData.get(FIELD_CHANGE_NOTE));
        if (changeNote != null && changeNote.length() > 500) {
            throw new BusinessException("修订说明不能超过 500 个字符");
        }
        String baseNo = OaBusinessFields.text(formData.get(FIELD_BASE_NO));
        Long documentId = null;
        int fromLatest = 0;
        if (baseNo != null) {
            DocumentRow base = repository.lockDocumentByNo(tenantId, baseNo);
            if (base == null) {
                throw new BusinessException("文号 " + baseNo + " 不存在");
            }
            if (base.ownerId() != applicantId) {
                throw new BusinessException("只能修订本人拟稿的公文");
            }
            if (!STATUS_PUBLISHED.equals(base.status()) && !STATUS_CLOSED.equals(base.status())) {
                throw new BusinessException("公文当前状态为" + label(base.status()) + "，不能发起修订");
            }
            if (base.pendingApplicationId() != null) {
                throw new BusinessException("该公文已有审批中的修订");
            }
            if (!base.docType().equals(docType)) {
                throw new BusinessException("修订不能改变公文类型");
            }
            if (changeNote == null) {
                throw new BusinessException("请填写修订说明");
            }
            documentId = base.id();
            fromLatest = base.latestVersion();
        }
        String audienceJson;
        try {
            audienceJson = MAPPER.writeValueAsString(audience);
        } catch (Exception e) {
            throw new IllegalStateException("发布范围序列化失败", e);
        }
        return new OaBusinessPreparation(Map.of(VAR_NOTICE, TYPE_NOTICE.equals(docType)
            ? 1
            : 0), new Plan(docType, title, category, content, changeNote, audienceJson, requireAck, documentId, fromLatest));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        Plan plan = (Plan)preparation.state();
        if (plan.documentId() == null) {
            long id = repository.insertDocument(tenantId, plan.docType(), plan.title(), plan
                .category(), applicantId, plan.requireAck(), applicationId, now);
            String prefix = TYPE_NOTICE.equals(plan.docType()) ? "GW" : "ZS";
            repository.assignDocNo(tenantId, id, prefix + now.format(NO_DATE) + String.format("%04d", id));
            repository.insertVersion(tenantId, id, 1, applicationId, plan.title(), plan.category(), plan.content(), plan
                .changeNote(), plan.audienceJson(), plan.requireAck(), applicantId, now);
            return;
        }
        if (repository.reviseDocument(tenantId, plan.documentId(), plan.fromLatest(), applicationId, plan.title(), plan
            .category(), now) != 1) {
            throw new BusinessException("公文状态已变化，请刷新后重试");
        }
        repository.insertVersion(tenantId, plan.documentId(), plan.fromLatest() + 1, applicationId, plan.title(), plan
            .category(), plan.content(), plan.changeNote(), plan.audienceJson(), plan.requireAck(), applicantId, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        VersionRow version = repository.lockVersionByApplication(tenantId, applicationId);
        if (version == null || !STATUS_APPROVING.equals(version.status())) {
            return;
        }
        DocumentRow document = repository.lockDocument(tenantId, version.documentId());
        if ("APPROVED".equals(status)) {
            OaDocumentAudience audience = OaDocumentRepository.audience(version.audienceJson());
            List<Long> users = repository.activeAudience(tenantId, audience.all(), expand(repository
                .depts(tenantId), audience.depts()), audience.users());
            repository.insertReceipts(tenantId, version.documentId(), version.versionNo(), users, now);
            repository.publishVersion(tenantId, version.id(), users.size(), now);
            repository.publishDocument(tenantId, version.documentId(), applicationId, version.versionNo(), version
                .title(), version.category(), version.requireAck(), now);
            String kind = document != null && TYPE_KNOWLEDGE.equals(document.docType()) ? "知识" : "公文";
            String action = version.requireAck() ? "请阅读并签收" : "请及时阅读";
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long
                .toString(applicationId), EVENT_PUBLISHED, "PUBLISH:" + version.documentId() + ":" + version
                    .versionNo(), applicationId, null, "DOCUMENT", kind + "发布", "「" + version
                        .title() + "」已发布，" + action, users);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionVersion(tenantId, version.id(), STATUS_APPROVING, status, now);
            repository.closePending(tenantId, version.documentId(), applicationId, now);
        }
    }

    public static String label(String status) {
        return switch (status) {
            case STATUS_APPROVING -> "审批中";
            case STATUS_PUBLISHED -> "已发布";
            case STATUS_CLOSED -> "已关闭";
            case STATUS_REVOKED -> "已撤稿";
            default -> status;
        };
    }

    /**
     * 选中部门连同全部下级部门。
     */
    public static Set<Long> expand(List<DeptRow> depts, Collection<Long> selected) {
        Set<Long> result = new LinkedHashSet<>();
        if (selected == null || selected.isEmpty()) {
            return result;
        }
        Map<Long, List<Long>> children = new HashMap<>();
        for (DeptRow dept : depts) {
            children.computeIfAbsent(dept.parentId(), key -> new ArrayList<>()).add(dept.id());
        }
        Deque<Long> queue = new ArrayDeque<>(selected);
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (result.add(id)) {
                queue.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return result;
    }

    private OaDocumentAudience audience(long tenantId, Map<String, Object> formData) {
        String scope = OaBusinessFields.requireText(formData.get(FIELD_SCOPE), "发布范围", 20);
        if (SCOPE_ALL.equals(scope)) {
            if (repository.activeAudience(tenantId, true, List.of(), List.of()).isEmpty()) {
                throw new BusinessException("当前没有在职员工可接收");
            }
            return new OaDocumentAudience(true, List.of(), List.of());
        }
        if (!SCOPE_CUSTOM.equals(scope)) {
            throw new BusinessException("发布范围无效");
        }
        List<DeptRow> depts = repository.depts(tenantId);
        Set<Long> deptIds = new LinkedHashSet<>();
        if (formData.get(FIELD_DEPTS) instanceof Collection<?> items) {
            Set<Long> known = new LinkedHashSet<>();
            depts.forEach(dept -> known.add(dept.id()));
            for (Object item : items) {
                Long id = parseId(item);
                if (id == null || !known.contains(id)) {
                    throw new BusinessException("发布部门无效，请联系管理员重新生成模板");
                }
                deptIds.add(id);
            }
        }
        Set<Long> userIds = new LinkedHashSet<>();
        if (formData.get(FIELD_USERS) instanceof Collection<?> items) {
            for (Object item : items) {
                Long id = parseId(item);
                if (id == null || !directory.activeEmployee(tenantId, id)) {
                    throw new BusinessException("发布人员中包含非在职员工");
                }
                userIds.add(id);
            }
        }
        if (deptIds.isEmpty() && userIds.isEmpty()) {
            throw new BusinessException("请选择发布部门或人员");
        }
        if (deptIds.size() > MAX_DEPTS || userIds.size() > MAX_USERS) {
            throw new BusinessException("发布范围最多 " + MAX_DEPTS + " 个部门、" + MAX_USERS + " 名人员");
        }
        if (repository.activeAudience(tenantId, false, expand(depts, deptIds), userIds).isEmpty()) {
            throw new BusinessException("发布范围内没有在职员工");
        }
        return new OaDocumentAudience(false, List.copyOf(deptIds), List.copyOf(userIds));
    }

    private static Long parseId(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record Plan(String docType, String title, String category, String content, String changeNote, String audienceJson,
                boolean requireAck, Long documentId, int fromLatest) {
    }
}
