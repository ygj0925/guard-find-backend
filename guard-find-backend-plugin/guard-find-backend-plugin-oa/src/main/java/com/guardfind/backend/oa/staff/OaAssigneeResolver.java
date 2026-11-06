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

package com.guardfind.backend.oa.staff;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 审批人解析：只接受 {@link OaAssigneeSpec}，所有结果必须落在本租户在职员工内。
 *
 * <p>
 * 发布校验能确定的部分（指定员工、岗位）在发布时拒绝；部门负责人、直属主管和表单字段
 * 依赖具体发起人，发布时只校验结构，执行时解析为空必须失败而不是默认同意。
 * </p>
 */
public class OaAssigneeResolver {

    private final OaStaffDirectory directory;

    public OaAssigneeResolver(OaStaffDirectory directory) {
        this.directory = directory;
    }

    public void requireActiveApplicant(OaAssigneeContext context) {
        if (!directory.activeEmployee(context.tenantId(), context.applicantId())) {
            throw new OaAssigneeUnavailableException("发起人不是本租户在职员工: " + context.applicantId());
        }
    }

    public List<Long> resolve(OaAssigneeSpec spec, OaAssigneeContext context) {
        requireActiveApplicant(context);
        List<Long> candidates = switch (spec.type()) {
            case EMPLOYEE -> resolveEmployee(spec, context.tenantId());
            case POSITION -> resolvePosition(spec, context.tenantId());
            case DEPT_LEADER -> resolveDepartmentLeader(context);
            case SUPERVISOR -> resolveSupervisor(context);
            case FORM_FIELD -> resolveFormField(spec, context);
        };
        List<Long> distinct = candidates.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (distinct.isEmpty()) {
            throw new OaAssigneeUnavailableException("审批人来源 %s 未解析到本租户在职员工".formatted(spec.type()));
        }
        return distinct;
    }

    public void validateForPublish(OaAssigneeSpec spec, long tenantId) {
        if (spec == null) {
            throw new OaAssigneeUnavailableException("审批人配置不能为空");
        }
        switch (spec.type()) {
            case EMPLOYEE -> requireActiveEmployee(tenantId, spec.userId(), "指定审批人");
            case POSITION -> {
                if (!directory.enabledPosition(tenantId, spec.value())) {
                    throw new OaAssigneeUnavailableException("岗位不存在或已停用: " + spec.value());
                }
                if (directory.activeUsersByPosition(tenantId, spec.value()).isEmpty()) {
                    throw new OaAssigneeUnavailableException("岗位下没有在职员工: " + spec.value());
                }
            }
            default -> {
                // 部门负责人、直属主管、表单字段依赖具体发起人，执行时再校验
            }
        }
    }

    public void validateForPublish(Collection<OaAssigneeSpec> specs, long tenantId) {
        if (specs == null || specs.isEmpty()) {
            throw new OaAssigneeUnavailableException("流程未配置任何审批人");
        }
        specs.forEach(spec -> validateForPublish(spec, tenantId));
    }

    private List<Long> resolveEmployee(OaAssigneeSpec spec, long tenantId) {
        requireActiveEmployee(tenantId, spec.userId(), "指定审批人");
        return List.of(spec.userId());
    }

    private List<Long> resolvePosition(OaAssigneeSpec spec, long tenantId) {
        if (!directory.enabledPosition(tenantId, spec.value())) {
            throw new OaAssigneeUnavailableException("岗位不存在或已停用: " + spec.value());
        }
        return directory.activeUsersByPosition(tenantId, spec.value());
    }

    private List<Long> resolveDepartmentLeader(OaAssigneeContext context) {
        Long departmentId = directory.departmentId(context.tenantId(), context.applicantId());
        if (departmentId == null) {
            throw new OaAssigneeUnavailableException("发起人未归属任何部门");
        }
        return directory.departmentLeaders(context.tenantId(), departmentId);
    }

    private List<Long> resolveSupervisor(OaAssigneeContext context) {
        Long supervisorId = directory.supervisorId(context.tenantId(), context.applicantId());
        if (supervisorId == null) {
            throw new OaAssigneeUnavailableException("发起人未配置直属主管");
        }
        requireActiveEmployee(context.tenantId(), supervisorId, "直属主管");
        return List.of(supervisorId);
    }

    private List<Long> resolveFormField(OaAssigneeSpec spec, OaAssigneeContext context) {
        String fieldKey = spec.value();
        if (!context.formData().containsKey(fieldKey)) {
            throw new OaAssigneeUnavailableException("表单缺少人员字段: " + fieldKey);
        }
        List<Long> userIds = parseUserIds(context.formData().get(fieldKey), fieldKey);
        if (userIds.isEmpty()) {
            throw new OaAssigneeUnavailableException("表单人员字段为空: " + fieldKey);
        }
        userIds.forEach(userId -> requireActiveEmployee(context.tenantId(), userId, "表单人员 " + fieldKey));
        return userIds;
    }

    private void requireActiveEmployee(long tenantId, long userId, String label) {
        if (!directory.activeEmployee(tenantId, userId)) {
            throw new OaAssigneeUnavailableException(label + " 不是本租户在职员工: " + userId);
        }
    }

    private static List<Long> parseUserIds(Object raw, String fieldKey) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        if (raw instanceof Iterable<?> iterable) {
            iterable.forEach(item -> userIds.add(parseUserId(item, fieldKey)));
            return List.copyOf(userIds);
        }
        if (raw instanceof CharSequence text) {
            for (String part : text.toString().split(",")) {
                if (!part.isBlank()) {
                    userIds.add(parseUserId(part, fieldKey));
                }
            }
            return List.copyOf(userIds);
        }
        userIds.add(parseUserId(raw, fieldKey));
        return List.copyOf(userIds);
    }

    private static Long parseUserId(Object raw, String fieldKey) {
        if (raw instanceof Number number) {
            long userId = number.longValue();
            if (userId > 0) {
                return userId;
            }
        } else if (raw != null) {
            try {
                long userId = Long.parseLong(raw.toString().trim());
                if (userId > 0) {
                    return userId;
                }
            } catch (NumberFormatException ignored) {
                // 统一在下方报错
            }
        }
        throw new OaAssigneeUnavailableException("表单人员字段格式非法: " + fieldKey);
    }
}
