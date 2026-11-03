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

package com.guardfind.backend.property.model.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约看房请求
 */
@Data
public class PropertyBookingReq {

    @NotNull(message = "预约时间不能为空")
    private LocalDateTime bookingTime;

    @NotBlank(message = "联系人不能为空")
    @Size(max = 50, message = "联系人不能超过 50 个字符")
    private String contactName;

    @NotBlank(message = "联系电话不能为空")
    @Size(max = 20, message = "联系电话不能超过 20 个字符")
    private String contactPhone;

    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;
}
