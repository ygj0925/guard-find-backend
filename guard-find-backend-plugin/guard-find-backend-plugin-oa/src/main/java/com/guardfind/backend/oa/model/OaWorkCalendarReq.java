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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 登记或覆盖某天的日期类型。
 */
public record OaWorkCalendarReq(@NotNull(message = "日期不能为空") LocalDate calendarDate,
                                @NotBlank(message = "日期类型不能为空") @Pattern(regexp = "WORKDAY|HOLIDAY", message = "日期类型只能是 WORKDAY 或 HOLIDAY") String dayType,
                                @Size(max = 64, message = "名称不能超过 64 个字符") String name) {
}
