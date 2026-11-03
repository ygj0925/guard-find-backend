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

package com.guardfind.backend.miniapp.service;

import com.guardfind.backend.miniapp.model.entity.MiniappNoticeDO;
import com.guardfind.backend.miniapp.mapper.MiniappNoticeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MiniappNoticeService {
    private final MiniappNoticeMapper mapper;

    public void send(Long userId, String type, Long businessId, String title, String content) {
        var notice = new MiniappNoticeDO();
        notice.setUserId(userId);
        notice.setBusinessType(type);
        notice.setBusinessId(businessId);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setIsRead(0);
        notice.setCreateTime(LocalDateTime.now());
        mapper.insert(notice);
    }
}
