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

package com.guardfind.backend.property.lease;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@RequiredArgsConstructor
@Slf4j
public class PropertyLeaseJobs {
    private final PropertyLeaseService service;

    @Scheduled(fixedDelay = 3600000, initialDelay = 90000)
    public void remind() {
        try {
            service.maintain();
        } catch (Exception e) {
            log.warn("租赁合同到期提醒未完成，下轮将重试", e);
        }
    }
}
