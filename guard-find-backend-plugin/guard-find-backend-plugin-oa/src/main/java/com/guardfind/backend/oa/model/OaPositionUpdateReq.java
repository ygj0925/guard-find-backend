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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新岗位请求。编码不可变更，避免已发布 BPMN 中的 position:CODE 失效。
 */
public record OaPositionUpdateReq(@NotBlank(message = "岗位名称不能为空") @Size(max = 64, message = "岗位名称不能超过 64 个字符") String name,
                                  @NotNull(message = "排序不能为空") @Min(value = 0, message = "排序不能小于 0") @Max(value = 9999, message = "排序不能大于 9999") Integer sort,
                                  @NotBlank(message = "岗位状态不能为空") @Pattern(regexp = "^(ENABLED|DISABLED)$", message = "岗位状态只能是 ENABLED 或 DISABLED") String status,
                                  @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号非法") Integer version) {
}
