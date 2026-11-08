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
import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.launch.OaLaunchScope;
import com.guardfind.backend.oa.model.OaAssigneeBindingReq;
import com.guardfind.backend.oa.model.OaAssigneeVariableResp;
import com.guardfind.backend.oa.model.OaLaunchDetailResp;
import com.guardfind.backend.oa.model.OaLaunchItemResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitResp;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository.ApplicantProfile;
import com.guardfind.backend.oa.repository.OaLaunchRepository.DedupRecord;
import com.guardfind.backend.oa.repository.OaLaunchRepository.OaLaunchRelease;
import com.guardfind.backend.oa.staff.OaAssigneeContext;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaAssigneeType;
import com.guardfind.backend.oa.staff.OaAssigneeUnavailableException;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 发起流程：按不可变发布版本校验范围、表单与审批人变量，再在同一事务内启动引擎并落库。
 *
 * <p>
 * requestId 在事务内先写入 PROCESSING；任何一步失败都回滚幂等记录与引擎实例，
 * 成功重试直接回放首次结果，不会重复发起或重复写审批轨迹。
 * </p>
 */
@Service
public class OaLaunchService {

    public static final String COMMAND_SUBMIT = "SUBMIT";
    private static final int DEDUP_TTL_HOURS = 24;
    private static final DateTimeFormatter BUSINESS_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OaLaunchRepository repository;
    private final OaBpmnValidator bpmnValidator;
    private final OaFormValueValidator formValueValidator;
    private final OaAttachmentService attachmentService;
    private final OaProcessLauncher launcher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaLaunchService(OaLaunchRepository repository,
                           OaBpmnValidator bpmnValidator,
                           OaFormValueValidator formValueValidator,
                           OaAttachmentService attachmentService,
                           OaProcessLauncher launcher,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.bpmnValidator = bpmnValidator;
        this.formValueValidator = formValueValidator;
        this.attachmentService = attachmentService;
        this.launcher = launcher;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public LaunchPage page(long tenantId, long userId, String keyword, String category, int page, int size) {
        ApplicantProfile profile = repository.applicantProfile(tenantId, userId);
        if (profile == null) {
            return new LaunchPage(List.of(), 0);
        }
        List<OaLaunchItemResp> allowed = repository.activeLaunches(tenantId, keyword, category)
            .stream()
            .filter(item -> scopeAllows(item.launchScope(), profile))
            .toList();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int from = Math.min(allowed.size(), (safePage - 1) * safeSize);
        int to = Math.min(allowed.size(), from + safeSize);
        return new LaunchPage(allowed.subList(from, to), allowed.size());
    }

    public OaLaunchDetailResp detail(long tenantId, long userId, long releaseId) {
        ApplicantProfile profile = requireApplicant(tenantId, userId);
        OaLaunchRelease release = requireLaunchable(tenantId, releaseId, profile);
        OaBpmnValidationResult validation = supportableValidation(release);
        Set<String> personFields = personFields(release.schemaJson());
        List<OaAssigneeVariableResp> variables = validation.assigneeVariables()
            .stream()
            .map(name -> suggestion(name, personFields))
            .toList();
        return new OaLaunchDetailResp(release.releaseId(), release.modelId(), release.modelKey(), release
            .modelName(), release.category(), release.description(), release.releaseVersion(), release
                .processDefinitionKey(), release.formId(), release.formVersionId(), release.formVersion(), release
                    .formName(), release.formKey(), release.schemaJson(), release.schemaDigest(), release
                        .launchScope(), true, variables);
    }

    public OaLaunchSubmitResp submit(long tenantId, long userId, long releaseId, OaLaunchSubmitReq req) {
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BusinessException("请求 ID 不能为空");
        }
        String requestId = req.requestId().trim();
        DedupRecord existing = repository.dedup(tenantId, userId, COMMAND_SUBMIT, requestId);
        if (existing != null) {
            return replayOrReject(existing);
        }
        try {
            return transactionTemplate.execute(status -> doSubmit(tenantId, userId, releaseId, requestId, req));
        } catch (DuplicateKeyException e) {
            DedupRecord concurrent = repository.dedup(tenantId, userId, COMMAND_SUBMIT, requestId);
            if (concurrent != null) {
                return replayOrReject(concurrent);
            }
            throw new BusinessException("请求处理冲突，请重试");
        }
    }

