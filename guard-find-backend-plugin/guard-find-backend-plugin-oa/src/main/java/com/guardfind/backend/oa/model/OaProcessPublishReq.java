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

import jakarta.validation.constraints.Size;

/**
 * 发布请求。表单版本可选；指定时必须属于当前租户。
 */
public record OaProcessPublishReq(Long formVersionId,
                                  @Size(max = 2000, message = "可发起范围不能超过 2000 个字符") String launchScope,
                                  @Size(max = 500, message = "发布说明不能超过 500 个字符") String remark) {
}
