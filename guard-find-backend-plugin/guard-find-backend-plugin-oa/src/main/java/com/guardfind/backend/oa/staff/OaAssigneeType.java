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

package com.guardfind.backend.oa.staff;

/**
 * 审批人来源：首版只支持明确、可校验的五种来源，不支持任意表达式。
 */
public enum OaAssigneeType {

    /**
     * 指定员工
     */
    EMPLOYEE,

    /**
     * 岗位（解析为岗位下全部在职员工）
     */
    POSITION,

    /**
     * 发起人所在部门负责人
     */
    DEPT_LEADER,

    /**
     * 发起人的直属主管
     */
    SUPERVISOR,

    /**
     * 受控表单人员字段（字段值必须是本租户在职员工）
     */
    FORM_FIELD
}
