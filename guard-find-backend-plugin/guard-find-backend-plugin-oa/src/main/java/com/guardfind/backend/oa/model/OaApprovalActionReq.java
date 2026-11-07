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
import jakarta.validation.constraints.Size;

/**
 * 审批命令请求。requestId 用于幂等回放；驳回与退回必须填写原因。
 */
public record OaApprovalActionReq(@NotBlank(message = "请求 ID 不能为空") @Size(max = 64, message = "请求 ID 不能超过 64 个字符") String requestId,
                                  @Size(max = 500, message = "原因不能超过 500 个字符") String reason) {
}