    private OaLaunchSubmitResp doSubmit(long tenantId,
                                        long userId,
                                        long releaseId,
                                        String requestId,
                                        OaLaunchSubmitReq req) {
        LocalDateTime now = LocalDateTime.now();
        repository.insertDedup(tenantId, userId, COMMAND_SUBMIT, requestId, now, now.plusHours(DEDUP_TTL_HOURS));

        ApplicantProfile profile = requireApplicant(tenantId, userId);
        OaLaunchRelease release = requireLaunchable(tenantId, releaseId, profile);
        OaBpmnValidationResult validation = supportableValidation(release);

        OaFormValueValidator.OaFormValueValidation values = formValueValidator.validate(release.schemaJson(), req
            .formData());
        if (!values.valid()) {
            throw new BusinessException("表单校验失败：" + String.join("；", values.errors().values()));
        }
        if (values.normalized().containsKey(OaAssigneeContext.FORM_DATA_VARIABLE)) {
            throw new BusinessException("表单字段不能使用保留名称 " + OaAssigneeContext.FORM_DATA_VARIABLE);
        }
        OaAttachmentService.PreparedForm prepared = attachmentService.prepare(tenantId, userId, values.normalized());
        Map<String, Object> normalized = prepared.values();

        Set<String> personFields = personFields(release.schemaJson());
        Map<String, OaAssigneeSpec> bindings = resolveBindings(validation, req.assignees(), personFields);

        Map<String, Object> variables = new LinkedHashMap<>(normalized);
        variables.put(OaAssigneeContext.FORM_DATA_VARIABLE, Map.copyOf(normalized));
        String title = title(req.title(), release.modelName());
        String businessKey = businessKey(release.releaseId(), now);

        ProcessInstance instance;
        try {
            instance = launcher.start(release
                .processDefinitionKey(), tenantId, businessKey, userId, variables, bindings, new LinkedHashSet<>(validation
                    .singleAssigneeVariables()));
        } catch (OaAssigneeUnavailableException e) {
            throw new BusinessException(e.getMessage());
        }

        long applicationId = repository.insertApplication(tenantId, release
            .modelKey(), businessKey, title, userId, profile.name(), profile.deptId(), profile.deptName(), release
                .releaseId(), release.formVersionId(), release.formVersion(), writeJson(normalized), instance
                    .getId(), now);
        attachmentService.link(tenantId, applicationId, release.modelKey(), businessKey, userId, prepared
            .attachments(), now);
        repository.insertAudit(tenantId, applicationId, instance.getId(), "SUBMIT", userId, profile
            .name(), requestId, auditDetail(requestId, release, bindings), now);

        OaLaunchSubmitResp resp = new OaLaunchSubmitResp(applicationId, businessKey, instance.getId(), false);
        repository.markDedupSucceeded(tenantId, userId, COMMAND_SUBMIT, requestId, writeJson(resp));
        return resp;
    }

    private OaLaunchSubmitResp replayOrReject(DedupRecord record) {
        if ("SUCCEEDED".equals(record.status())) {
            try {
                OaLaunchSubmitResp stored = objectMapper.readValue(record.resultJson(), OaLaunchSubmitResp.class);
                return new OaLaunchSubmitResp(stored.applicationId(), stored.businessKey(), stored
                    .processInstanceId(), true);
            } catch (Exception e) {
                throw new IllegalStateException("幂等结果快照损坏", e);
            }
        }
        if ("PROCESSING".equals(record.status())) {
            throw new BusinessException("请求正在处理中，请稍后重试");
        }
        throw new BusinessException("上一次请求未成功，请重新提交");
    }

    private ApplicantProfile requireApplicant(long tenantId, long userId) {
        ApplicantProfile profile = repository.applicantProfile(tenantId, userId);
        if (profile == null) {
            throw new BusinessException("当前用户不存在或不属于当前租户");
        }
        return profile;
    }

    private OaLaunchRelease requireLaunchable(long tenantId, long releaseId, ApplicantProfile profile) {
        OaLaunchRelease release = repository.launchRelease(tenantId, releaseId);
        if (release == null) {
            throw new BusinessException("流程未发布或已停用");
        }
        OaLaunchScope scope;
        try {
            scope = OaLaunchScope.parse(release.launchScope());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("可发起范围配置无效，请联系管理员重新发布");
        }
        if (!scope.allows(profile.userId(), profile.deptId(), profile.roleCodes())) {
            throw new BusinessException("您不在该流程的可发起范围内");
        }
        return release;
    }

