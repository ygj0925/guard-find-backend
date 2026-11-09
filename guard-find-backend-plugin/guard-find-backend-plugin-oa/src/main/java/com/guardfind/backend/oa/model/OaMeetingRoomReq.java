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

public record OaMeetingRoomReq(@NotBlank(message = "请填写会议室编码") @Size(max = 32, message = "会议室编码不能超过 32 个字符") @Pattern(regexp = "[A-Za-z0-9_-]+", message = "会议室编码只能包含字母、数字、下划线和短横线") String code,
                               @NotBlank(message = "请填写会议室名称") @Size(max = 100, message = "会议室名称不能超过 100 个字符") String name,
                               @Size(max = 200, message = "位置不能超过 200 个字符") String location,
                               @NotNull(message = "请填写容纳人数") @Min(value = 1, message = "容纳人数至少为 1") @Max(value = 1000, message = "容纳人数不能超过 1000") Integer capacity,
                               Boolean needApproval,
                               @Pattern(regexp = "ENABLED|DISABLED", message = "状态无效") String status,
                               @Size(max = 500, message = "备注不能超过 500 个字符") String remark, Integer version) {
}
