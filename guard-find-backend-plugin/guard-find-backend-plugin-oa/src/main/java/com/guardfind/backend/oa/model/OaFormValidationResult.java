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
 * 表单 schema 校验结果。digest 为原始 schema 的 SHA-256，发布时写入不可变版本快照。
 */
public record OaFormValidationResult(boolean valid, List<String> errors, int fieldCount, String digest) {

    public OaFormValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static OaFormValidationResult failure(List<String> errors, String digest) {
        return new OaFormValidationResult(false, errors, 0, digest);
    }
}
