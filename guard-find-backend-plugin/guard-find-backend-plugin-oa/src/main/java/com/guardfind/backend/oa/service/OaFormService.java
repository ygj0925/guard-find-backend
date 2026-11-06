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

import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaFormUpdateReq;
import com.guardfind.backend.oa.model.OaFormValidationResult;
import com.guardfind.backend.oa.model.OaFormVersionDetailResp;
import com.guardfind.backend.oa.model.OaFormVersionOptionResp;
import com.guardfind.backend.oa.model.OaFormVersionResp;
import com.guardfind.backend.oa.repository.OaFormRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 表单草稿与发布版本服务。
 *
 * <p>
 * 草稿可反复修改；发布时校验 schema 并写入不可变 oa_form_version 快照，同时推进表单指针。
 * 已发布的版本不允许修改，流程发布只能绑定已存在的表单版本。
 * </p>
 */
@Service
public class OaFormService {

    static final String EMPTY_SCHEMA = "{\"version\":1,\"fields\":[]}";

    private final OaFormRepository repository;
    private final OaFormSchemaValidator validator;
    private final TransactionTemplate transactionTemplate;

    public OaFormService(OaFormRepository repository,
                         OaFormSchemaValidator validator,
                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.validator = validator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaFormResp> page(long tenantId, String keyword, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.page(tenantId, keyword, status, (safePage - 1) * safeSize, safeSize);
    }

    public long count(long tenantId, String keyword, String status) {
        return repository.count(tenantId, keyword, status);
    }

    public OaFormResp detail(long tenantId, long id) {
        OaFormResp form = repository.detail(tenantId, id);
        if (form == null) {
            throw new BusinessException("表单不存在");
        }
        return form;
    }

    public List<OaFormVersionResp> versions(long tenantId, long id) {
        detail(tenantId, id);
        return repository.versions(tenantId, id);
    }

    public List<OaFormVersionOptionResp> versionOptions(long tenantId) {
        return repository.versionOptions(tenantId);
    }

    public OaFormVersionDetailResp versionDetail(long tenantId, long versionId) {
        OaFormVersionDetailResp detail = repository.versionDetail(tenantId, versionId);
        if (detail == null) {
            throw new BusinessException("表单版本不存在");
        }
        return detail;
    }

    public OaFormResp create(long tenantId, long userId, OaFormCreateReq req) {
        String schemaJson = req.schemaJson() == null || req.schemaJson().isBlank() ? EMPTY_SCHEMA : req.schemaJson();
        OaFormValidationResult validation = validator.validate(schemaJson, false);
        requireValid(validation);
        if (repository.formKeyExists(tenantId, req.formKey())) {
            throw new BusinessException("表单标识已存在");
        }
        try {
            long id = repository.insertForm(tenantId, req.formKey(), req.name().trim(), req
                .description(), schemaJson, validation.digest(), userId, LocalDateTime.now());
            return detail(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("表单标识已存在");
        }
    }

    public OaFormResp update(long tenantId, long userId, long id, OaFormUpdateReq req) {
        OaFormResp form = detail(tenantId, id);
        String schemaJson = req.schemaJson() == null ? form.schemaJson() : req.schemaJson();
        if (schemaJson == null || schemaJson.isBlank()) {
            schemaJson = EMPTY_SCHEMA;
        }
        OaFormValidationResult validation = validator.validate(schemaJson, false);
        requireValid(validation);
        int rows = repository.updateDraft(tenantId, id, req.name().trim(), req.description(), schemaJson, validation
            .digest(), userId, LocalDateTime.now(), req.version());
        if (rows == 0) {
            throw new BusinessException("表单已被其他人修改，请刷新后重试");
        }
        return detail(tenantId, id);
    }

    public OaFormPublishResp publish(long tenantId, long userId, long id) {
        return transactionTemplate.execute(status -> doPublish(tenantId, userId, id));
    }

    private OaFormPublishResp doPublish(long tenantId, long userId, long id) {
        OaFormResp form = repository.lock(tenantId, id);
        if (form == null) {
            throw new BusinessException("表单不存在");
        }
        String schemaJson = form.schemaJson() == null || form.schemaJson().isBlank() ? EMPTY_SCHEMA : form.schemaJson();
        OaFormValidationResult validation = validator.validate(schemaJson, true);
        if (!validation.valid()) {
            return OaFormPublishResp.rejected(validation.errors());
        }

        int formVersion = repository.nextVersion(tenantId, id);
        LocalDateTime now = LocalDateTime.now();
        long versionId = repository.insertVersion(tenantId, id, formVersion, schemaJson, validation.digest(), validation
            .fieldCount(), userId, now);
        int rows = repository.markPublished(tenantId, id, versionId, formVersion, validation.digest(), userId, now, form
            .version());
        if (rows == 0) {
            throw new BusinessException("表单已被其他人修改，请刷新后重试");
        }
        return new OaFormPublishResp(true, List.of(), versionId, formVersion, validation.digest(), validation
            .fieldCount());
    }

    public OaFormResp disable(long tenantId, long userId, long id) {
        return transactionTemplate.execute(status -> {
            OaFormResp form = repository.lock(tenantId, id);
            if (form == null) {
                throw new BusinessException("表单不存在");
            }
            if ("DISABLED".equals(form.status())) {
                return form;
            }
            int rows = repository.markDisabled(tenantId, id, userId, LocalDateTime.now(), form.version());
            if (rows == 0) {
                throw new BusinessException("表单已被其他人修改，请刷新后重试");
            }
            return repository.detail(tenantId, id);
        });
    }

    private static void requireValid(OaFormValidationResult validation) {
        if (!validation.valid()) {
            throw new BusinessException(String.join("；", validation.errors()));
        }
    }
}
