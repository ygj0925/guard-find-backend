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

import java.util.regex.Pattern;

/**
 * 会签完成条件白名单：只允许实例计数与数字做四则运算后比较。
 *
 * <p>
 * 表达式必须由 {@code ${...}} 包裹，操作数只允许
 * {@code nrOfCompletedInstances}、{@code nrOfActiveInstances}、{@code nrOfInstances}
 * 与数字，拒绝方法调用、属性访问、字符串、布尔逻辑与嵌套花括号。
 * 不写完成条件即为全票通过；比例通过由
 * {@code ${nrOfCompletedInstances / nrOfInstances >= 0.6}} 这类表达式表达。
 * </p>
 */
public final class OaCountersignConditionValidator {

    private static final String OPERAND = "(?:nrOfCompletedInstances|nrOfActiveInstances|nrOfInstances|\\d+(?:\\.\\d+)?)";
    private static final Pattern EXPRESSION = Pattern
        .compile("^\\$\\{\\s*" + OPERAND + "(?:\\s*[-+*/]\\s*" + OPERAND + ")*\\s*(?:==|!=|<=|>=|<|>)\\s*" + OPERAND + "(?:\\s*[-+*/]\\s*" + OPERAND + ")*\\s*}$");

    private OaCountersignConditionValidator() {
    }

    public static void requireSafe(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("会签完成条件不能为空");
        }
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("会签完成条件不能超过 200 个字符");
        }
        if (!EXPRESSION.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("会签完成条件只允许实例计数与数字的四则比较");
        }
    }

    public static boolean isSafe(String expression) {
        try {
            requireSafe(expression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
