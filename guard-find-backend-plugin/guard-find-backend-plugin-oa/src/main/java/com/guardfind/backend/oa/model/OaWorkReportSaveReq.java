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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * date 为周期内任一天，周报归一到所在周的周一。已有草稿修改时 version 必填。
 */
public record OaWorkReportSaveReq(@NotBlank(message = "请选择报告类型") String reportType,
                                  @NotNull(message = "请选择报告日期") LocalDate date,
                                  @NotBlank(message = "请填写已完成工作") @Size(max = 5000, message = "已完成工作不能超过 5000 个字符") String doneContent,
                                  @Size(max = 5000, message = "后续计划不能超过 5000 个字符") String planContent,
                                  @Size(max = 2000, message = "问题与协调不能超过 2000 个字符") String issueContent, Boolean submit,
                                  Integer version) {
}
