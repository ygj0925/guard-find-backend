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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 审批中追加抄送。接收人须为本租户在职员工，已在名单中的人跳过。
 */
public record OaTaskCcReq(@NotBlank(message = "请求 ID 不能为空") @Size(max = 64, message = "请求 ID 不能超过 64 个字符") String requestId,
                          @NotEmpty(message = "请选择抄送人") @Size(max = 20, message = "抄送人不能超过 20 人") List<Long> receiverIds,
                          @Size(max = 500, message = "抄送说明不能超过 500 个字符") String reason) {
}
