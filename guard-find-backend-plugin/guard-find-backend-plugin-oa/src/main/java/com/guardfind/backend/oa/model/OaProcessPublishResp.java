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

import java.util.List;

/**
 * 发布结果。校验失败时 valid=false 且不创建发布记录。
 */
public record OaProcessPublishResp(boolean valid, List<String> errors, Long releaseId, Integer releaseVersion,
                                   String engineDefinitionId, Integer processDefinitionVersion) {

    public OaProcessPublishResp {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static OaProcessPublishResp rejected(List<String> errors) {
        return new OaProcessPublishResp(false, errors, null, null, null, null);
    }
}
