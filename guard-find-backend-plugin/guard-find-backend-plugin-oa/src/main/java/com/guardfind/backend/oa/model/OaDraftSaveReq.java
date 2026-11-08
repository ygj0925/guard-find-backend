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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 保存申请草稿。草稿不做必填校验，提交时仍走完整发起校验；更新时 version 用于乐观锁。
 */
public record OaDraftSaveReq(@NotNull(message = "流程发布 ID 不能为空") Long releaseId,
                             @Size(max = 200, message = "标题不能超过 200 个字符") String title, Map<String, Object> formData,
                             @Size(max = 50, message = "审批人变量不能超过 50 个") List<@Valid OaAssigneeBindingReq> assignees,
                             @Size(max = 20, message = "抄送人不能超过 20 人") List<Long> ccUserIds, Integer version) {
}