    private boolean scopeAllows(String launchScope, ApplicantProfile profile) {
        try {
            return OaLaunchScope.parse(launchScope).allows(profile.userId(), profile.deptId(), profile.roleCodes());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private OaBpmnValidationResult supportableValidation(OaLaunchRelease release) {
        OaBpmnValidationResult validation = bpmnValidator.validate(release.bpmnXml().getBytes(StandardCharsets.UTF_8));
        if (!validation.valid()) {
            String message = validation.errors().isEmpty() ? "BPMN 校验未通过" : validation.errors().get(0);
            throw new BusinessException("流程定义校验失败：" + message);
        }
        if (!validation.staticAssignments().isEmpty()) {
            throw new BusinessException("流程仍使用静态审批人（" + String.join(", ", validation
                .staticAssignments()) + "），请改为 ${变量} 后重新发布");
        }
        if (!validation.groupVariables().isEmpty()) {
            throw new BusinessException("暂不支持候选组表达式：" + String.join(", ", validation.groupVariables()));
        }
        if (validation.assigneeVariables().isEmpty()) {
            throw new BusinessException("流程没有可绑定的审批人变量");
        }
        return validation;
    }

    private Map<String, OaAssigneeSpec> resolveBindings(OaBpmnValidationResult validation,
                                                        List<OaAssigneeBindingReq> requested,
                                                        Set<String> personFields) {
        Set<String> required = new LinkedHashSet<>(validation.assigneeVariables());
        Map<String, OaAssigneeBindingReq> provided = new LinkedHashMap<>();
        if (requested != null) {
            for (OaAssigneeBindingReq binding : requested) {
                String name = binding == null || binding.variableName() == null ? "" : binding.variableName().trim();
                if (name.isEmpty()) {
                    throw new BusinessException("审批人变量名不能为空");
                }
                if (!required.contains(name)) {
                    throw new BusinessException("审批人变量不在流程定义中: " + name);
                }
                if (provided.putIfAbsent(name, binding) != null) {
                    throw new BusinessException("审批人变量重复绑定: " + name);
                }
            }
        }
        Map<String, OaAssigneeSpec> specs = new LinkedHashMap<>();
        for (String name : required) {
            OaAssigneeBindingReq binding = provided.get(name);
            OaAssigneeSpec spec = binding == null ? autoSpec(name, personFields) : toSpec(name, binding, personFields);
            if (spec == null) {
                throw new BusinessException("审批人变量 " + name + " 未绑定来源");
            }
            specs.put(name, spec);
        }
        return specs;
    }

    private OaAssigneeSpec autoSpec(String variableName, Set<String> personFields) {
        if (personFields.contains(variableName)) {
            return OaAssigneeSpec.formField(variableName);
        }
        String normalized = variableName.toLowerCase(Locale.ROOT);
        if (Set.of("supervisor", "directsupervisor").contains(normalized)) {
            return OaAssigneeSpec.supervisor();
        }
        if (Set.of("deptleader", "dept_leader", "departmentleader", "department_leader").contains(normalized)) {
            return OaAssigneeSpec.departmentLeader();
        }
        return null;
    }

    private OaAssigneeSpec toSpec(String variableName, OaAssigneeBindingReq binding, Set<String> personFields) {
        OaAssigneeType type;
        try {
            type = OaAssigneeType.valueOf(binding.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("审批人变量 " + variableName + " 的来源不支持: " + binding.type());
        }
        String value = binding.value() == null || binding.value().isBlank() ? null : binding.value().trim();
        try {
            if (type == OaAssigneeType.FORM_FIELD && (value == null || !personFields.contains(value))) {
                throw new IllegalArgumentException("表单中不存在可写人员字段: " + value);
            }
            return new OaAssigneeSpec(type, value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("审批人变量 " + variableName + " 配置无效: " + e.getMessage());
        }
    }

    private OaAssigneeVariableResp suggestion(String variableName, Set<String> personFields) {
        OaAssigneeSpec spec = autoSpec(variableName, personFields);
        if (spec == null) {
            return new OaAssigneeVariableResp(variableName, null, null);
        }
        return new OaAssigneeVariableResp(variableName, spec.type().name(), spec.value());
    }

    private Set<String> personFields(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            JsonNode fields = root == null ? null : root.get("fields");
            if (fields == null || !fields.isArray()) {
                return Set.of();
            }
            Set<String> keys = new LinkedHashSet<>();
            for (JsonNode field : fields) {
                if (field == null || !field.isObject()) {
                    continue;
                }
                if (!"person".equals(field.path("type").asText("")) || field.path("hidden").asBoolean(false) || field
                    .path("readonly")
                    .asBoolean(false)) {
                    continue;
                }
                String key = field.path("key").asText("");
                if (!key.isBlank()) {
                    keys.add(key);
                }
            }
            return Set.copyOf(keys);
        } catch (Exception e) {
            throw new BusinessException("表单 schema 无法解析，请联系管理员重新发布");
        }
    }

    private static String title(String requested, String modelName) {
        String title = requested == null || requested.isBlank() ? modelName : requested.trim();
        if (title == null || title.isBlank()) {
            throw new BusinessException("申请标题不能为空");
        }
        if (title.length() > 200) {
            throw new BusinessException("申请标题不能超过 200 个字符");
        }
        return title;
    }

    private static String businessKey(long releaseId, LocalDateTime now) {
        return "OA-" + releaseId + "-" + BUSINESS_TIME.format(now) + "-" + UUID.randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, 8)
            .toUpperCase(Locale.ROOT);
    }

    private String auditDetail(String requestId, OaLaunchRelease release, Map<String, OaAssigneeSpec> bindings) {
        List<Map<String, Object>> assignees = new ArrayList<>();
        bindings.forEach((name, spec) -> assignees.add(Map.of("variableName", name, "type", spec.type()
            .name(), "value", spec.value() == null ? "" : spec.value())));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", requestId);
        detail.put("releaseId", release.releaseId());
        detail.put("modelKey", release.modelKey());
        detail.put("formVersionId", release.formVersionId());
        detail.put("assignees", assignees);
        return writeJson(detail);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public record LaunchPage(List<OaLaunchItemResp> items, long total) {

        public LaunchPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
