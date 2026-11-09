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
import com.guardfind.backend.oa.repository.OaContractRepository.SealRequestRow;
import com.guardfind.backend.oa.repository.OaContractRepository.SealRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import com.guardfind.backend.oa.travel.OaTravelHook;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用印申请钩子：发起时锁住印章，要求印章启用且保管人在职；公章/法人章或超过 10 份需总经理审批。
 * 审批通过只进入待用印，由印章保管人登记实际用印。
 */
@Component
public class OaSealRequestHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "SEAL_USE";
    public static final String FIELD_SEAL = "sealCode";
    public static final String FIELD_PURPOSE = "purpose";
    public static final String FIELD_COPIES = "copies";
    public static final String FIELD_USE_DATE = "useDate";
    public static final String FIELD_CONTRACT_NO = "contractNo";
    public static final String FIELD_FILES = "sealFiles";
    public static final String VAR_RISK = "sealRisk";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String GM_POSITION = "GM";
    public static final int MAX_COPIES = 100;
    public static final int GM_COPIES = 10;
    public static final Set<String> HIGH_RISK_TYPES = Set.of("OFFICIAL", "LEGAL_REP");

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_SEAL = "PENDING_SEAL";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final Set<String> CONTRACT_SEALABLE = Set
        .of(OaContractHook.STATUS_PENDING_SIGN, OaContractHook.STATUS_SIGNED, OaContractHook.STATUS_ARCHIVED);
    private static final String FORM_NAME = "用印表单";

    private final OaContractRepository repository;
    private final OaStaffDirectory directory;

    public OaSealRequestHook(OaContractRepository repository, OaStaffDirectory directory) {
        this.repository = repository;
        this.directory = directory;
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
        OaBusinessFields.requireField(fields, FIELD_SEAL, Set.of("radio"), true, "印章", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_PURPOSE, Set
            .of("text", "textarea"), true, "用印事由", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_COPIES, Set.of("number"), true, "用印份数", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_USE_DATE, Set.of("date"), true, "用印日期", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_CONTRACT_NO, Set.of("text"), false, "关联合同编号", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_FILES, Set.of("attachment"), false, "用印文件", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        String code = OaBusinessFields.requireText(formData.get(FIELD_SEAL), "印章", 32);
        OaBusinessFields.requireText(formData.get(FIELD_PURPOSE), "用印事由", 500);
        int copies = OaBusinessFields.quantity(formData.get(FIELD_COPIES), "用印份数", MAX_COPIES);
        LocalDate useDate = OaBusinessFields.date(formData.get(FIELD_USE_DATE), "用印日期", true);
        if (useDate.isBefore(LocalDate.now())) {
            throw new BusinessException("用印日期不能早于今天");
        }
        SealRow seal = repository.lockSealByCode(tenantId, code);
        if (seal == null) {
            throw new BusinessException("印章 " + code + " 不存在");
        }
        if (!"ENABLED".equals(seal.status())) {
            throw new BusinessException("印章「" + seal.name() + "」已停用");
        }
        if (!directory.activeEmployee(tenantId, seal.keeperId())) {
            throw new BusinessException("印章「" + seal.name() + "」的保管人不在职，请联系管理员更换保管人");
        }
        Long contractId = null;
        String contractNo = OaBusinessFields.text(formData.get(FIELD_CONTRACT_NO));
        if (contractNo != null) {
            ContractRow contract = repository.lockContractByNo(tenantId, contractNo);
            if (contract == null || contract.ownerId() != applicantId) {
                throw new BusinessException("合同编号 " + contractNo + " 不存在或不是本人经办");
            }
            if (!CONTRACT_SEALABLE.contains(contract.status())) {
                throw new BusinessException("合同当前状态为" + OaContractHook.label(contract.status()) + "，不能申请用印");
            }
            contractId = contract.id();
        }
        int risk = HIGH_RISK_TYPES.contains(seal.sealType()) || copies > GM_COPIES ? 1 : 0;
        return new OaBusinessPreparation(Map.of(VAR_RISK, risk), new Plan(seal.id(), contractId, copies, useDate));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        Plan plan = (Plan)preparation.state();
        repository.insertSealRequest(tenantId, applicationId, applicantId, plan.sealId(), plan.contractId(), plan
            .copies(), plan.useDate(), now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        SealRequestRow request = repository.lockSealRequestByApplication(tenantId, applicationId);
        if (request == null || !STATUS_APPROVING.equals(request.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionSealRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_PENDING_SEAL, now);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionSealRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    record Plan(long sealId, Long contractId, int copies, LocalDate useDate) {
    }
}
