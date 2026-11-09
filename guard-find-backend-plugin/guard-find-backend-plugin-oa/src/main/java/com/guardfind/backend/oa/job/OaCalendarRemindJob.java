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

package com.guardfind.backend.oa.job;

import com.guardfind.backend.oa.service.OaCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 日程到点提醒扫描。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oa.calendar-remind", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OaCalendarRemindJob {

    private final OaCalendarService calendarService;

    @Scheduled(cron = "${oa.calendar-remind.cron:0 * * * * ?}")
    public void scan() {
        try {
            int reminded = calendarService.remindDue(LocalDateTime.now());
            if (reminded > 0) {
                log.info("OA 日程提醒 {} 条", reminded);
            }
        } catch (RuntimeException e) {
            log.error("OA 日程提醒扫描失败", e);
        }
    }
}
