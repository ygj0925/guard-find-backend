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

package com.guardfind.backend.property.operations;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@RequiredArgsConstructor
public class PropertyOperationsJobs {
    private static final Logger LOG = LoggerFactory.getLogger(PropertyOperationsJobs.class);
    private final PropertyBookingOperations bookings;
    private final PropertyNotificationService notifications;

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void maintain() {
        try {
            bookings.maintain();
        } catch (Exception e) {
            LOG.warn("房源预约维护未完成", e);
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 45000)
    public void notifyUsers() {
        try {
            notifications.dispatch();
        } catch (Exception e) {
            LOG.warn("订阅消息分发未完成", e);
        }
    }
}
