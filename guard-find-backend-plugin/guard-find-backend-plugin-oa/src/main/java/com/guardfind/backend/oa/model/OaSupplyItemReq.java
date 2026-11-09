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

public record OaSupplyItemReq(@NotBlank(message = "编码不能为空") @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "编码只能包含字母、数字、下划线和短横线，且不超过 32 位") String code,
                              @NotBlank(message = "名称不能为空") @Size(max = 100, message = "名称不能超过 100 个字符") String name,
                              @Size(max = 200, message = "规格不能超过 200 个字符") String spec,
                              @NotBlank(message = "单位不能为空") @Size(max = 16, message = "单位不能超过 16 个字符") String unit,
                              @NotNull(message = "安全库存不能为空") @Min(value = 0, message = "安全库存不能为负") @Max(value = 99999, message = "安全库存不能超过 99999") Integer safetyStock,
                              @Pattern(regexp = "ENABLED|DISABLED", message = "状态只能是 ENABLED 或 DISABLED") String status,
                              Integer version) {
}
