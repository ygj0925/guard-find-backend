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

import java.util.List;

/**
 * OA 员工目录只读入口。实现必须显式带 tenant_id 查询，不依赖 MyBatis 拦截器。
 */
public interface OaStaffDirectory {

    /**
     * 是否为本租户在职且启用的员工
     */
    boolean activeEmployee(long tenantId, long userId);

    /**
     * 员工当前所属部门（以 sys_user.dept_id 为准）
     */
    Long departmentId(long tenantId, long userId);

    /**
     * 员工直属主管用户 ID
     */
    Long supervisorId(long tenantId, long userId);

    /**
     * 岗位是否存在且启用
     */
    boolean enabledPosition(long tenantId, String code);

    /**
     * 岗位下全部在职员工
     */
    List<Long> activeUsersByPosition(long tenantId, String code);

    /**
     * 部门负责人（部门内在职且标记 dept_leader 的员工）
     */
    List<Long> departmentLeaders(long tenantId, long departmentId);
}
