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

import com.guardfind.backend.oa.service.OaExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 异步导出：定时生成待处理的导出文件，并删除过期文件。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oa.export", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OaExportJob {

    private final OaExportService exportService;

    @Scheduled(fixedDelayString = "${oa.export.delay-ms:10000}", initialDelayString = "${oa.export.initial-delay-ms:15000}")
    public void run() {
        try {
            int handled = exportService.runPending();
            if (handled > 0) {
                log.info("OA 导出任务处理 {} 条", handled);
            }
        } catch (RuntimeException e) {
            log.error("OA 导出任务扫描失败", e);
        }
        try {
            int purged = exportService.purgeExpired();
            if (purged > 0) {
                log.info("OA 过期导出文件清理 {} 条", purged);
            }
        } catch (RuntimeException e) {
            log.error("OA 过期导出文件清理失败", e);
        }
    }
}
