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

package com.guardfind.backend.oa.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.guardfind.backend.oa.business.OaBusinessFields;
import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaTravelRepository;
import com.guardfind.backend.oa.repository.OaTravelRepository.LoanRow;
import com.guardfind.backend.oa.repository.OaTravelRepository.TravelRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 借款申请钩子：可关联本人已通过的出差，关联时借款合计（审批中与未关闭）不得超过出差预算；
 * 通过后进入待放款，由财务登记放款后才产生余额，驳回/撤回/终止时关闭。
 */
@Component
public class OaLoanHook implements OaBusinessHook {

    public static final String BUSINESS_TYPE = "LOAN";
    public static final String FIELD_AMOUNT = "amount";
    public static final String FIELD_TRAVEL_NO = "travelNo";
    public static final String FIELD_REASON = "reason";
    public static final String FIELD_REPAY_DATE = "expectedRepayDate";
    public static final String VAR_AMOUNT = "loanAmount";
    public static final String VAR_FINANCE_REVIEWER = "financeReviewer";
    public static final String VAR_FINAL_APPROVER = "finalApprover";
    public static final String FINANCE_POSITION = "FINANCE";
    public static final String GM_POSITION = "GM";

    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_PENDING_DISBURSE = "PENDING_DISBURSE";
    public static final String STATUS_OUTSTANDING = "OUTSTANDING";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final String FORM_NAME = "借款表单";

    private final OaTravelRepository repository;

    public OaLoanHook(OaTravelRepository repository) {
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
        OaBusinessFields.requireField(fields, FIELD_AMOUNT, Set.of("number"), true, "借款金额", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_REASON, Set
            .of("text", "textarea"), true, "借款事由", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_TRAVEL_NO, Set.of("text"), false, "关联出差单号", FORM_NAME, errors);
        OaBusinessFields.requireField(fields, FIELD_REPAY_DATE, Set.of("date"), false, "预计还款日期", FORM_NAME, errors);
        return errors;
    }

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_FINANCE_REVIEWER, OaAssigneeSpec
            .position(FINANCE_POSITION), VAR_FINAL_APPROVER, OaAssigneeSpec.position(GM_POSITION));
    }

    @Override
    public OaBusinessPreparation prepare(long tenantId, long applicantId, Map<String, Object> formData) {
        BigDecimal amount = OaBusinessFields.amount(formData.get(FIELD_AMOUNT), "借款金额");
        OaBusinessFields.requireText(formData.get(FIELD_REASON), "借款事由", 500);
        LocalDate repayDate = OaBusinessFields.date(formData.get(FIELD_REPAY_DATE), "预计还款日期", false);
        if (repayDate != null && repayDate.isBefore(LocalDate.now())) {
            throw new BusinessException("预计还款日期不能早于今天");
        }
        Long travelId = null;
        String travelNo = OaBusinessFields.text(formData.get(FIELD_TRAVEL_NO));
        if (travelNo != null) {
            TravelRow travel = approvedTravel(repository, tenantId, applicantId, travelNo, true);
            BigDecimal total = repository.activeLoanTotal(tenantId, travel.id(), true).add(amount);
            if (total.compareTo(travel.budget()) > 0) {
                throw new BusinessException("借款合计 " + OaBusinessFields.plain(total) + " 元超出出差预算 " + OaBusinessFields
                    .plain(travel.budget()) + " 元");
            }
            travelId = travel.id();
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(VAR_AMOUNT, amount.doubleValue());
        return new OaBusinessPreparation(variables, new LoanPlan(amount, travelId, repayDate));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        LoanPlan plan = (LoanPlan)preparation.state();
        repository.insertLoan(tenantId, applicationId, applicantId, plan.travelId(), plan.amount(), plan
            .expectedRepayDate(), STATUS_APPROVING, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        LoanRow loan = repository.lockLoanByApplication(tenantId, applicationId);
        if (loan == null || !STATUS_APPROVING.equals(loan.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            repository.transitionLoan(tenantId, loan.id(), STATUS_APPROVING, STATUS_PENDING_DISBURSE, now);
        } else if (OaTravelHook.CLOSE_STATUSES.contains(status)) {
            repository.transitionLoan(tenantId, loan.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    /**
     * 返回申请人本人已通过的出差；发起时锁住出差行以串行化同一出差下的借款与报销。
     */
    public static TravelRow approvedTravel(OaTravelRepository repository,
                                           long tenantId,
                                           long applicantId,
                                           String travelNo,
                                           boolean lock) {
        TravelRow travel = repository.travelByNo(tenantId, travelNo.trim(), lock);
        if (travel == null || travel.applicantId() != applicantId) {
            throw new BusinessException("出差单 " + travelNo + " 不存在或不属于本人");
        }
        if (!OaTravelHook.STATUS_APPROVED.equals(travel.status())) {
            throw new BusinessException("出差单 " + travelNo + " 尚未审批通过");
        }
        return travel;
    }

    public record LoanPlan(BigDecimal amount, Long travelId, LocalDate expectedRepayDate) {
    }
}
