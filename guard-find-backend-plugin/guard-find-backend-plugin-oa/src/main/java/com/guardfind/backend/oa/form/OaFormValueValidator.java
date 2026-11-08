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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务端表单值校验：以发布版本的 schema 为准，只接收可见、可写字段。
 *
 * <p>
 * 隐藏字段不参与校验也不落库；只读字段由系统带入，首版没有带入值时忽略提交值。
 * 未知字段直接丢弃，避免把任意 JSON 写进申请快照。
 * </p>
 */
public class OaFormValueValidator {

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final int MAX_TEXT = 500;
    private static final int MAX_TEXTAREA = 5000;
    private static final int MAX_ATTACHMENTS = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public OaFormValueValidation validate(String schemaJson, Map<String, Object> values) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> normalized = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = schemaJson == null ? null : objectMapper.readTree(schemaJson);
        } catch (Exception e) {
            return new OaFormValueValidation(false, Map.of("*", "表单 schema 不是合法 JSON"), Map.of());
        }
        JsonNode fields = root == null ? null : root.get("fields");
        if (fields == null || !fields.isArray()) {
            return new OaFormValueValidation(false, Map.of("*", "表单 schema 缺少 fields 数组"), Map.of());
        }
        Map<String, Object> input = values == null ? Map.of() : values;
        for (JsonNode field : fields) {
            if (field == null || !field.isObject() || field.path("hidden").asBoolean(false) || field.path("readonly")
                .asBoolean(false)) {
                continue;
            }
            String key = field.path("key").asText("");
            String label = field.path("label").asText(key);
            String type = field.path("type").asText("");
            boolean required = field.path("required").asBoolean(false);
            Object raw = input.get(key);
            if (isEmpty(raw)) {
                if (required) {
                    errors.put(key, requiredMessage(type, label));
                }
                continue;
            }
            try {
                Object value = normalize(type, raw, field);
                if (value instanceof List<?> rows && rows.isEmpty() && "table".equals(type)) {
                    if (required) {
                        errors.put(key, requiredMessage(type, label));
                    }
                } else if (value != null) {
                    normalized.put(key, value);
                }
            } catch (IllegalArgumentException e) {
                errors.put(key, e.getMessage());
            }
        }
        return new OaFormValueValidation(errors.isEmpty(), errors, normalized);
    }

    private static Object normalize(String type, Object raw, JsonNode field) {
        return switch (type) {
            case "text" -> text(raw, MAX_TEXT, "不能超过 " + MAX_TEXT + " 个字符");
            case "textarea" -> text(raw, MAX_TEXTAREA, "不能超过 " + MAX_TEXTAREA + " 个字符");
            case "number" -> number(raw);
            case "date" -> date(raw);
            case "radio" -> radio(raw, field);
            case "checkbox" -> checkbox(raw, field);
            case "person" -> person(raw);
            case "attachment" -> attachment(raw);
            case "table" -> table(raw, field);
            default -> null;
        };
    }

    /**
     * 明细表：整行为空的行直接丢弃；单元格按列类型规范化，未知列丢弃，错误带行号。
     */
    private static List<Map<String, Object>> table(Object raw, JsonNode field) {
        List<Object> items = items(raw, "明细必须是行数组");
        JsonNode maxRowsNode = field.get("maxRows");
        int maxRows = maxRowsNode != null && maxRowsNode.canConvertToInt()
            ? maxRowsNode.asInt()
            : OaFormSchemaValidator.DEFAULT_TABLE_ROWS;
        JsonNode columns = field.path("columns");
        List<Map<String, Object>> rows = new ArrayList<>();
        int rowNo = 0;
        for (Object item : items) {
            rowNo++;
            if (!(item instanceof Map<?, ?> cells)) {
                throw new IllegalArgumentException("第 " + rowNo + " 行格式非法");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            boolean blank = true;
            for (JsonNode column : columns) {
                if (!isEmpty(cells.get(column.path("key").asText("")))) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                continue;
            }
            for (JsonNode column : columns) {
                String columnKey = column.path("key").asText("");
                String columnLabel = column.path("label").asText(columnKey);
                String columnType = column.path("type").asText("");
                Object cell = cells.get(columnKey);
                if (isEmpty(cell)) {
                    if (column.path("required").asBoolean(false)) {
                        throw new IllegalArgumentException("第 " + rowNo + " 行" + requiredMessage(columnType, columnLabel));
                    }
                    continue;
                }
                if (!OaFormSchemaValidator.COLUMN_TYPES.contains(columnType)) {
                    continue;
                }
                try {
                    row.put(columnKey, normalize(columnType, cell, column));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("第 " + rowNo + " 行" + columnLabel + e.getMessage());
                }
            }
            rows.add(Collections.unmodifiableMap(row));
            if (rows.size() > maxRows) {
                throw new IllegalArgumentException("明细不能超过 " + maxRows + " 行");
            }
        }
        return List.copyOf(rows);
    }

    private static String text(Object raw, int max, String message) {
        if (!(raw instanceof CharSequence text)) {
            throw new IllegalArgumentException("必须是文本");
        }
        String value = text.toString();
        if (value.length() > max) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static BigDecimal number(Object raw) {
        if (raw instanceof Number number) {
            if (number instanceof Double value && !Double.isFinite(value)) {
                throw new IllegalArgumentException("必须是有限数字");
            }
            if (number instanceof Float value && !Float.isFinite(value)) {
                throw new IllegalArgumentException("必须是有限数字");
            }
            return new BigDecimal(number.toString());
        }
        if (raw instanceof CharSequence text) {
            try {
                return new BigDecimal(text.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("必须是数字");
            }
        }
        throw new IllegalArgumentException("必须是数字");
    }

    private static String date(Object raw) {
        if (!(raw instanceof CharSequence text) || !DATE.matcher(text.toString()).matches()) {
            throw new IllegalArgumentException("日期格式必须是 YYYY-MM-DD");
        }
        try {
            return LocalDate.parse(text.toString()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期不是有效日期");
        }
    }

    private static String radio(Object raw, JsonNode field) {
        if (!(raw instanceof CharSequence text)) {
            throw new IllegalArgumentException("请选择有效选项");
        }
        String value = text.toString();
        if (!optionValues(field).contains(value)) {
            throw new IllegalArgumentException("请选择有效选项");
        }
        return value;
    }

    private static List<String> checkbox(Object raw, JsonNode field) {
        List<Object> items = items(raw, "多选值必须是数组");
        List<String> allowed = optionValues(field);
        List<String> values = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof CharSequence text) || !allowed.contains(text.toString())) {
                throw new IllegalArgumentException("请选择有效选项");
            }
            values.add(text.toString());
        }
        return List.copyOf(values);
    }

    private static List<Long> person(Object raw) {
        List<Object> items;
        if (raw instanceof Number) {
            items = List.of(raw);
        } else if (raw instanceof CharSequence text) {
            items = new ArrayList<>(List.of(text.toString().split(",")));
        } else {
            items = items(raw, "人员字段必须是员工 ID");
        }
        List<Long> userIds = new ArrayList<>();
        for (Object item : items) {
            long userId = positiveLong(item);
            if (!userIds.contains(userId)) {
                userIds.add(userId);
            }
        }
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("人员字段不能为空");
        }
        return List.copyOf(userIds);
    }

    private static List<Object> attachment(Object raw) {
        List<Object> items = items(raw, "附件必须是数组");
        if (items.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("附件不能超过 " + MAX_ATTACHMENTS + " 个");
        }
        List<Object> files = new ArrayList<>();
        Set<Long> fileIds = new LinkedHashSet<>();
        for (Object item : items) {
            if (item instanceof CharSequence) {
                throw new IllegalArgumentException("附件未完成上传");
            }
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("附件格式非法");
            }
            long fileId = attachmentFileId(map.get("fileId"));
            if (!fileIds.add(fileId)) {
                throw new IllegalArgumentException("同一附件不能重复上传");
            }
            Map<String, Object> file = new LinkedHashMap<>();
            Object uid = map.get("uid");
            if (uid instanceof CharSequence text) {
                file.put("uid", text.toString());
            }
            Object name = map.get("name");
            if (name instanceof CharSequence text) {
                file.put("name", text.toString());
            }
            file.put("fileId", fileId);
            files.add(Map.copyOf(file));
        }
        return List.copyOf(files);
    }

    private static long attachmentFileId(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            if (value > 0 && number.doubleValue() == (double)value) {
                return value;
            }
        } else if (raw instanceof CharSequence text) {
            try {
                long value = Long.parseLong(text.toString().trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 统一在下方报错
            }
        }
        throw new IllegalArgumentException("附件未完成上传");
    }

    private static List<Object> items(Object raw, String message) {
        if (raw instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (raw instanceof Object[] array) {
            return new ArrayList<>(List.of(array));
        }
        throw new IllegalArgumentException(message);
    }

    private static List<String> optionValues(JsonNode field) {
        JsonNode options = field.get("options");
        List<String> values = new ArrayList<>();
        if (options != null && options.isArray()) {
            for (JsonNode option : options) {
                String value = option.path("value").asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static long positiveLong(Object raw) {
        if (raw instanceof Number number) {
            long value = number.longValue();
            if (value > 0) {
                return value;
            }
        } else if (raw != null) {
            try {
                long value = Long.parseLong(raw.toString().trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 统一在下方报错
            }
        }
        throw new IllegalArgumentException("必须是有效的员工 ID");
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Object[] array) {
            return array.length == 0;
        }
        return false;
    }

    private static String requiredMessage(String type, String label) {
        return switch (type) {
            case "checkbox", "radio" -> "请选择" + label;
            case "attachment" -> "请上传" + label;
            default -> "请填写" + label;
        };
    }

    public record OaFormValueValidation(boolean valid, Map<String, String> errors, Map<String, Object> normalized) {

        public OaFormValueValidation {
            errors = errors == null ? Map.of() : Map.copyOf(errors);
            normalized = normalized == null ? Map.of() : Map.copyOf(normalized);
        }
    }
}
