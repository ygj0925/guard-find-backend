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

public record OaContractSignReq(@NotBlank(message = "请求号不能为空") @Size(max = 64, message = "请求号不能超过 64 个字符") String requestId,
                                @NotNull(message = "请选择签署日期") LocalDate signedDate,
                                @NotBlank(message = "请填写签署人") @Size(max = 200, message = "签署人不能超过 200 个字符") String signatories,
                                @Size(max = 500, message = "备注不能超过 500 个字符") String remark) {
}
