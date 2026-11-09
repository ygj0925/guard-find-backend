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
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新建时 requestId 必填用于幂等；修改时 version 必填。全天日程按开始/结束日期整天占用。
 */
public record OaCalendarEventReq(@Size(max = 64, message = "请求号不能超过 64 个字符") String requestId,
                                 @NotBlank(message = "请填写日程标题") @Size(max = 200, message = "日程标题不能超过 200 个字符") String title,
                                 @Size(max = 200, message = "地点不能超过 200 个字符") String location,
                                 @Size(max = 2000, message = "说明不能超过 2000 个字符") String description,
                                 @NotNull(message = "请选择开始时间") LocalDateTime startTime,
                                 @NotNull(message = "请选择结束时间") LocalDateTime endTime, Boolean allDay, String scope,
                                 Long deptId,
                                 @Min(value = 0, message = "提醒时间不能为负") @Max(value = 10080, message = "最多提前 7 天提醒") Integer remindMinutes,
                                 @Size(max = 50, message = "成员最多 50 人") List<Long> memberIds, Integer version) {
}
