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

package com.guardfind.backend.oa;

import com.guardfind.backend.oa.bpmn.OaConditionCombinator;
import com.guardfind.backend.oa.bpmn.OaConditionExpressionValidator;
import com.guardfind.backend.oa.bpmn.OaConditionItem;
import com.guardfind.backend.oa.bpmn.OaConditionOperator;
import com.guardfind.backend.oa.bpmn.OaConditionRule;
import com.guardfind.backend.oa.bpmn.OaConditionRuleCompiler;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.5 结构化条件编译：只生成受限 EL，并拒绝任何可执行内容。
 */
class OaConditionRuleCompilerTest {

    private final OaConditionRuleCompiler compiler = new OaConditionRuleCompiler();

    @Test
    void compilesAndRule() {
        String expression = compiler.compile(new OaConditionRule(OaConditionCombinator.AND, List
            .of(new OaConditionItem("amount", OaConditionOperator.GE, new BigDecimal("1000")), new OaConditionItem("days", OaConditionOperator.GT, 3))));

        assertThat(expression).isEqualTo("${amount >= 1000 && days > 3}");
        assertThat(OaConditionExpressionValidator.isSafe(expression)).isTrue();
    }

    @Test
    void compilesOrRuleWithStringsAndBoolean() {
        String expression = compiler.compile(new OaConditionRule(OaConditionCombinator.OR, List
            .of(new OaConditionItem("status", OaConditionOperator.EQ, "APPROVED"), new OaConditionItem("urgent", OaConditionOperator.EQ, true))));

        assertThat(expression).isEqualTo("${status == \"APPROVED\" || urgent == true}");
        assertThat(OaConditionExpressionValidator.isSafe(expression)).isTrue();
    }

    @Test
    void compilesInAndNotIn() {
        String in = compiler.compile(new OaConditionRule(OaConditionCombinator.AND, List
            .of(new OaConditionItem("level", OaConditionOperator.IN, List.of("A", "B")))));
        String notIn = compiler.compile(new OaConditionRule(OaConditionCombinator.AND, List
            .of(new OaConditionItem("role", OaConditionOperator.NOT_IN, List.of("admin", "root")))));

        assertThat(in).isEqualTo("${(level == \"A\" || level == \"B\")}");
        assertThat(notIn).isEqualTo("${(role != \"admin\" && role != \"root\")}");
        assertThat(OaConditionExpressionValidator.isSafe(in)).isTrue();
        assertThat(OaConditionExpressionValidator.isSafe(notIn)).isTrue();
    }

    @Test
    void escapesStringLiteral() {
        String expression = compiler.compile(new OaConditionRule(OaConditionCombinator.AND, List
            .of(new OaConditionItem("name", OaConditionOperator.EQ, "a\"b\\c"))));

        assertThat(expression).isEqualTo("${name == \"a\\\"b\\\\c\"}");
        assertThat(OaConditionExpressionValidator.isSafe(expression)).isTrue();
    }

    @Test
    void allowsDottedFieldPath() {
        String expression = compiler.compile(new OaConditionRule(OaConditionCombinator.AND, List
            .of(new OaConditionItem("form.amount", OaConditionOperator.LE, 500))));

        assertThat(expression).isEqualTo("${form.amount <= 500}");
    }

    @Test
    void rejectsInvalidFieldNames() {
        assertThatThrownBy(() -> compile("amount()", OaConditionOperator.EQ, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("字段名非法");
        assertThatThrownBy(() -> compile("1abc", OaConditionOperator.EQ, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("字段名非法");
        assertThatThrownBy(() -> compile("new", OaConditionOperator.EQ, 1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("关键字");
        assertThatThrownBy(() -> compile("true", OaConditionOperator.EQ, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("关键字");
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThatThrownBy(() -> compile("field", OaConditionOperator.EQ, new Object()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("只支持数字、布尔或字符串");
        assertThatThrownBy(() -> compile("field", OaConditionOperator.EQ, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("数值非法");
        assertThatThrownBy(() -> compile("field", OaConditionOperator.IN, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("集合不能为空");
        assertThatThrownBy(() -> compile("field", OaConditionOperator.IN, "not-a-collection"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("必须是集合");
    }

    @Test
    void rejectsEmptyRule() {
        assertThatThrownBy(() -> new OaConditionRule(OaConditionCombinator.AND, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("条件项不能为空");
    }

    @Test
    void expressionValidatorRejectsExecutableSyntax() {
        assertThat(OaConditionExpressionValidator.isSafe("${T(java.lang.Runtime).getRuntime()}")).isFalse();
        assertThat(OaConditionExpressionValidator.isSafe("${foo.bar()}")).isFalse();
        assertThat(OaConditionExpressionValidator.isSafe("${new java.io.File('/tmp')}")).isFalse();
        assertThat(OaConditionExpressionValidator.isSafe("${a == 1 ? 2 : 3}")).isFalse();
        assertThat(OaConditionExpressionValidator.isSafe("amount > 1")).isFalse();
        assertThat(OaConditionExpressionValidator.isSafe("${a == a == a}")).isFalse();
    }

    private String compile(String field, OaConditionOperator operator, Object value) {
        return compiler.compile(new OaConditionRule(OaConditionCombinator.AND, List
            .of(new OaConditionItem(field, operator, value))));
    }
}
