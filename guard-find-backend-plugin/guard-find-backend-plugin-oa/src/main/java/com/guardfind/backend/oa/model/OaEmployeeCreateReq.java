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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 新建 OA 员工档案请求。用户来自后台账号，部门以账号归属为准。
 */
public record OaEmployeeCreateReq(@NotNull(message = "请选择后台用户") @Positive(message = "后台用户 ID 非法") Long userId,
                                  @Size(max = 64, message = "工号不能超过 64 个字符") String employeeNo,
                                  @Positive(message = "直属主管 ID 非法") Long supervisorId,
                                  @Pattern(regexp = "^(ACTIVE|SUSPENDED|RESIGNED)$", message = "在职状态只能是 ACTIVE、SUSPENDED 或 RESIGNED") String employmentStatus,
                                  Boolean deptLeader, LocalDate hireDate, LocalDate leaveDate,
                                  @Size(max = 20, message = "一个员工最多关联 20 个岗位") List<Long> positionIds) {
}
