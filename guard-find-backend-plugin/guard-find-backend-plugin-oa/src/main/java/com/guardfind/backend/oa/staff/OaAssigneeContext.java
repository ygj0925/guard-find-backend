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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批人解析上下文：租户、发起人和表单快照。
 *
 * @param tenantId    租户 ID
 * @param applicantId 发起人用户 ID
 * @param formData    表单数据（受控人员字段从这里读取）
 */
public record OaAssigneeContext(long tenantId, long applicantId, Map<String, Object> formData) {

    public static final String FORM_DATA_VARIABLE = "oaFormData";

    public OaAssigneeContext {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("租户不能为空");
        }
        if (applicantId <= 0) {
            throw new IllegalArgumentException("发起人不能为空");
        }
        formData = formData == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(formData));
    }
}
