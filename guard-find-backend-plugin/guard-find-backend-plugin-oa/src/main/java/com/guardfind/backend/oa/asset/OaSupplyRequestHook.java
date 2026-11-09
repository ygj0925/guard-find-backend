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
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.repository.OaAssetRepository;
import com.guardfind.backend.oa.repository.OaAssetRepository.SupplyRequestRow;
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
 * 用品领用钩子：发起时校验用品启用且当前库存足够（仅提示性检查，不预占），通过后进入待发放；
 * 库存只在管理员发放时按条件扣减，扣减不足整单回滚。
 */
@Component
public class OaSupplyRequestHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "SUPPLY_REQUEST";
    public static final String FIELD_ITEMS = "supplyItems";
    public static final String FIELD_REASON = "reason";
    public static final String COL_CODE = "itemCode";
    public static final String COL_QUANTITY = "quantity";
    public static final String VAR_ITEM_COUNT = "supplyItemCount";
    public static final String VAR_QUANTITY = "supplyQuantity";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_ISSUE = "PENDING_ISSUE";
    public static final String STATUS_ISSUED = "ISSUED";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final String FORM_NAME = "领用表单";
    private static final String ITEMS_LABEL = "领用明细";
    private static final int MAX_ROWS = 20;
    private static final int MAX_QUANTITY = 9999;

    private final OaAssetRepository repository;

    public OaSupplyRequestHook(OaAssetRepository repository) {
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
        Map<String, JsonNode> columns = OaBusinessFields
            .tableColumns(fields, FIELD_ITEMS, ITEMS_LABEL, FORM_NAME, errors);
        if (columns != null) {
            OaBusinessFields.requireColumn(columns, COL_CODE, Set.of("radio", "text"), true, "用品", ITEMS_LABEL, errors);
            OaBusinessFields.requireColumn(columns, COL_QUANTITY, Set.of("number"), true, "数量", ITEMS_LABEL, errors);
        }
        OaBusinessFields.requireField(fields, FIELD_REASON, Set
            .of("text", "textarea"), true, "领用事由", FORM_NAME, errors);
        return errors;
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        List<Map<?, ?>> rows = OaBusinessFields.rows(formData.get(FIELD_ITEMS), ITEMS_LABEL);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ITEMS_LABEL + "不能超过 " + MAX_ROWS + " 行");
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        int total = 0;
        int line = 0;
        for (Map<?, ?> cells : rows) {
            line++;
            String prefix = "第 " + line + " 行";
            String code = OaBusinessFields.text(cells.get(COL_CODE));
            if (code == null) {
                throw new BusinessException(prefix + "请选择用品");
            }
            OaSupplyItemResp item = repository.supplyItemByCode(tenantId, code, false);
            if (item == null || !"ENABLED".equals(item.status())) {
                throw new BusinessException(prefix + "用品 " + code + " 不存在或已停用");
            }
            if (quantities.containsKey(item.id())) {
                throw new BusinessException(prefix + "用品 " + item.name() + " 重复，请合并数量");
            }
            int quantity = OaBusinessFields.quantity(cells.get(COL_QUANTITY), prefix + "数量", MAX_QUANTITY);
            if (item.stock() < quantity) {
                throw new BusinessException(prefix + item.name() + "库存不足：当前 " + item.stock() + "，申领 " + quantity);
            }
            quantities.put(item.id(), quantity);
            total += quantity;
        }
        OaBusinessFields.requireText(formData.get(FIELD_REASON), "领用事由", 500);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_ITEM_COUNT, quantities.size());
        variables.put(VAR_QUANTITY, total);
        return new OaBusinessPreparation(variables, new SupplyPlan(Map.copyOf(quantities), total));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        SupplyPlan plan = (SupplyPlan)preparation.state();
        long requestId = repository.insertSupplyRequest(tenantId, applicationId, applicantId, plan.quantities()
            .size(), plan.total(), STATUS_APPROVING, now);
        plan.quantities()
            .forEach((itemId, quantity) -> repository.insertSupplyRequestItem(tenantId, requestId, itemId, quantity));
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        SupplyRequestRow request = repository.lockSupplyRequestByApplication(tenantId, applicationId);
        if (request == null || !STATUS_APPROVING.equals(request.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionSupplyRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_PENDING_ISSUE, now);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionSupplyRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    public record SupplyPlan(Map<Long, Integer> quantities, int total) {
    }
}
