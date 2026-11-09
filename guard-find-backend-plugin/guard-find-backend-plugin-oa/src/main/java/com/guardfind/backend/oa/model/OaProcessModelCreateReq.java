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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建流程模型草稿。modelKey 创建后不可修改，发布时作为稳定标识。
 */
public record OaProcessModelCreateReq(@NotBlank(message = "模型标识不能为空") @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}", message = "模型标识只能包含字母、数字、下划线，且以字母或下划线开头") String modelKey,
                                      @NotBlank(message = "模型名称不能为空") @Size(max = 100, message = "模型名称不能超过 100 个字符") String name,
                                      @Size(max = 64, message = "分类不能超过 64 个字符") String category,
                                      @Size(max = 500, message = "说明不能超过 500 个字符") String description, String bpmnXml,
                                      @Pattern(regexp = "GENERIC|LEAVE|EXPENSE|TRAVEL|LOAN|ATTENDANCE_CORRECTION|OVERTIME|OUTING|PURCHASE|SUPPLY_REQUEST|ASSET_SCRAP", message = "业务类型不在支持范围内") String businessType) {
}
