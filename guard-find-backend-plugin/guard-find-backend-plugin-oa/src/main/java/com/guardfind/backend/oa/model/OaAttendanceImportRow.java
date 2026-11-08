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

import jakarta.validation.constraints.Size;

/**
 * 导入行原样保留字符串，格式错误按行报告而不是整体 400。
 */
public record OaAttendanceImportRow(@Size(max = 32, message = "工号不能超过 32 个字符") String employeeNo,
                                    @Size(max = 10, message = "日期不能超过 10 个字符") String workDate,
                                    @Size(max = 5, message = "上班时间不能超过 5 个字符") String checkIn,
                                    @Size(max = 5, message = "下班时间不能超过 5 个字符") String checkOut) {
}
