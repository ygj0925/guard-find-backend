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

import java.util.List;

/**
 * 受限结构化条件：一组同层比较，用 AND/OR 组合。
 *
 * @param combinator 组合方式
 * @param items      比较项，非空
 */
public record OaConditionRule(OaConditionCombinator combinator, List<OaConditionItem> items) {

    public OaConditionRule {
        if (combinator == null) {
            throw new IllegalArgumentException("条件组合方式不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("条件项不能为空");
        }
        items = List.copyOf(items);
    }
}
