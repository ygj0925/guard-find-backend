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

import com.guardfind.backend.oa.service.OaContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 合同到期提醒：每天扫描 30 天内到期的已签署/已归档合同，同一到期日只提醒经办人一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oa.contract-expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OaContractExpiryJob {

    private final OaContractService contractService;

    @Scheduled(cron = "${oa.contract-expiry.cron:0 0 9 * * ?}")
    public void scan() {
        try {
            int recorded = contractService.scanExpiring(LocalDate.now());
            if (recorded > 0) {
                log.info("OA 合同到期扫描生成 {} 条提醒事件", recorded);
            }
        } catch (RuntimeException e) {
            log.error("OA 合同到期扫描失败", e);
        }
    }
}
