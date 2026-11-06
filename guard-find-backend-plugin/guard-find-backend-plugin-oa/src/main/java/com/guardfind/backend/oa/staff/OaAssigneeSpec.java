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

/**
 * 审批人配置。发布和执行都只接受该结构，不把用户输入当作任意 EL 执行。
 *
 * @param type  审批人来源
 * @param value 指定员工 ID、岗位编码或表单字段 Key；部门负责人和直属主管为空
 */
public record OaAssigneeSpec(OaAssigneeType type, String value) {

    public OaAssigneeSpec {
        if (type == null) {
            throw new IllegalArgumentException("审批人来源不能为空");
        }
        value = value == null ? null : value.trim();
        boolean hasValue = value != null && !value.isEmpty();
        if (requiresValue(type) && !hasValue) {
            throw new IllegalArgumentException("审批人来源 %s 需要配置取值".formatted(type));
        }
        if (!requiresValue(type) && hasValue) {
            throw new IllegalArgumentException("审批人来源 %s 不接受取值".formatted(type));
        }
        if (type == OaAssigneeType.EMPLOYEE) {
            userId(value);
        }
    }

    public static OaAssigneeSpec employee(long userId) {
        return new OaAssigneeSpec(OaAssigneeType.EMPLOYEE, Long.toString(userId));
    }

    public static OaAssigneeSpec position(String code) {
        return new OaAssigneeSpec(OaAssigneeType.POSITION, code);
    }

    public static OaAssigneeSpec departmentLeader() {
        return new OaAssigneeSpec(OaAssigneeType.DEPT_LEADER, null);
    }

    public static OaAssigneeSpec supervisor() {
        return new OaAssigneeSpec(OaAssigneeType.SUPERVISOR, null);
    }

    public static OaAssigneeSpec formField(String fieldKey) {
        return new OaAssigneeSpec(OaAssigneeType.FORM_FIELD, fieldKey);
    }

    public long userId() {
        return userId(value);
    }

    private static boolean requiresValue(OaAssigneeType type) {
        return type == OaAssigneeType.EMPLOYEE || type == OaAssigneeType.POSITION || type == OaAssigneeType.FORM_FIELD;
    }

    private static long userId(String raw) {
        long userId;
        try {
            userId = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("指定审批人必须是用户 ID");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("指定审批人必须是用户 ID");
        }
        return userId;
    }
}
