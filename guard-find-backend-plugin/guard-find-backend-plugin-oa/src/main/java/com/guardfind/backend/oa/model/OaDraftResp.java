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

import java.time.LocalDateTime;

/**
 * 草稿列表项；launchable 为 false 表示发布版本已停用、被新版本替代或已不在可发起范围内。
 */
public record OaDraftResp(Long id, Long releaseId, String title, String modelName, String formName,
                          Integer releaseVersion, boolean launchable, int version, LocalDateTime createTime,
                          LocalDateTime updateTime) {
}
