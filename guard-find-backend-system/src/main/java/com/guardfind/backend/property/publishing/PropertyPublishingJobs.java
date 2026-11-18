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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;

@Component
@RequiredArgsConstructor
@Slf4j
public class PropertyPublishingJobs {
    private final PropertyPublishingService service;
    // Long platform uploads must not occupy the application's shared scheduling threads.
    private final AtomicInteger sequence = new AtomicInteger();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        var thread = new Thread(runnable, "property-publishing-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @Scheduled(fixedDelay = 60000, initialDelay = 75000)
    public void dispatch() {
        for (int i = 0; i < 3; i++) {
            try {
                workers.execute(() -> {
                    try {
                        service.maintain();
                    } catch (Exception e) {
                        log.warn("运营发布任务处理未完成", e);
                    }
                });
            } catch (RejectedExecutionException busy) {
                break; // All workers are busy; queued SQL tasks remain unclaimed for the next tick.
            }
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(45, TimeUnit.SECONDS))
                workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
