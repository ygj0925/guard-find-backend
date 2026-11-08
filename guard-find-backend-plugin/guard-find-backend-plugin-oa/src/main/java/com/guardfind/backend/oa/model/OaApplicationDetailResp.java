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

package com.guardfind.backend.oa.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的申请详情：包含提交时的表单快照与审批轨迹。
 */
public record OaApplicationDetailResp(long id, String businessType, String businessKey, String title, String status,
                                      Long applicantId, String applicantName, Long applicantDeptId,
                                      String applicantDeptName, long releaseId, Integer releaseVersion,
                                      Long formVersionId, Integer formVersion, String formName, String formKey,
                                      String modelName, String modelKey, String schemaJson,
                                      Map<String, Object> formData, String processInstanceId,
                                      LocalDateTime submittedTime, LocalDateTime finishedTime, LocalDateTime createTime,
                                      List<OaApplicationTimelineResp> timeline, Map<String, String> formLabels) {

    public OaApplicationDetailResp {
        formData = normalizeFormData(formData);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        formLabels = formLabels == null ? Map.of() : Map.copyOf(formLabels);
    }

    /**
     * 附件 fileId 是雪花 ID，超出 JS 安全整数范围；响应时统一转成字符串，
     * 避免前端 JSON 解析丢精度后按错误 ID 下载。
     */
    private static Map<String, Object> normalizeFormData(Map<String, Object> formData) {
        if (formData == null || formData.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        formData.forEach((key, value) -> values.put(key, normalizeValue(value)));
        return Map.copyOf(values);
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key instanceof String name) {
                    Object normalized = "fileId".equals(name) && item instanceof Number number
                        ? Long.toString(number.longValue())
                        : normalizeValue(item);
                    nested.put(name, normalized);
                }
            });
            return Map.copyOf(nested);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OaApplicationDetailResp::normalizeValue).toList();
        }
        return value;
    }

    public OaApplicationDetailResp withTimeline(List<OaApplicationTimelineResp> timeline) {
        return new OaApplicationDetailResp(id, businessType, businessKey, title, status, applicantId, applicantName, applicantDeptId, applicantDeptName, releaseId, releaseVersion, formVersionId, formVersion, formName, formKey, modelName, modelKey, schemaJson, formData, processInstanceId, submittedTime, finishedTime, createTime, timeline, formLabels);
    }

    public OaApplicationDetailResp withFormLabels(Map<String, String> formLabels) {
        return new OaApplicationDetailResp(id, businessType, businessKey, title, status, applicantId, applicantName, applicantDeptId, applicantDeptName, releaseId, releaseVersion, formVersionId, formVersion, formName, formKey, modelName, modelKey, schemaJson, formData, processInstanceId, submittedTime, finishedTime, createTime, timeline, formLabels);
    }
}
