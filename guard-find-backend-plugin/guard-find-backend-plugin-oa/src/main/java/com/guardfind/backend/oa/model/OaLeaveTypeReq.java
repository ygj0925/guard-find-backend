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

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 新建/修改假期类型；修改时 code 不生效，version 用于乐观锁。
 */
public record OaLeaveTypeReq(@Size(max = 32, message = "类型编码不能超过 32 个字符") @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "类型编码只能包含字母、数字和下划线，且以字母开头") String code,
                             @NotBlank(message = "类型名称不能为空") @Size(max = 64, message = "类型名称不能超过 64 个字符") String name,
                             @NotNull(message = "请选择是否扣减余额") Boolean balanceRequired,
                             @DecimalMin(value = "0.5", message = "单次最多天数不能小于 0.5") @DecimalMax(value = "366", message = "单次最多天数不能超过 366") BigDecimal maxDaysPerRequest,
                             @Pattern(regexp = "ENABLED|DISABLED", message = "状态只能是 ENABLED 或 DISABLED") String status,
                             @Min(value = 0, message = "排序不能小于 0") @Max(value = 9999, message = "排序不能大于 9999") Integer sort,
                             @Size(max = 500, message = "说明不能超过 500 个字符") String remark, Integer version) {
}
