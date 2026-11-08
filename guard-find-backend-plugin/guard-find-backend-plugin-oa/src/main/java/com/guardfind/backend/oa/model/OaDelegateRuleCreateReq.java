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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 新建限时代理规则。委托人固定为当前登录用户，代理窗口不能跨过现有规则。
 */
public record OaDelegateRuleCreateReq(@NotNull(message = "代理人不能为空") Long delegateId,
                                      @NotNull(message = "开始时间不能为空") LocalDateTime startTime,
                                      @NotNull(message = "结束时间不能为空") LocalDateTime endTime,
                                      @Size(max = 500, message = "代理原因不能超过 500 个字符") String reason) {
}
