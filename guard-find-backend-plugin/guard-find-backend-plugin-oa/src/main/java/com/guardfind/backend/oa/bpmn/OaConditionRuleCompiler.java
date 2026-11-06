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

package com.guardfind.backend.oa.bpmn;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把结构化条件编译成受限 EL，并在返回前用 {@link OaConditionExpressionValidator} 再校验。
 *
 * <p>
 * 字段名只允许标识符与点路径；值只允许数字、布尔、字符串或它们的集合；不拼接任意文本。
 * </p>
 */
public class OaConditionRuleCompiler {

    private static final Pattern FIELD_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private static final Set<String> RESERVED = Set
        .of("and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge", "div", "mod", "empty", "null", "instanceof", "new", "true", "false");

    public String compile(OaConditionRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("条件规则不能为空");
        }
        String joiner = rule.combinator() == OaConditionCombinator.AND ? " && " : " || ";
        List<String> parts = new ArrayList<>();
        for (OaConditionItem item : rule.items()) {
            parts.add(compileItem(item));
        }
        String expression = "${" + String.join(joiner, parts) + "}";
        OaConditionExpressionValidator.requireSafe(expression);
        return expression;
    }

    private String compileItem(OaConditionItem item) {
        String field = requireField(item.field());
        return switch (item.operator()) {
            case EQ -> "%s == %s".formatted(field, literal(item.value()));
            case NE -> "%s != %s".formatted(field, literal(item.value()));
            case GT -> "%s > %s".formatted(field, literal(item.value()));
            case GE -> "%s >= %s".formatted(field, literal(item.value()));
            case LT -> "%s < %s".formatted(field, literal(item.value()));
            case LE -> "%s <= %s".formatted(field, literal(item.value()));
            case IN -> membership(field, collection(item.value()), true);
            case NOT_IN -> membership(field, collection(item.value()), false);
        };
    }

    private static String membership(String field, List<Object> values, boolean in) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("条件集合不能为空");
        }
        String joiner = in ? " || " : " && ";
        String operator = in ? " == " : " != ";
        List<String> parts = values.stream().map(value -> field + operator + literal(value)).toList();
        return "(" + String.join(joiner, parts) + ")";
    }

    private static String requireField(String field) {
        String trimmed = field == null ? "" : field.trim();
        if (!FIELD_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("条件字段名非法: " + field);
        }
        if (RESERVED.contains(trimmed.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("条件字段名不能使用关键字: " + field);
        }
        return trimmed;
    }

    private static List<Object> collection(Object value) {
        List<Object> values = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(values::add);
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
        } else {
            throw new IllegalArgumentException("IN/NOT_IN 条件值必须是集合");
        }
        values.forEach(OaConditionRuleCompiler::literal);
        return values;
    }

    private static String literal(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
                throw new IllegalArgumentException("条件数值非法");
            }
            return new BigDecimal(number.toString()).toPlainString();
        }
        if (value instanceof CharSequence text) {
            return "\"" + text.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        throw new IllegalArgumentException("条件值只支持数字、布尔或字符串: " + value.getClass().getSimpleName());
    }
}
