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

package com.guardfind.backend.oa.business;

import com.guardfind.backend.oa.staff.OaAssigneeSpec;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 业务类型钩子：通用审批之上的业务规则（额度、金额、台账）都在发起与终态事务内执行，
 * 任一步抛出业务异常都会回滚整张申请。
 */
public interface OaBusinessHook {

    String businessType();

    /**
     * 发布时校验表单是否满足业务字段约定，返回错误列表。
     */
    default List<String> validateForm(String schemaJson) {
        return List.of();
    }

    /**
     * 发起页与发起时对未显式绑定的审批人变量给出的默认来源。
     */
    default Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of();
    }

    /**
     * 启动引擎前执行：校验业务规则、锁定额度，并返回写入流程变量的受信值。
     */
    OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData);

    /**
     * 申请落库后执行：写入业务单据并占用额度。
     */
    void submitted(long tenantId,
                   long applicationId,
                   long applicantId,
                   OaBusinessPreparation preparation,
                   LocalDateTime now);

    /**
     * 申请进入终态（APPROVED/REJECTED/WITHDRAWN/CANCELLED）后执行，必须幂等。
     */
    void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now);
}
