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
 * 发起时对一个流程变量的审批人来源绑定。
 *
 * @param variableName 流程变量名
 * @param type         EMPLOYEE/POSITION/DEPT_LEADER/SUPERVISOR/FORM_FIELD
 * @param value        员工 ID、岗位编码或表单字段 Key；部门负责人和直属主管不填
 */
public record OaAssigneeBindingReq(@NotBlank(message = "审批人变量名不能为空") @Size(max = 64, message = "审批人变量名不能超过 64 个字符") String variableName,
                                   @NotBlank(message = "审批人来源不能为空") String type,
                                   @Size(max = 128, message = "审批人取值不能超过 128 个字符") String value) {
}
