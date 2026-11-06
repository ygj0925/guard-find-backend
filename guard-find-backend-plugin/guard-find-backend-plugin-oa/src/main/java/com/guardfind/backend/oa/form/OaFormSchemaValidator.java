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

package com.guardfind.backend.oa.form;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaFormValidationResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表单 schema 校验：首版只放行八种字段类型，拒绝重复 key、非法选项与超限结构。
 *
 * <pre>
 * {"version":1,"fields":[
 * {"id":"field_1","key":"amount","label":"金额","type":"number","required":true}
 * ]}
 * </pre>
 */
public class OaFormSchemaValidator {

    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_FIELDS = 100;
    public static final int MAX_OPTIONS = 50;
    public static final int MAX_ERRORS = 40;

    public static final Set<String> FIELD_TYPES = Set
        .of("text", "textarea", "number", "date", "radio", "checkbox", "attachment", "person");

    private static final Set<String> OPTION_TYPES = Set.of("radio", "checkbox");
    private static final Pattern FIELD_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final Pattern FIELD_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public OaFormValidationResult validate(String schemaJson, boolean requireFields) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return OaFormValidationResult.failure(List.of("表单 schema 不能为空"), null);
        }
        byte[] bytes = schemaJson.getBytes(StandardCharsets.UTF_8);
        String digest = digest(bytes);
        if (bytes.length > MAX_BYTES) {
            return OaFormValidationResult.failure(List.of("表单 schema 超过 1MB 限制"), digest);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            return OaFormValidationResult.failure(List.of("表单 schema 不是合法 JSON"), digest);
        }
        if (root == null || !root.isObject()) {
            return OaFormValidationResult.failure(List.of("表单 schema 必须是 JSON 对象"), digest);
        }
        JsonNode fields = root.get("fields");
        if (fields == null || !fields.isArray()) {
            return OaFormValidationResult.failure(List.of("表单 schema 缺少 fields 数组"), digest);
        }
        if (fields.size() > MAX_FIELDS) {
            return OaFormValidationResult.failure(List.of("表单字段不能超过 " + MAX_FIELDS + " 个"), digest);
        }
        if (requireFields && fields.isEmpty()) {
            return OaFormValidationResult.failure(List.of("表单至少需要一个字段才能发布"), digest);
        }

        List<String> errors = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < fields.size(); index++) {
            validateField(fields.get(index), index, keys, ids, errors);
            if (errors.size() >= MAX_ERRORS) {
                errors.add("错误过多，仅保留前 " + MAX_ERRORS + " 条");
                break;
            }
        }
        return new OaFormValidationResult(errors.isEmpty(), errors, fields.size(), digest);
    }

    private void validateField(JsonNode field, int index, Set<String> keys, Set<String> ids, List<String> errors) {
        String prefix = "第 " + (index + 1) + " 个字段";
        if (field == null || !field.isObject()) {
            errors.add(prefix + "必须是对象");
            return;
        }
        String id = text(field, "id");
        if (!id.isBlank()) {
            if (!FIELD_ID.matcher(id).matches()) {
                errors.add(prefix + "的 id 只能包含字母、数字、下划线和中划线");
            } else if (!ids.add(id)) {
                errors.add("字段 id 重复: " + id);
            }
        }
        String key = text(field, "key");
        if (key.isBlank()) {
            errors.add(prefix + "缺少 key");
        } else if (!FIELD_KEY.matcher(key).matches()) {
            errors.add(prefix + "的 key 只能包含字母、数字和下划线，且以字母开头: " + key);
        } else if (!keys.add(key)) {
            errors.add("字段 key 重复: " + key);
        }
        String label = text(field, "label");
        if (label.isBlank()) {
            errors.add(prefix + "缺少 label");
        } else if (label.length() > 100) {
            errors.add(prefix + "的 label 不能超过 100 个字符");
        }
        String type = text(field, "type");
        if (!FIELD_TYPES.contains(type)) {
            errors.add(prefix + "的类型不支持: " + (type.isBlank() ? "(空)" : type));
            return;
        }
        JsonNode required = field.get("required");
        if (required != null && !required.isBoolean()) {
            errors.add(prefix + "的 required 必须是布尔值");
        }
        if (OPTION_TYPES.contains(type)) {
            validateOptions(field.get("options"), prefix, errors);
        }
    }

    private void validateOptions(JsonNode options, String prefix, List<String> errors) {
        if (options == null || !options.isArray() || options.isEmpty()) {
            errors.add(prefix + "至少需要一个选项");
            return;
        }
        if (options.size() > MAX_OPTIONS) {
            errors.add(prefix + "的选项不能超过 " + MAX_OPTIONS + " 个");
        }
        Set<String> values = new HashSet<>();
        for (int index = 0; index < options.size(); index++) {
            JsonNode option = options.get(index);
            String optionPrefix = prefix + "第 " + (index + 1) + " 个选项";
            if (option == null || !option.isObject()) {
                errors.add(optionPrefix + "必须是对象");
                continue;
            }
            String label = text(option, "label");
            String value = text(option, "value");
            if (label.isBlank()) {
                errors.add(optionPrefix + "缺少 label");
            } else if (label.length() > 100) {
                errors.add(optionPrefix + "的 label 不能超过 100 个字符");
            }
            if (value.isBlank()) {
                errors.add(optionPrefix + "缺少 value");
            } else if (value.length() > 100) {
                errors.add(optionPrefix + "的 value 不能超过 100 个字符");
            } else if (!values.add(value)) {
                errors.add(prefix + "的选项值重复: " + value);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String digest(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
