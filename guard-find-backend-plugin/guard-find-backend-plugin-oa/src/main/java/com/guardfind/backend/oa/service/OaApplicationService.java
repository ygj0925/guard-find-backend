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

package com.guardfind.backend.oa.service;

import com.guardfind.backend.oa.model.OaApplicationDetailResp;
import com.guardfind.backend.oa.model.OaApplicationResp;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.util.List;

/**
 * 我的申请查询。只允许发起人读取自己的申请，不提供他人申请的管理视图。
 */
@Service
public class OaApplicationService {

    private final OaLaunchRepository repository;

    public OaApplicationService(OaLaunchRepository repository) {
        this.repository = repository;
    }

    public List<OaApplicationResp> page(long tenantId,
                                        long applicantId,
                                        String keyword,
                                        String status,
                                        int page,
                                        int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.applicationPage(tenantId, applicantId, keyword, status, (safePage - 1) * safeSize, safeSize);
    }

    public long count(long tenantId, long applicantId, String keyword, String status) {
        return repository.applicationCount(tenantId, applicantId, keyword, status);
    }

    public OaApplicationDetailResp detail(long tenantId, long applicantId, long id) {
        OaApplicationDetailResp detail = repository.applicationDetail(tenantId, applicantId, id);
        if (detail == null) {
            throw new BusinessException("申请不存在");
        }
        return detail;
    }
}
