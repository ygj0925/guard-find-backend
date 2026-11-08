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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 年度余额；可用 = 总额 - 已扣减 - 审批中占用。
 */
public record OaLeaveBalanceResp(long id, long userId, String userName, long leaveTypeId, String leaveTypeCode,
                                 String leaveTypeName, int periodYear, BigDecimal totalDays, BigDecimal usedDays,
                                 BigDecimal occupiedDays, BigDecimal availableDays, LocalDateTime updateTime) {
}
