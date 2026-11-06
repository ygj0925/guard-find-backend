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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保存表单草稿请求。version 为乐观锁版本；schemaJson 省略时保留原草稿。
 */
public record OaFormUpdateReq(@NotBlank(message = "表单名称不能为空") @Size(max = 100, message = "表单名称不能超过 100 个字符") String name,
                              @Size(max = 500, message = "说明不能超过 500 个字符") String description, String schemaJson,
                              @NotNull(message = "版本不能为空") Integer version) {
}
