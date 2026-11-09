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

public record OaVehicleReq(@NotBlank(message = "请填写车牌号") @Size(max = 20, message = "车牌号不能超过 20 个字符") String plateNo,
                           @NotBlank(message = "请填写品牌型号") @Size(max = 100, message = "品牌型号不能超过 100 个字符") String model,
                           @NotNull(message = "请填写座位数") @Min(value = 2, message = "座位数至少为 2") @Max(value = 60, message = "座位数不能超过 60") Integer seats,
                           @Min(value = 0, message = "里程不能为负数") @Max(value = 9999999, message = "里程超出范围") Integer mileage,
                           @Pattern(regexp = "ENABLED|DISABLED", message = "状态无效") String status,
                           @Size(max = 500, message = "备注不能超过 500 个字符") String remark, Integer version) {
}
