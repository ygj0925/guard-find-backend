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
 * 离职/调岗待办转交入口，便于员工服务在事务内触发，也便于单元测试替换。
 */
public interface OaTaskHandover {

    /**
     * 把来源员工名下所有运行中任务转交给目标处理人，返回转交数量。
     */
    int transferRunningTasks(long tenantId, long operatorId, long fromUserId, long targetUserId, String reason);
}
