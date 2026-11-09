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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OaPurchaseBudgetReq(@NotNull(message = "部门不能为空") Long deptId,
                                  @NotNull(message = "预算年度不能为空") @Min(value = 2000, message = "预算年度无效") @Max(value = 2100, message = "预算年度无效") Integer budgetYear,
                                  @NotNull(message = "预算额度不能为空") @DecimalMin(value = "0", message = "预算额度不能为负") @Digits(integer = 12, fraction = 2, message = "预算额度最多两位小数") BigDecimal amount,
                                  @Size(max = 500, message = "备注不能超过 500 个字符") String remark, Integer version) {
}
