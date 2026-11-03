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

package com.guardfind.backend.property;

import java.util.List;

import com.guardfind.backend.property.support.RegionNodeResp;
import com.guardfind.backend.property.support.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行政区划仓库单测:china.json 载入、级联结构、编码转名称。
 */
@DisplayName("行政区划仓库")
class RegionRepositoryTest {

    private final RegionRepository repository = new RegionRepository();

    @Test
    @DisplayName("载入全国省市区树:34 省级,树形结构完整")
    void loadsFullTree() {
        List<RegionNodeResp> tree = repository.tree();
        assertEquals(34, tree.size());
        // 北京:直辖市(省级下挂区县),含子节点
        RegionNodeResp beijing = tree.stream().filter(node -> "北京市".equals(node.getName())).findFirst().orElseThrow();
        assertNotNull(beijing.getChildren());
        assertTrue(beijing.getChildren().size() > 10);
        // 普通省份:省 → 市 → 区县三级
        RegionNodeResp hebei = tree.stream().filter(node -> "河北省".equals(node.getName())).findFirst().orElseThrow();
        RegionNodeResp shijiazhuang = hebei.getChildren()
            .stream()
            .filter(node -> "石家庄市".equals(node.getName()))
            .findFirst()
            .orElseThrow();
        assertTrue(shijiazhuang.getChildren().stream().anyMatch(node -> "长安区".equals(node.getName())));
    }

    @Test
    @DisplayName("编码转名称:省级与区县级均可解析")
    void resolvesNames() {
        assertEquals("北京市", repository.nameOf("110000"));
        assertEquals("东城区", repository.nameOf("110101"));
        assertEquals("石家庄市", repository.nameOf("130100"));
    }

    @Test
    @DisplayName("空白或未知编码返回 null")
    void blanksAndUnknown() {
        assertEquals(null, repository.nameOf(null));
        assertEquals(null, repository.nameOf(" "));
        assertEquals(null, repository.nameOf("999999"));
    }
}
