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

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 批量排班：对所选员工在日期区间内（可限定星期几，1=周一…7=周日）设置班次；shiftId 为空表示休息，clear 为真时删除排班。
 */
public record OaScheduleSaveReq(@NotEmpty(message = "请选择员工") @Size(max = 200, message = "一次最多为 200 名员工排班") List<Long> userIds,
                                @NotNull(message = "开始日期不能为空") LocalDate startDate,
                                @NotNull(message = "结束日期不能为空") LocalDate endDate, List<Integer> weekdays, Long shiftId,
                                Boolean clear) {
}
