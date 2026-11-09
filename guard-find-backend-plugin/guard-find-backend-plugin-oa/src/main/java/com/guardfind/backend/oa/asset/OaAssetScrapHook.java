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

package com.guardfind.backend.oa.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaAssetRepository;
import com.guardfind.backend.oa.repository.OaAssetRepository.AssetRow;
import com.guardfind.backend.oa.repository.OaAssetRepository.ScrapRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产报废钩子：发起时锁住资产卡片，拒绝已报废、在用或已有进行中报废的资产；通过后进入待报废，
 * 由资产管理员执行报废才真正改变卡片状态。
 */
@Component
public class OaAssetScrapHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "ASSET_SCRAP";
    public static final String FIELD_ASSET_NO = "assetNo";
    public static final String FIELD_REASON = "reason";
    public static final String VAR_VALUE = "assetValue";
    public static final String VAR_FINANCE_REVIEWER = "financeReviewer";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String FINANCE_POSITION = "FINANCE";
    public static final String GM_POSITION = "GM";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_SCRAP = "PENDING_SCRAP";
    public static final String STATUS_SCRAPPED = "SCRAPPED";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final String FORM_NAME = "报废表单";

    private final OaAssetRepository repository;

    public OaAssetScrapHook(OaAssetRepository repository) {
        this.repository = repository;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public List<String> validateForm(String schemaJson) {
        Map<String, JsonNode> fields = OaBusinessFields.fields(schemaJson);
        if (fields == null) {
            return List.of(FORM_NAME + " schema 无法解析");
        }
        List<String> errors = new ArrayList<>();
        OaBusinessFields.requireField(fields, FIELD_ASSET_NO, Set.of("text"), true, "资产编号", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_REASON, Set
            .of("text", "textarea"), true, "报废原因", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINANCE_REVIEWER, OaAssigneeSpec
            .position(FINANCE_POSITION), VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String assetNo = OaBusinessFields.requireText(formData.get(FIELD_ASSET_NO), "资产编号", 32);
        OaBusinessFields.requireText(formData.get(FIELD_REASON), "报废原因", 500);
        AssetRow asset = repository.lockAssetByNo(tenantId, assetNo);
        if (asset == null) {
            throw new BusinessException("资产编号 " + assetNo + " 不存在");
        }
        if (OaAssetStatus.SCRAPPED.equals(asset.status())) {
            throw new BusinessException("资产已报废");
        }
        if (OaAssetStatus.IN_USE.equals(asset.status())) {
            throw new BusinessException("资产在用，请先归还后再申请报废");
        }
        if (repository.activeScrapCount(tenantId, asset.id()) > 0) {
            throw new BusinessException("该资产已有进行中的报废申请");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_VALUE, asset.originalValue().doubleValue());
        return new OaBusinessPreparation(variables, asset.id());
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        repository.insertScrap(tenantId, applicationId, applicantId, (Long)preparation.state(), STATUS_APPROVING, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        ScrapRow scrap = repository.lockScrapByApplication(tenantId, applicationId);
        if (scrap == null || !STATUS_APPROVING.equals(scrap.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionScrap(tenantId, scrap.id(), STATUS_APPROVING, STATUS_PENDING_SCRAP, now);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionScrap(tenantId, scrap.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }
}
