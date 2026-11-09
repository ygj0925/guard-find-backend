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

import java.time.LocalDate;

public record OaExportCreateReq(@NotBlank(message = "请求号不能为空") @Size(max = 64, message = "请求号过长") String requestId,
                                @NotBlank(message = "请选择导出类型") String exportType,
                                @NotNull(message = "请选择开始日期") LocalDate from,
                                @NotNull(message = "请选择结束日期") LocalDate to,
                                @Size(max = 64, message = "业务类型过长") String businessType) {
}
