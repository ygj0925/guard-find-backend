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

import com.guardfind.backend.oa.service.OaMeetingService;
import com.guardfind.backend.oa.service.OaVehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 资源超时释放：审批中的会议室预订到开始时间释放，用车结束仍未出车的申请置为过期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oa.resource-expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OaResourceExpiryJob {

    private final OaMeetingService meetingService;
    private final OaVehicleService vehicleService;

    @Scheduled(cron = "${oa.resource-expiry.cron:0 */5 * * * ?}")
    public void scan() {
        LocalDateTime now = LocalDateTime.now();
        try {
            int meetings = meetingService.expireApprovals(now);
            if (meetings > 0) {
                log.info("OA 会议室预订超时释放 {} 条", meetings);
            }
        } catch (RuntimeException e) {
            log.error("OA 会议室预订超时扫描失败", e);
        }
        try {
            int vehicles = vehicleService.expireDispatches(now);
            if (vehicles > 0) {
                log.info("OA 用车申请过期 {} 条", vehicles);
            }
        } catch (RuntimeException e) {
            log.error("OA 用车申请过期扫描失败", e);
        }
    }
}
