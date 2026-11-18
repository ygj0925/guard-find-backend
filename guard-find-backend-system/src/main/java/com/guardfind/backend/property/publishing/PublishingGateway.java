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

package com.guardfind.backend.property.publishing;

import java.util.List;
import java.util.function.BooleanSupplier;

public interface PublishingGateway {
    boolean ready(String credentialKey);

    /** Success means accepted for moderation, not publicly published. */
    Result publish(String credentialKey, String title, String body, List<String> images);

    default Result publish(String credentialKey,
                           String title,
                           String body,
                           List<String> images,
                           BooleanSupplier stillCurrent) {
        if (!stillCurrent.getAsBoolean())
            return new Result("FAILED", null, "房源或任务已变化，本次尚未提交发布");
        return publish(credentialKey, title, body, images);
    }

    record Result(String status, String itemId, String message) {}
}
