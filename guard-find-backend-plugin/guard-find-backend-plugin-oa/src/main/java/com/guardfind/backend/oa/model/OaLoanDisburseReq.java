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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OaLoanDisburseReq(@NotNull(message = "发放金额不能为空") @DecimalMin(value = "0.01", message = "发放金额必须大于 0") @Digits(integer = 12, fraction = 2, message = "发放金额最多两位小数") BigDecimal amount,
                                @NotNull(message = "发放日期不能为空") LocalDate disburseDate,
                                @NotNull(message = "发放方式不能为空") @Pattern(regexp = "BANK_TRANSFER|CASH|OTHER", message = "发放方式只能是 BANK_TRANSFER、CASH 或 OTHER") String method,
                                @Size(max = 64, message = "流水号不能超过 64 个字符") String reference,
                                @Size(max = 500, message = "备注不能超过 500 个字符") String remark) {
}
