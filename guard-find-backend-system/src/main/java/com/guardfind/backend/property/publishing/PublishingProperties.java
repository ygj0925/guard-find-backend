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

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/** Secrets are bound only on the server. Never expose this bean through an API. */
@Data
@Component
@ConfigurationProperties(prefix = "guardfind.publishing")
public class PublishingProperties {
    private boolean enabled;
    private Set<String> mediaHosts = new HashSet<>();
    @ToString.Exclude
    private Map<String, Credential> douyinAccounts = new HashMap<>();

    @Data
    public static class Credential {
        private String openId;
        @ToString.Exclude
        private String accessToken;
        /** ISO instant, e.g. 2026-12-01T00:00:00Z. */
        private String expiresAt;
        private boolean housingApproved;
        private boolean scopeConfirmed;
    }
}
