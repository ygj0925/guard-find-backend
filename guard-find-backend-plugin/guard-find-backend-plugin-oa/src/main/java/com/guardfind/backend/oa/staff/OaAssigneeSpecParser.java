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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 从 BPMN 审批人属性中解析可静态校验的来源。
 *
 * <p>
 * 约定前缀：employee:101、position:FINANCE、formField:approvers、deptLeader、supervisor。
 * 不带前缀的普通值（如 ${approver}）视为运行时变量，发布阶段不做员工校验。
 * </p>
 */
public final class OaAssigneeSpecParser {

    private OaAssigneeSpecParser() {
    }

    public static Optional<OaAssigneeSpec> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = token.trim();
        if (value.startsWith("${") || value.indexOf(':') < 0 && !"deptLeader".equals(value) && !"supervisor"
            .equals(value)) {
            return Optional.empty();
        }
        if ("deptLeader".equals(value)) {
            return Optional.of(OaAssigneeSpec.departmentLeader());
        }
        if ("supervisor".equals(value)) {
            return Optional.of(OaAssigneeSpec.supervisor());
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            return Optional.empty();
        }
        String type = value.substring(0, separator);
        String specValue = value.substring(separator + 1).trim();
        return switch (type) {
            case "employee" -> Optional.of(OaAssigneeSpec.employee(parseUserId(specValue)));
            case "position" -> Optional.of(OaAssigneeSpec.position(specValue));
            case "formField" -> Optional.of(OaAssigneeSpec.formField(specValue));
            default -> Optional.empty();
        };
    }

    public static List<OaAssigneeSpec> parseAll(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<OaAssigneeSpec> specs = new ArrayList<>();
        for (String token : raw.split(",")) {
            parse(token).ifPresent(specs::add);
        }
        return List.copyOf(specs);
    }

    private static long parseUserId(String raw) {
        try {
            long userId = Long.parseLong(raw);
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
            // 统一在下方报错
        }
        throw new IllegalArgumentException("employee: 后必须是用户 ID");
    }
}
