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

import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaAssigneeSpecParser;
import com.guardfind.backend.oa.staff.OaAssigneeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 9.8 静态审批人来源解析：前缀约定、运行时变量跳过与非法员工 ID。
 */
class OaAssigneeSpecParserTest {

    @Test
    void parsesStaticSources() {
        assertThat(OaAssigneeSpecParser.parse("employee:101"))
            .contains(new OaAssigneeSpec(OaAssigneeType.EMPLOYEE, "101"));
        assertThat(OaAssigneeSpecParser.parse("position:FINANCE").orElseThrow().type())
            .isEqualTo(OaAssigneeType.POSITION);
        assertThat(OaAssigneeSpecParser.parse("formField:approvers").orElseThrow().value()).isEqualTo("approvers");
        assertThat(OaAssigneeSpecParser.parse("deptLeader").orElseThrow().type()).isEqualTo(OaAssigneeType.DEPT_LEADER);
        assertThat(OaAssigneeSpecParser.parse("supervisor").orElseThrow().type()).isEqualTo(OaAssigneeType.SUPERVISOR);
    }

    @Test
    void skipsRuntimeVariablesAndUnknownPrefixes() {
        assertThat(OaAssigneeSpecParser.parse("${approver}")).isEmpty();
        assertThat(OaAssigneeSpecParser.parse("manager")).isEmpty();
        assertThat(OaAssigneeSpecParser.parse("group:hr")).isEmpty();
        assertThat(OaAssigneeSpecParser.parse(" ")).isEmpty();
    }

    @Test
    void rejectsInvalidEmployeeId() {
        assertThatThrownBy(() -> OaAssigneeSpecParser.parse("employee:abc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("用户 ID");
        assertThatThrownBy(() -> OaAssigneeSpecParser.parse("employee:0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesCommaSeparatedAssignments() {
        assertThat(OaAssigneeSpecParser.parseAll("employee:101, position:FINANCE, ${runtime}, supervisor"))
            .extracting(OaAssigneeSpec::type)
            .containsExactly(OaAssigneeType.EMPLOYEE, OaAssigneeType.POSITION, OaAssigneeType.SUPERVISOR);
        assertThat(OaAssigneeSpecParser.parseAll(null)).isEmpty();
    }
}
