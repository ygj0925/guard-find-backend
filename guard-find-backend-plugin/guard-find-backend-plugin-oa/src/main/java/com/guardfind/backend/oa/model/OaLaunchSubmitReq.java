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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 发起请求。requestId 由客户端生成，用于命令幂等与重试回放。
 */
public record OaLaunchSubmitReq(@NotBlank(message = "请求 ID 不能为空") @Size(max = 64, message = "请求 ID 不能超过 64 个字符") String requestId,
                                @Size(max = 200, message = "标题不能超过 200 个字符") String title,
                                @NotNull(message = "表单数据不能为空") Map<String, Object> formData,
                                @NotNull(message = "审批人绑定不能为空") @Size(max = 50, message = "审批人变量不能超过 50 个") List<@Valid OaAssigneeBindingReq> assignees) {
}
