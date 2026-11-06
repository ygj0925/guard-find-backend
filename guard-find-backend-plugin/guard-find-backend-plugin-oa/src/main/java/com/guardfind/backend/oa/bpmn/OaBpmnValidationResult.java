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

package com.guardfind.backend.oa.bpmn;

import java.util.List;

/**
 * BPMN 校验结果。digest 为原始 XML 的 SHA-256，用于发布版本指纹。
 *
 * @param valid      是否通过
 * @param errors     错误列表，通过时为空
 * @param nodeCount  流程节点数
 * @param digest     原始 XML 的 SHA-256 十六进制摘要
 * @param processKey 流程定义 key，解析失败时可能为空
 */
public record OaBpmnValidationResult(boolean valid, List<String> errors, int nodeCount, String digest,
                                     String processKey) {

    public OaBpmnValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    static OaBpmnValidationResult failure(String message, String digest) {
        return new OaBpmnValidationResult(false, List.of(message), 0, digest, null);
    }
}
