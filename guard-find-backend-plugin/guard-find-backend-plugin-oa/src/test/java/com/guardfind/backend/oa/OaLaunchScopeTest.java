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

import com.guardfind.backend.oa.launch.OaLaunchScope;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 10.1 可发起范围：结构白名单、并集匹配与发布时规范化。
 */
class OaLaunchScopeTest {

    @Test
    void blankScopeMeansUnrestricted() {
        assertThat(OaLaunchScope.parse(null).unrestricted()).isTrue();
        assertThat(OaLaunchScope.parse("  ").unrestricted()).isTrue();
        assertThat(OaLaunchScope.parse("{}").unrestricted()).isTrue();
        assertThat(OaLaunchScope.parse("{}").toJsonOrNull()).isNull();
        assertThat(OaLaunchScope.parse(null).allows(1L, 2L, Set.of("employee"))).isTrue();
    }

    @Test
    void matchesUserDeptAndRoleAsUnion() {
        OaLaunchScope scope = OaLaunchScope.parse("""
            {"users":[101],"depts":[10],"roles":["hr"]}
            """);

        assertThat(scope.unrestricted()).isFalse();
        assertThat(scope.allows(101L, 20L, Set.of())).isTrue();
        assertThat(scope.allows(999L, 10L, Set.of())).isTrue();
        assertThat(scope.allows(999L, 20L, Set.of("hr"))).isTrue();
        assertThat(scope.allows(999L, 20L, Set.of("employee"))).isFalse();
        assertThat(scope.allows(null, null, null)).isFalse();
    }

    @Test
    void normalizesAndDeduplicatesEntries() {
        OaLaunchScope scope = OaLaunchScope.parse("""
            {"users":[101,101],"depts":[],"roles":["hr","hr"]}
            """);

        assertThat(scope.users()).containsExactly(101L);
        assertThat(scope.depts()).isEmpty();
        assertThat(scope.roles()).containsExactly("hr");
        assertThat(scope.toJsonOrNull()).isEqualTo("{\"users\":[101],\"roles\":[\"hr\"]}");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidTypes() {
        assertRejected("{\"teams\":[1]}", "不支持字段");
        assertRejected("[]", "必须是 JSON 对象");
        assertRejected("{not json", "必须是合法 JSON");
        assertRejected("{\"users\":101}", "users 必须是数组");
        assertRejected("{\"users\":[\"101\"]}", "users 只能包含整数 ID");
        assertRejected("{\"users\":[0]}", "users 只能包含正整数 ID");
        assertRejected("{\"depts\":[-1]}", "depts 只能包含正整数 ID");
        assertRejected("{\"roles\":[1]}", "roles 只能包含角色编码字符串");
        assertRejected("{\"roles\":[\"  \"]}", "roles 不能包含空编码");
    }

    @Test
    void rejectsOversizedLists() {
        String users = IntStream.rangeClosed(1, 201).mapToObj(Integer::toString).collect(Collectors.joining(","));

        assertRejected("{\"users\":[" + users + "]}", "不能超过 200 项");
    }

    private static void assertRejected(String json, String fragment) {
        assertThatThrownBy(() -> OaLaunchScope.parse(json)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(fragment);
    }
}
