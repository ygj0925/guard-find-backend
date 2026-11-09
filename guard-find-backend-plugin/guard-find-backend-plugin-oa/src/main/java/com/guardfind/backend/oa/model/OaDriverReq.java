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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OaDriverReq(@NotBlank(message = "请填写司机姓名") @Size(max = 64, message = "司机姓名不能超过 64 个字符") String name,
                          @Size(max = 32, message = "联系电话不能超过 32 个字符") String phone, Long userId,
                          @Pattern(regexp = "ENABLED|DISABLED", message = "状态无效") String status,
                          @Size(max = 500, message = "备注不能超过 500 个字符") String remark, Integer version) {
}
