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

import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaProcessModelUpdateReq;
import com.guardfind.backend.oa.model.OaProcessPublishReq;
import com.guardfind.backend.oa.model.OaProcessPublishResp;
import com.guardfind.backend.oa.model.OaProcessReleaseResp;
import com.guardfind.backend.oa.repository.OaProcessModelRepository;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 流程模型草稿与发布版本服务。
 *
 * <p>
 * 发布在同一事务内完成 XML 校验、Flowable 部署、不可变发布记录写入和模型指针更新；
 * 任一步失败都回滚，不会留下半套版本。旧实例始终绑定原流程定义，不做迁移。
 * </p>
 */
@Service
public class OaProcessModelService {

    private static final int MAX_XML_BYTES = 512 * 1024;

    private final OaProcessModelRepository repository;
    private final OaBpmnValidator validator;
    private final RepositoryService repositoryService;
    private final TransactionTemplate transactionTemplate;

    public OaProcessModelService(OaProcessModelRepository repository,
                                 OaBpmnValidator validator,
                                 RepositoryService repositoryService,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.validator = validator;
        this.repositoryService = repositoryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaProcessModelResp> page(long tenantId, String keyword, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.page(tenantId, keyword, status, (safePage - 1) * safeSize, safeSize);
    }

    public long count(long tenantId, String keyword, String status) {
        return repository.count(tenantId, keyword, status);
    }

    public OaProcessModelResp detail(long tenantId, long id) {
        OaProcessModelResp model = repository.detail(tenantId, id);
        if (model == null) {
            throw new BusinessException("流程模型不存在");
        }
        return model;
    }

    public List<OaProcessReleaseResp> releases(long tenantId, long id) {
        detail(tenantId, id);
        return repository.releases(tenantId, id);
    }

    public OaProcessModelResp create(long tenantId, long userId, OaProcessModelCreateReq req) {
        requireXmlSize(req.bpmnXml());
        if (repository.modelKeyExists(tenantId, req.modelKey())) {
            throw new BusinessException("模型标识已存在");
        }
        try {
            long id = repository.insertModel(tenantId, req.modelKey(), req.name().trim(), req.category(), req
                .description(), req.bpmnXml(), digestOrNull(req.bpmnXml()), userId, LocalDateTime.now());
            return detail(tenantId, id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException("模型标识已存在");
        }
    }

    public OaProcessModelResp update(long tenantId, long userId, long id, OaProcessModelUpdateReq req) {
        OaProcessModelResp model = detail(tenantId, id);
        String xml = req.bpmnXml() == null ? model.bpmnXml() : req.bpmnXml();
        requireXmlSize(xml);
        int rows = repository.updateDraft(tenantId, id, req.name().trim(), req.category(), req
            .description(), xml, digestOrNull(xml), userId, LocalDateTime.now(), req.version());
        if (rows == 0) {
            throw new BusinessException("模型已被其他人修改，请刷新后重试");
        }
        return detail(tenantId, id);
    }

    public OaProcessPublishResp publish(long tenantId, long userId, long id, OaProcessPublishReq req) {
        return transactionTemplate.execute(status -> doPublish(tenantId, userId, id, req));
    }

    private OaProcessPublishResp doPublish(long tenantId, long userId, long id, OaProcessPublishReq req) {
        OaProcessModelResp model = repository.lock(tenantId, id);
        if (model == null) {
            throw new BusinessException("流程模型不存在");
        }
        if (model.bpmnXml() == null || model.bpmnXml().isBlank()) {
            return OaProcessPublishResp.rejected(List.of("流程 XML 不能为空"));
        }
        requireXmlSize(model.bpmnXml());

        OaBpmnValidationResult validation = validator.validate(model.bpmnXml().getBytes(StandardCharsets.UTF_8));
        if (!validation.valid()) {
            return OaProcessPublishResp.rejected(validation.errors());
        }
        String processKey = validation.processKey();
        if (processKey == null || processKey.isBlank()) {
            return OaProcessPublishResp.rejected(List.of("无法解析流程定义 Key"));
        }
        String latestKey = repository.latestProcessKey(tenantId, id);
        if (latestKey != null && !latestKey.equals(processKey)) {
            return OaProcessPublishResp.rejected(List.of("流程定义 Key 已发布为 " + latestKey + "，不能在后续版本中修改"));
        }
        if (repository.processKeyUsedByOtherModel(tenantId, processKey, id)) {
            return OaProcessPublishResp.rejected(List.of("流程定义 Key 已被其他模型占用: " + processKey));
        }

        OaProcessModelRepository.FormVersion formVersion = null;
        if (req != null && req.formVersionId() != null) {
            formVersion = repository.formVersion(tenantId, req.formVersionId());
            if (formVersion == null) {
                return OaProcessPublishResp.rejected(List.of("绑定的表单版本不存在或不属于当前租户"));
            }
        }

        int releaseVersion = repository.nextReleaseVersion(tenantId, id);
        Deployment deployment = repositoryService.createDeployment()
            .name(model.modelKey() + "-v" + releaseVersion)
            .tenantId(Long.toString(tenantId))
            .addString(processKey + "-v" + releaseVersion + ".bpmn20.xml", model.bpmnXml())
            .deploy();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
            .deploymentId(deployment.getId())
            .processDefinitionTenantId(Long.toString(tenantId))
            .singleResult();
        if (definition == null) {
            throw new BusinessException("流程部署后未找到流程定义");
        }

        LocalDateTime now = LocalDateTime.now();
        long releaseId = repository.insertRelease(tenantId, id, releaseVersion, model.bpmnXml(), validation
            .digest(), definition.getId(), definition.getKey(), definition.getVersion(), formVersion == null
                ? null
                : formVersion.formId(), formVersion == null ? null : req.formVersionId(), formVersion == null
                    ? null
                    : formVersion.version(), req == null ? null : req.launchScope(), userId, now, req == null
                        ? null
                        : req.remark());
        repository.stopActiveReleases(tenantId, id, releaseId);
        int rows = repository.updatePublished(tenantId, id, model.bpmnXml(), validation
            .digest(), releaseId, releaseVersion, formVersion == null ? null : formVersion.formId(), formVersion == null
                ? null
                : req.formVersionId(), userId, now, model.version());
        if (rows == 0) {
            throw new BusinessException("模型已被其他人修改，请刷新后重试");
        }
        return new OaProcessPublishResp(true, List.of(), releaseId, releaseVersion, definition.getId(), definition
            .getVersion());
    }

    public OaProcessModelResp disable(long tenantId, long userId, long id) {
        return transactionTemplate.execute(status -> {
            OaProcessModelResp model = repository.lock(tenantId, id);
            if (model == null) {
                throw new BusinessException("流程模型不存在");
            }
            if ("DISABLED".equals(model.status())) {
                return model;
            }
            int rows = repository.markDisabled(tenantId, id, userId, LocalDateTime.now(), model.version());
            if (rows == 0) {
                throw new BusinessException("模型已被其他人修改，请刷新后重试");
            }
            repository.stopAllActiveReleases(tenantId, id);
            return repository.detail(tenantId, id);
        });
    }

    public OaBpmnValidationResult validate(String bpmnXml) {
        requireXmlSize(bpmnXml);
        return validator.validate(bpmnXml.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireXmlSize(String xml) {
        if (xml != null && xml.getBytes(StandardCharsets.UTF_8).length > MAX_XML_BYTES) {
            throw new BusinessException("BPMN XML 超过 512KB 限制");
        }
    }

    private static String digestOrNull(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
