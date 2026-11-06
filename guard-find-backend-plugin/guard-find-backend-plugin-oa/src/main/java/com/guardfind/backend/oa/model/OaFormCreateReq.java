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

package com.guardfind.backend.oa.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建表单草稿请求。
 *
 * @param formKey     表单稳定标识，租户内唯一
 * @param name        表单名称
 * @param description 说明
 * @param schemaJson  草稿 schema，省略时为空表单
 */
public record OaFormCreateReq(@NotBlank(message = "表单标识不能为空") @Size(max = 64, message = "表单标识不能超过 64 个字符") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "表单标识只能包含字母、数字和下划线，且以字母开头") String formKey,
                              @NotBlank(message = "表单名称不能为空") @Size(max = 100, message = "表单名称不能超过 100 个字符") String name,
                              @Size(max = 500, message = "说明不能超过 500 个字符") String description, String schemaJson) {
}
