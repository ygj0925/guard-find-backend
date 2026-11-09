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

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OaAssetCreateReq(@NotBlank(message = "请求号不能为空") @Size(max = 64, message = "请求号不能超过 64 个字符") String requestId,
                               @NotBlank(message = "名称不能为空") @Size(max = 100, message = "名称不能超过 100 个字符") String name,
                               @Size(max = 32, message = "分类不能超过 32 个字符") String category,
                               @Size(max = 200, message = "规格不能超过 200 个字符") String spec,
                               @NotNull(message = "原值不能为空") @DecimalMin(value = "0", message = "原值不能为负") @Digits(integer = 12, fraction = 2, message = "原值最多两位小数") BigDecimal originalValue,
                               @Size(max = 100, message = "存放位置不能超过 100 个字符") String location, LocalDate acquiredDate) {
}
