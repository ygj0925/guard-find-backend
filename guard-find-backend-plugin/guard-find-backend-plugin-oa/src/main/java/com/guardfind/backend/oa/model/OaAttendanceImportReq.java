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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OaAttendanceImportReq(@Size(max = 64, message = "请求号不能超过 64 个字符") String requestId,
                                    @Size(max = 200, message = "文件名不能超过 200 个字符") String fileName,
                                    @NotEmpty(message = "导入数据不能为空") @Size(max = 2000, message = "单次最多导入 2000 行") List<@Valid OaAttendanceImportRow> rows) {
}
