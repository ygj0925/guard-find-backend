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

import java.time.LocalDateTime;

/**
 * 代理规则展示项。active 由服务端按当前时间计算，避免前端时钟漂移。
 */
public record OaDelegateRuleResp(long id, long delegatorId, String delegatorName, long delegateId, String delegateName,
                                 LocalDateTime startTime, LocalDateTime endTime, String status, String reason,
                                 boolean active, LocalDateTime createTime) {
}
