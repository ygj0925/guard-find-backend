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

public record OaSealReq(@NotBlank(message = "请填写印章编码") @Size(max = 32, message = "印章编码不能超过 32 个字符") @Pattern(regexp = "[A-Za-z0-9_-]+", message = "印章编码只能包含字母、数字、下划线和短横线") String code,
                        @NotBlank(message = "请填写印章名称") @Size(max = 100, message = "印章名称不能超过 100 个字符") String name,
                        @NotBlank(message = "请选择印章类型") @Pattern(regexp = "OFFICIAL|CONTRACT|FINANCE|LEGAL_REP|OTHER", message = "印章类型无效") String sealType,
                        @NotNull(message = "请选择保管人") Long keeperId,
                        @Pattern(regexp = "ENABLED|DISABLED", message = "状态无效") String status,
                        @Size(max = 500, message = "备注不能超过 500 个字符") String remark, Integer version) {
}
