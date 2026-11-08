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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务钩子共用的表单约定校验与取值：发布时检查字段类型，发起时把表单值转换为受信的业务值。
 */
public final class OaBusinessFields {

    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OaBusinessFields() {
    }

    /**
     * 解析表单 schema 的顶层字段，按 key 索引；无法解析时返回 null。
     */
    public static Map<String, JsonNode> fields(String schemaJson) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(schemaJson == null ? "{}" : schemaJson);
            for (JsonNode field : root.path("fields")) {
                fields.put(field.path("key").asText(""), field);
            }
        } catch (Exception e) {
            return null;
        }
        return fields;
    }

    public static void requireField(Map<String, JsonNode> fields,
                                    String key,
                                    Set<String> types,
                                    boolean required,
                                    String label,
                                    String formName,
                                    List<String> errors) {
        JsonNode field = fields.get(key);
        String typeText = String.join("/", types);
        if (field == null) {
            if (required) {
                errors.add(formName + "缺少字段 " + key + "（" + label + "，类型 " + typeText + "）");
            }
            return;
        }
        if (!types.contains(field.path("type").asText(""))) {
            errors.add(formName + "字段 " + key + " 必须是 " + typeText + " 类型");
            return;
        }
        if (required) {
            if (!field.path("required").asBoolean(false)) {
                errors.add(formName + "字段 " + key + " 必须设为必填");
            }
            if (field.path("hidden").asBoolean(false) || field.path("readonly").asBoolean(false)) {
                errors.add(formName + "字段 " + key + " 不能隐藏或只读");
            }
        }
    }

    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public static String requireText(Object value, String label, int maxLength) {
        String text = text(value);
        if (text == null) {
            throw new BusinessException("请填写" + label);
        }
        if (text.length() > maxLength) {
            throw new BusinessException(label + "不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    /**
     * 金额必须为正、最多两位小数且不超过 {@link #MAX_AMOUNT}，统一保留两位小数。
     */
    public static BigDecimal amount(Object value, String label) {
        BigDecimal amount;
        if (value instanceof BigDecimal decimal) {
            amount = decimal;
        } else if (value instanceof Number number) {
            amount = new BigDecimal(number.toString());
        } else {
            String text = text(value);
            if (text == null) {
                throw new BusinessException("请填写" + label);
            }
            try {
                amount = new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw new BusinessException(label + "格式无效");
            }
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(label + "必须大于 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(label + "最多两位小数");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(label + "超出范围");
        }
        return amount.setScale(2);
    }

    public static LocalDate date(Object value, String label, boolean required) {
        String text = text(value);
        if (text == null) {
            if (required) {
                throw new BusinessException("请选择" + label);
            }
            return null;
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            throw new BusinessException(label + "格式无效");
        }
    }

    public static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
