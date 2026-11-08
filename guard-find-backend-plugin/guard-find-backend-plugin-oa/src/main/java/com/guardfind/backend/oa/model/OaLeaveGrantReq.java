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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 发放或调整额度：days 为增量，负数表示收回，调整后总额不能低于已扣减与占用之和。
 */
public record OaLeaveGrantReq(@NotNull(message = "员工不能为空") Long userId, @NotNull(message = "假期类型不能为空") Long leaveTypeId,
                              @NotNull(message = "年度不能为空") @Min(value = 2000, message = "年度无效") @Max(value = 2100, message = "年度无效") Integer periodYear,
                              @NotNull(message = "调整天数不能为空") @DecimalMin(value = "-366", message = "调整天数超出范围") @DecimalMax(value = "366", message = "调整天数超出范围") BigDecimal days,
                              @Size(max = 500, message = "说明不能超过 500 个字符") String remark) {
}
