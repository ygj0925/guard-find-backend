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

import com.guardfind.backend.oa.service.OaOutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * outbox 定时投递。默认开启，可用 oa.outbox.dispatch-enabled=false 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oa.outbox", name = "dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class OaOutboxJob {

    private final OaOutboxDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${oa.outbox.dispatch-delay-ms:15000}", initialDelayString = "${oa.outbox.initial-delay-ms:15000}")
    public void dispatch() {
        OaOutboxDispatcher.DispatchResult result = dispatcher.dispatchDue();
        if (result.failed() > 0) {
            log.warn("OA outbox 投递完成：扫描 {}，成功 {}，失败 {}", result.scanned(), result.sent(), result.failed());
        }
    }
}
