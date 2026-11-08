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

package com.guardfind.backend.oa.business;

import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按流程模型业务类型分发钩子；GENERIC 或未注册类型不做任何业务处理。
 */
@Component
public class OaBusinessHooks {

    public static final String GENERIC = "GENERIC";

    private final Map<String, OaBusinessHook> hooks = new LinkedHashMap<>();
    private final OaLaunchRepository launchRepository;

    public OaBusinessHooks(List<OaBusinessHook> hooks, OaLaunchRepository launchRepository) {
        for (OaBusinessHook hook : hooks) {
            if (this.hooks.putIfAbsent(hook.businessType(), hook) != null) {
                throw new IllegalStateException("业务类型钩子重复注册: " + hook.businessType());
            }
        }
        this.launchRepository = launchRepository;
    }

    public OaBusinessHook find(String businessType) {
        return businessType == null ? null : hooks.get(businessType);
    }

    public List<String> validateForm(String businessType, String schemaJson) {
        OaBusinessHook hook = find(businessType);
        return hook == null ? List.of() : hook.validateForm(schemaJson);
    }

    public Map<String, OaAssigneeSpec> defaultAssignees(String businessType) {
        OaBusinessHook hook = find(businessType);
        return hook == null ? Map.of() : hook.defaultAssignees();
    }

    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        if (hooks.isEmpty()) {
            return;
        }
        OaBusinessHook hook = find(launchRepository.applicationBusinessType(tenantId, applicationId));
        if (hook != null) {
            hook.finished(tenantId, applicationId, status, operatorId, now);
        }
    }
}
