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

package com.guardfind.backend.oa.staff;

/**
 * 审批人无法解析或校验失败。发布或发起时抛出，调用方必须回滚，不得默认同意。
 */
public class OaAssigneeUnavailableException extends RuntimeException {

    public OaAssigneeUnavailableException(String message) {
        super(message);
    }

    public OaAssigneeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
