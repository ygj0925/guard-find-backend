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

package com.guardfind.backend.oa.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaContractRepository;
import com.guardfind.backend.oa.repository.OaContractRepository.ContractRow;
import com.guardfind.backend.oa.repository.OaContractRepository.ContractValues;
import com.guardfind.backend.oa.repository.OaContractRepository.VersionRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合同审批钩子：新合同落台账与第 1 版；填写原合同编号时为修订，只允许经办人对已关闭或待签署的合同送审新版本。
 * 审批通过只进入待签署，签署与归档由合同管理员在台账中登记。
 */
@Component
public class OaContractHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "CONTRACT";
    public static final String FIELD_TITLE = "contractTitle";
    public static final String FIELD_COUNTERPARTY = "counterparty";
    public static final String FIELD_TYPE = "contractType";
    public static final String FIELD_AMOUNT = "contractAmount";
    public static final String FIELD_START = "startDate";
    public static final String FIELD_END = "endDate";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_FILES = "contractFiles";
    public static final String FIELD_BASE_NO = "baseContractNo";
    public static final String FIELD_CHANGE_NOTE = "changeNote";
    public static final String VAR_VALUE = "contractValue";
    public static final String VAR_LEGAL_REVIEWER = "legalReviewer";
    public static final String VAR_FINANCE_REVIEWER = "financeReviewer";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String LEGAL_POSITION = "LEGAL";
    public static final String FINANCE_POSITION = "FINANCE";
    public static final String GM_POSITION = "GM";
    public static final Set<String> TYPES = Set.of("SALES", "PURCHASE", "SERVICE", "OTHER");

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_SIGN = "PENDING_SIGN";
    public static final String STATUS_SIGNED = "SIGNED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String VERSION_APPROVED = "APPROVED";

    private static final String FORM_NAME = "合同表单";
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final OaContractRepository repository;

    public OaContractHook(OaContractRepository repository) {
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
        OaBusinessFields.requireField(fields, FIELD_TITLE, Set.of("text"), true, "合同名称", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_COUNTERPARTY, Set.of("text"), true, "相对方", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_TYPE, Set.of("radio"), true, "合同类型", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_AMOUNT, Set.of("number"), true, "合同金额", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_START, Set.of("date"), true, "开始日期", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_END, Set.of("date"), true, "结束日期", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_SUMMARY, Set.of("textarea"), true, "主要条款摘要", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_FILES, Set.of("attachment"), true, "合同文本", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_BASE_NO, Set.of("text"), false, "原合同编号", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_CHANGE_NOTE, Set
            .of("text", "textarea"), false, "修订说明", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_LEGAL_REVIEWER, OaAssigneeSpec.position(LEGAL_POSITION), VAR_FINANCE_REVIEWER, OaAssigneeSpec
            .position(FINANCE_POSITION), VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String title = OaBusinessFields.requireText(formData.get(FIELD_TITLE), "合同名称", 200);
        String counterparty = OaBusinessFields.requireText(formData.get(FIELD_COUNTERPARTY), "相对方", 200);
        String type = OaBusinessFields.requireText(formData.get(FIELD_TYPE), "合同类型", 20);
        if (!TYPES.contains(type)) {
            throw new BusinessException("合同类型无效");
        }
        ContractValues values = new ContractValues(title, counterparty, type, OaBusinessFields.amount(formData
            .get(FIELD_AMOUNT), "合同金额"), OaBusinessFields.date(formData
                .get(FIELD_START), "开始日期", true), OaBusinessFields.date(formData.get(FIELD_END), "结束日期", true));
        if (values.endDate().isBefore(values.startDate())) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        String summary = OaBusinessFields.requireText(formData.get(FIELD_SUMMARY), "主要条款摘要", 2000);
        if (!(formData.get(FIELD_FILES) instanceof Collection<?> files) || files.isEmpty()) {
            throw new BusinessException("请上传合同文本");
        }
        String changeNote = OaBusinessFields.text(formData.get(FIELD_CHANGE_NOTE));
        if (changeNote != null && changeNote.length() > 500) {
            throw new BusinessException("修订说明不能超过 500 个字符");
        }
        String baseNo = OaBusinessFields.text(formData.get(FIELD_BASE_NO));
        Long contractId = null;
        int fromVersion = 0;
        if (baseNo != null) {
            ContractRow base = repository.lockContractByNo(tenantId, baseNo);
            if (base == null) {
                throw new BusinessException("合同编号 " + baseNo + " 不存在");
            }
            if (base.ownerId() != applicantId) {
                throw new BusinessException("只能修订本人经办的合同");
            }
            if (!STATUS_CLOSED.equals(base.status()) && !STATUS_PENDING_SIGN.equals(base.status())) {
                throw new BusinessException("合同当前状态为" + label(base.status()) + "，不能发起修订");
            }
            if (changeNote == null) {
                throw new BusinessException("请填写修订说明");
            }
            contractId = base.id();
            fromVersion = base.currentVersion();
        }
        return new OaBusinessPreparation(Map.of(VAR_VALUE, values.amount()
            .doubleValue()), new Plan(values, summary, changeNote, contractId, fromVersion));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        Plan plan = (Plan)preparation.state();
        if (plan.contractId() == null) {
            long id = repository.insertContract(tenantId, plan.values(), applicantId, applicationId, now);
            repository.assignContractNo(tenantId, id, "HT" + now.format(NO_DATE) + String.format("%04d", id));
            repository.insertVersion(tenantId, id, 1, applicationId, plan.values(), plan.summary(), plan
                .changeNote(), applicantId, now);
            return;
        }
        if (repository.reviseContract(tenantId, plan.contractId(), plan.fromVersion(), plan
            .values(), applicationId, now) != 1) {
            throw new BusinessException("合同状态已变化，请刷新后重试");
        }
        repository.insertVersion(tenantId, plan.contractId(), plan.fromVersion() + 1, applicationId, plan.values(), plan
            .summary(), plan.changeNote(), applicantId, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        VersionRow version = repository.lockVersionByApplication(tenantId, applicationId);
        if (version == null || !STATUS_APPROVING.equals(version.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionVersion(tenantId, version.id(), STATUS_APPROVING, VERSION_APPROVED, now);
            repository.transitionContract(tenantId, version
                .contractId(), applicationId, STATUS_APPROVING, STATUS_PENDING_SIGN, now);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionVersion(tenantId, version.id(), STATUS_APPROVING, status, now);
            repository.transitionContract(tenantId, version
                .contractId(), applicationId, STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    public static String label(String status) {
        return switch (status) {
            case STATUS_APPROVING -> "审批中";
            case STATUS_PENDING_SIGN -> "待签署";
            case STATUS_SIGNED -> "已签署";
            case STATUS_ARCHIVED -> "已归档";
            case STATUS_CLOSED -> "已关闭";
            default -> status;
        };
    }

    /**
     * 表单解析后的合同快照；contractId 为空表示新合同，否则为对该合同 fromVersion 的修订。
     */
    record Plan(ContractValues values, String summary, String changeNote, Long contractId, int fromVersion) {
    }
}
