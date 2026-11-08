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

import java.util.Map;

/**
 * 业务钩子发起前的计算结果。
 *
 * @param variables 覆盖写入流程变量的服务端计算值（不信任前端同名字段）
 * @param state     钩子自身在 submitted 阶段需要的中间结果
 */
public record OaBusinessPreparation(Map<String, Object> variables, Object state) {

    public OaBusinessPreparation {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
