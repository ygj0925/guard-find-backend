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

/**
 * 结构化条件的一项比较。值只能是数字、布尔、字符串；IN/NOT_IN 使用集合。
 *
 * @param field    表单字段或流程变量名
 * @param operator 比较操作
 * @param value    比较值
 */
public record OaConditionItem(String field, OaConditionOperator operator, Object value) {

    public OaConditionItem {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("条件字段不能为空");
        }
        if (operator == null) {
            throw new IllegalArgumentException("条件操作不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("条件值不能为空");
        }
    }
}
