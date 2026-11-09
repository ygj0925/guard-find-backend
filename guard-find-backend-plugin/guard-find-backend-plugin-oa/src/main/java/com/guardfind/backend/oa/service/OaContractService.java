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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.contract.OaContractHook;
import com.guardfind.backend.oa.contract.OaSealRequestHook;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.model.OaContractArchiveReq;
import com.guardfind.backend.oa.model.OaContractDetailResp;
import com.guardfind.backend.oa.model.OaContractResp;
import com.guardfind.backend.oa.model.OaContractSignReq;
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaLinkedFileResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaSealExecuteReq;
import com.guardfind.backend.oa.model.OaSealReq;
import com.guardfind.backend.oa.model.OaSealRequestResp;
import com.guardfind.backend.oa.model.OaSealResp;
import com.guardfind.backend.oa.repository.OaContractRepository;
import com.guardfind.backend.oa.repository.OaContractRepository.ContractRow;
import com.guardfind.backend.oa.repository.OaContractRepository.ExpiringContract;
import com.guardfind.backend.oa.repository.OaContractRepository.RequestFilter;
import com.guardfind.backend.oa.repository.OaContractRepository.SealRequestRow;
import com.guardfind.backend.oa.repository.OaContractRepository.SealRow;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.guardfind.backend.oa.service.OaPurchaseService.blankToNull;
import static com.guardfind.backend.oa.service.OaPurchaseService.field;
import static com.guardfind.backend.oa.service.OaPurchaseService.offset;
import static com.guardfind.backend.oa.service.OaPurchaseService.option;
import static com.guardfind.backend.oa.service.OaPurchaseService.requireNotFuture;
import static com.guardfind.backend.oa.service.OaPurchaseService.safeSize;

/**
 * 合同与用印：合同台账的签署/归档登记与到期提醒、印章档案与保管人、用印登记（只有保管人可执行）。
 * 审批通过只代表可以签署/用印，实际执行由对应经办人单独登记且按请求号幂等。
 */
@Service
public class OaContractService {

    public static final String CONTRACT_FORM_KEY = "contract_review";
    public static final String CONTRACT_MODEL_KEY = "contract_pilot";
    public static final String SEAL_FORM_KEY = "seal_use";
    public static final String SEAL_MODEL_KEY = "seal_pilot";
    public static final String EVENT_CONTRACT_SIGNED = "CONTRACT_SIGNED";
    public static final String EVENT_CONTRACT_EXPIRING = "CONTRACT_EXPIRING";
    public static final String EVENT_SEAL_USED = "SEAL_USED";
    public static final int EXPIRY_DAYS = 30;
    private static final int EXPIRY_SCAN_LIMIT = 2000;
    private static final String NOT_VISIBLE = "合同不存在或无权查看";
    private static final String REQUEST_NOT_VISIBLE = "用印申请不存在或无权查看";

    private static final Set<String> CONTRACT_STATUSES = Set
        .of(OaContractHook.STATUS_APPROVING, OaContractHook.STATUS_PENDING_SIGN, OaContractHook.STATUS_SIGNED, OaContractHook.STATUS_ARCHIVED, OaContractHook.STATUS_CLOSED);
    private static final Set<String> SEAL_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> REQUEST_STATUSES = Set
        .of(OaSealRequestHook.STATUS_APPROVING, OaSealRequestHook.STATUS_PENDING_SEAL, OaSealRequestHook.STATUS_USED, OaSealRequestHook.STATUS_CLOSED);

    private final OaContractRepository repository;
    private final OaStaffDirectory directory;
    private final OaAttachmentService attachmentService;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaContractService(OaContractRepository repository,
                             OaStaffDirectory directory,
                             OaAttachmentService attachmentService,
                             OaFormService formService,
                             OaProcessModelService modelService,
                             OaOutboxService outboxService,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.directory = directory;
        this.attachmentService = attachmentService;
        this.formService = formService;
        this.modelService = modelService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaContractResp> contracts(long tenantId,
                                          String status,
                                          String keyword,
                                          Long ownerId,
                                          Integer expiringDays,
                                          int page,
                                          int size) {
        int limit = safeSize(size);
        return repository
            .contracts(tenantId, checked(status, CONTRACT_STATUSES, "合同"), keyword, ownerId, expiringBefore(expiringDays), offset(page, limit), limit);
    }

    public long countContracts(long tenantId, String status, String keyword, Long ownerId, Integer expiringDays) {
        return repository
            .countContracts(tenantId, checked(status, CONTRACT_STATUSES, "合同"), keyword, ownerId, expiringBefore(expiringDays));
    }

    /**
     * viewerId 非空时只允许经办人查看本人合同。
     */
    public OaContractDetailResp contractDetail(long tenantId, Long viewerId, long id) {
        OaContractResp contract = visibleContract(tenantId, viewerId, id);
        List<OaLinkedFileResp> files = attachmentService.files(tenantId, repository
            .versionApplicationIds(tenantId, id));
        List<OaSealRequestResp> requests = repository
            .sealRequests(tenantId, new RequestFilter(null, null, null, id), 0, 100);
        return new OaContractDetailResp(contract, repository.versions(tenantId, id), files, requests);
    }

    public OaAttachmentService.DownloadAttachment contractFile(long tenantId, Long viewerId, long id, long fileId) {
        visibleContract(tenantId, viewerId, id);
        return attachmentService.authorizeApplicationDownload(tenantId, fileId, repository
            .versionApplicationIds(tenantId, id));
    }

    public OaContractResp sign(long tenantId, long operatorId, long id, OaContractSignReq req) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            ContractRow contract = requireContract(tenantId, id);
            Long replayed = repository.contractIdBySignRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            if (contract.ownerId() == operatorId) {
                throw new BusinessException("不能为本人经办的合同登记签署");
            }
            if (!OaContractHook.STATUS_PENDING_SIGN.equals(contract.status())) {
                throw new BusinessException(switch (contract.status()) {
                    case OaContractHook.STATUS_APPROVING -> "合同尚未审批通过，不能签署";
                    case OaContractHook.STATUS_CLOSED -> "合同已关闭，不能签署";
                    default -> "合同已签署";
                });
            }
            requireNotFuture(req.signedDate(), "签署日期");
            LocalDateTime now = LocalDateTime.now();
            try {
                if (repository.markSigned(tenantId, id, contract.currentVersion(), requestId, req.signedDate(), req
                    .signatories()
                    .trim(), blankToNull(req.remark()), operatorId, now) != 1) {
                    throw new BusinessException("合同状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(contract
                .currentApplicationId()), EVENT_CONTRACT_SIGNED, "SIGNED:" + id, contract
                    .currentApplicationId(), null, "CONTRACT_SIGNED", "合同已签署", "合同 " + contract
                        .contractNo() + "「" + contract.title() + "」已登记签署", List.of(contract.ownerId()));
        });
        return repository.contract(tenantId, id);
    }

    public OaContractResp archive(long tenantId, long operatorId, long id, OaContractArchiveReq req) {
        String requestId = req.requestId().trim();
        String archiveNo = req.archiveNo().trim();
        transactionTemplate.executeWithoutResult(status -> {
            ContractRow contract = requireContract(tenantId, id);
            Long replayed = repository.contractIdByArchiveRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            if (!OaContractHook.STATUS_SIGNED.equals(contract.status())) {
                throw new BusinessException(OaContractHook.STATUS_ARCHIVED.equals(contract.status())
                    ? "合同已归档"
                    : "合同当前状态为" + OaContractHook.label(contract.status()) + "，签署后才能归档");
            }
            Long used = repository.contractIdByArchiveNo(tenantId, archiveNo);
            if (used != null && used != id) {
                throw new BusinessException("档案号已被使用");
            }
            try {
                if (repository.markArchived(tenantId, id, requestId, archiveNo, blankToNull(req
                    .location()), operatorId, LocalDateTime.now()) != 1) {
                    throw new BusinessException("合同状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("档案号或请求号已被使用");
            }
        });
        return repository.contract(tenantId, id);
    }

    /**
     * 为到期日落在今天起 {@link #EXPIRY_DAYS} 天内的已签署/已归档合同提醒经办人，同一到期日只提醒一次。
     */
    public int scanExpiring(LocalDate today) {
        int recorded = 0;
        for (ExpiringContract contract : repository.expiring(today, today.plusDays(EXPIRY_DAYS), EXPIRY_SCAN_LIMIT)) {
            if (outboxService.recordNotification(contract.tenantId(), OaOutboxService.AGGREGATE_APPLICATION, Long
                .toString(contract.applicationId()), EVENT_CONTRACT_EXPIRING, "EXPIRY:" + contract.id() + ":" + contract
                    .endDate(), contract.applicationId(), null, "CONTRACT_EXPIRING", "合同即将到期", "合同 " + contract
                        .contractNo() + "「" + contract.title() + "」将于 " + contract.endDate() + " 到期", List.of(contract
                            .ownerId()))) {
                recorded++;
            }
        }
        return recorded;
    }

    public List<OaSealResp> seals(long tenantId, String status) {
        return repository.seals(tenantId, checked(status, SEAL_STATUSES, "印章"));
    }

    public OaSealResp createSeal(long tenantId, long userId, OaSealReq req) {
        requireKeeper(tenantId, req.keeperId());
        try {
            long id = repository.insertSeal(tenantId, req.code().trim(), req.name().trim(), req.sealType(), req
                .keeperId(), req.status() == null ? "ENABLED" : req.status(), blankToNull(req
                    .remark()), userId, LocalDateTime.now());
            return repository.seal(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("印章编码已存在");
        }
    }

    public OaSealResp updateSeal(long tenantId, long userId, long id, OaSealReq req) {
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        if (repository.seal(tenantId, id) == null) {
            throw new BusinessException("印章不存在");
        }
        requireKeeper(tenantId, req.keeperId());
        try {
            if (repository.updateSeal(tenantId, id, req.version(), req.code().trim(), req.name().trim(), req
                .sealType(), req.keeperId(), req.status() == null ? "ENABLED" : req.status(), blankToNull(req
                    .remark()), userId, LocalDateTime.now()) != 1) {
                throw new BusinessException("印章已被修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("印章编码已存在");
        }
        return repository.seal(tenantId, id);
    }

    public List<OaSealRequestResp> sealRequests(long tenantId,
                                                String status,
                                                Long applicantId,
                                                Long keeperId,
                                                int page,
                                                int size) {
        int limit = safeSize(size);
        return repository
            .sealRequests(tenantId, new RequestFilter(checked(status, REQUEST_STATUSES, "用印申请"), applicantId, keeperId, null), offset(page, limit), limit);
    }

    public long countSealRequests(long tenantId, String status, Long applicantId, Long keeperId) {
        return repository
            .countSealRequests(tenantId, new RequestFilter(checked(status, REQUEST_STATUSES, "用印申请"), applicantId, keeperId, null));
    }

    /**
     * applicantId / keeperId 非空时分别限定为申请人本人或当前保管人。
     */
    public List<OaLinkedFileResp> sealRequestFiles(long tenantId, Long applicantId, Long keeperId, long id) {
        OaSealRequestResp request = visibleRequest(tenantId, applicantId, keeperId, id);
        return attachmentService.files(tenantId, List.of(request.applicationId()));
    }

    public OaAttachmentService.DownloadAttachment sealRequestFile(long tenantId,
                                                                  Long applicantId,
                                                                  Long keeperId,
                                                                  long id,
                                                                  long fileId) {
        OaSealRequestResp request = visibleRequest(tenantId, applicantId, keeperId, id);
        return attachmentService.authorizeApplicationDownload(tenantId, fileId, List.of(request.applicationId()));
    }

    public OaSealRequestResp executeSeal(long tenantId, long operatorId, long id, OaSealExecuteReq req) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            SealRequestRow request = repository.lockSealRequest(tenantId, id);
            if (request == null) {
                throw new BusinessException("用印申请不存在");
            }
            Long replayed = repository.sealRequestIdByExecuteRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id || !OaSealRequestHook.STATUS_USED.equals(request.status())) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            SealRow seal = repository.lockSeal(tenantId, request.sealId());
            if (seal.keeperId() != operatorId) {
                throw new BusinessException("只有印章保管人可以登记用印");
            }
            if (!OaSealRequestHook.STATUS_PENDING_SEAL.equals(request.status())) {
                throw new BusinessException(switch (request.status()) {
                    case OaSealRequestHook.STATUS_APPROVING -> "用印尚未审批通过，不能登记";
                    case OaSealRequestHook.STATUS_CLOSED -> "用印申请已关闭";
                    default -> "该用印已登记";
                });
            }
            if (!"ENABLED".equals(seal.status())) {
                throw new BusinessException("印章已停用，不能登记用印");
            }
            if (req.usedCopies() > request.copies()) {
                throw new BusinessException("实际用印份数不能超过审批份数 " + request.copies());
            }
            requireNotFuture(req.usedDate(), "用印日期");
            LocalDateTime now = LocalDateTime.now();
            try {
                if (repository.markSealUsed(tenantId, id, requestId, req.usedCopies(), req.usedDate(), blankToNull(req
                    .remark()), operatorId, now) != 1) {
                    throw new BusinessException("用印状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(request
                .applicationId()), EVENT_SEAL_USED, "SEAL_USED:" + id, request
                    .applicationId(), null, "SEAL_USED", "用印已登记", "您申请的「" + seal.name() + "」已用印 " + req
                        .usedCopies() + " 份", List.of(request.applicantId()));
        });
        return repository.sealRequest(tenantId, id);
    }

    public OaContractTemplateResp contractTemplate(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            long formId = publishForm(tenantId, userId, CONTRACT_FORM_KEY, "合同审批", contractSchema());
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(CONTRACT_MODEL_KEY, "合同审批", "contract", "直属上级 → 法务岗位 → 财务岗位；金额超过 10 万元再由总经理岗位审批；通过后由合同管理员登记签署与归档", CONTRACT_BPMN, OaContractHook.BUSINESS_TYPE));
            return new OaContractTemplateResp(formId, model.id(), model.modelKey());
        });
    }

    public OaContractTemplateResp sealTemplate(long tenantId, long userId) {
        List<OaSealResp> enabled = repository.seals(tenantId, "ENABLED");
        if (enabled.isEmpty()) {
            throw new BusinessException("没有启用的印章，请先在印章档案中新建并启用");
        }
        return transactionTemplate.execute(status -> {
            long formId = publishForm(tenantId, userId, SEAL_FORM_KEY, "用印申请", sealSchema(enabled));
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(SEAL_MODEL_KEY, "用印申请", "contract", "直属上级审批；公章/法人章或超过 10 份再由总经理岗位审批；通过后由印章保管人登记用印", SEAL_BPMN, OaSealRequestHook.BUSINESS_TYPE));
            return new OaContractTemplateResp(formId, model.id(), model.modelKey());
        });
    }

    public String contractSchema() {
        return schema(List
            .of(field(OaContractHook.FIELD_TITLE, "合同名称", "text", true), field(OaContractHook.FIELD_COUNTERPARTY, "相对方", "text", true), withOptions(field(OaContractHook.FIELD_TYPE, "合同类型", "radio", true), List
                .of(option("销售合同", "SALES"), option("采购合同", "PURCHASE"), option("服务合同", "SERVICE"), option("其他", "OTHER"))), field(OaContractHook.FIELD_AMOUNT, "合同金额（元）", "number", true), field(OaContractHook.FIELD_START, "开始日期", "date", true), field(OaContractHook.FIELD_END, "结束日期", "date", true), field(OaContractHook.FIELD_SUMMARY, "主要条款摘要", "textarea", true), field(OaContractHook.FIELD_FILES, "合同文本", "attachment", true), field(OaContractHook.FIELD_BASE_NO, "原合同编号（修订时填写）", "text", false), field(OaContractHook.FIELD_CHANGE_NOTE, "修订说明", "textarea", false)));
    }

    public String sealSchema(List<OaSealResp> seals) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (OaSealResp seal : seals.subList(0, Math.min(seals.size(), OaFormSchemaValidator.MAX_OPTIONS))) {
            options.add(option(seal.name(), seal.code()));
        }
        return schema(List
            .of(withOptions(field(OaSealRequestHook.FIELD_SEAL, "印章", "radio", true), options), field(OaSealRequestHook.FIELD_PURPOSE, "用印事由", "textarea", true), field(OaSealRequestHook.FIELD_COPIES, "用印份数", "number", true), field(OaSealRequestHook.FIELD_USE_DATE, "用印日期", "date", true), field(OaSealRequestHook.FIELD_CONTRACT_NO, "关联合同编号", "text", false), field(OaSealRequestHook.FIELD_FILES, "用印文件", "attachment", false)));
    }

    static Map<String, Object> withOptions(Map<String, Object> field, List<Map<String, Object>> options) {
        field.put("options", options);
        return field;
    }

    private OaContractResp visibleContract(long tenantId, Long viewerId, long id) {
        OaContractResp contract = repository.contract(tenantId, id);
        if (contract == null || viewerId != null && contract.ownerId() != viewerId) {
            throw new BusinessException(NOT_VISIBLE);
        }
        return contract;
    }

    private OaSealRequestResp visibleRequest(long tenantId, Long applicantId, Long keeperId, long id) {
        OaSealRequestResp request = repository.sealRequest(tenantId, id);
        if (request == null || applicantId != null && request
            .applicantId() != applicantId || keeperId != null && request.keeperId() != keeperId) {
            throw new BusinessException(REQUEST_NOT_VISIBLE);
        }
        return request;
    }

    private ContractRow requireContract(long tenantId, long id) {
        ContractRow contract = repository.lockContract(tenantId, id);
        if (contract == null) {
            throw new BusinessException("合同不存在");
        }
        return contract;
    }

    private void requireKeeper(long tenantId, Long keeperId) {
        if (keeperId == null || !directory.activeEmployee(tenantId, keeperId)) {
            throw new BusinessException("保管人不是在职员工");
        }
    }

    private static LocalDate expiringBefore(Integer days) {
        if (days == null) {
            return null;
        }
        return LocalDate.now().plusDays(Math.min(366, Math.max(0, days)));
    }

    private long publishForm(long tenantId, long userId, String key, String name, String schema) {
        OaFormResp form = formService
            .create(tenantId, userId, new OaFormCreateReq(key, name, name + "试点模板，业务字段 key 不可修改", schema));
        OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
        if (!published.valid()) {
            throw new BusinessException(name + "表单发布失败：" + String.join("；", published.errors()));
        }
        return form.id();
    }

    private String schema(List<Map<String, Object>> fields) {
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("模板 schema 序列化失败", e);
        }
    }

    private static String checked(String status, Set<String> allowed, String label) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!allowed.contains(status)) {
            throw new BusinessException(label + "状态无效: " + status);
        }
        return status;
    }

    public static final String CONTRACT_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_contract" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="contract_pilot" name="合同审批" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToLegal</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:userTask id="legalApprove" name="法务审核" flowable:candidateUsers="${legalReviewer}">
              <bpmn:incoming>flowToLegal</bpmn:incoming>
              <bpmn:outgoing>flowToFinance</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:userTask id="financeApprove" name="财务审核" flowable:candidateUsers="${financeReviewer}">
              <bpmn:incoming>flowToFinance</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="valueGateway" name="金额超过 10 万元？" default="flowSmall">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowLarge</bpmn:outgoing>
              <bpmn:outgoing>flowSmall</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="gmApprove" name="总经理审批" flowable:candidateUsers="${finalApprover}">
              <bpmn:incoming>flowLarge</bpmn:incoming>
              <bpmn:outgoing>flowGmDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowGmDone</bpmn:incoming>
              <bpmn:incoming>flowSmall</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToLegal" sourceRef="supervisorApprove" targetRef="legalApprove"/>
            <bpmn:sequenceFlow id="flowToFinance" sourceRef="legalApprove" targetRef="financeApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="financeApprove" targetRef="valueGateway"/>
            <bpmn:sequenceFlow id="flowLarge" name="大于 10 万元" sourceRef="valueGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${contractValue &gt; 100000}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="10 万元及以内" sourceRef="valueGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_contract">
            <bpmndi:BPMNPlane id="BPMNPlane_contract" bpmnElement="contract_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="legalApprove_di" bpmnElement="legalApprove">
                <dc:Bounds x="390" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="financeApprove_di" bpmnElement="financeApprove">
                <dc:Bounds x="540" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="valueGateway_di" bpmnElement="valueGateway" isMarkerVisible="true">
                <dc:Bounds x="695" y="95" width="50" height="50"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="gmApprove_di" bpmnElement="gmApprove">
                <dc:Bounds x="800" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="962" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToLegal_di" bpmnElement="flowToLegal">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="390" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToFinance_di" bpmnElement="flowToFinance">
                <di:waypoint x="490" y="120"/>
                <di:waypoint x="540" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToGateway_di" bpmnElement="flowToGateway">
                <di:waypoint x="640" y="120"/>
                <di:waypoint x="695" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowLarge_di" bpmnElement="flowLarge">
                <di:waypoint x="745" y="120"/>
                <di:waypoint x="800" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowSmall_di" bpmnElement="flowSmall">
                <di:waypoint x="720" y="145"/>
                <di:waypoint x="720" y="220"/>
                <di:waypoint x="980" y="220"/>
                <di:waypoint x="980" y="138"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowGmDone_di" bpmnElement="flowGmDone">
                <di:waypoint x="900" y="120"/>
                <di:waypoint x="962" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;

    public static final String SEAL_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_seal" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="seal_pilot" name="用印申请" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="riskGateway" name="公章/法人章或超过 10 份？" default="flowSmall">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowLarge</bpmn:outgoing>
              <bpmn:outgoing>flowSmall</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="gmApprove" name="总经理审批" flowable:candidateUsers="${finalApprover}">
              <bpmn:incoming>flowLarge</bpmn:incoming>
              <bpmn:outgoing>flowGmDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowGmDone</bpmn:incoming>
              <bpmn:incoming>flowSmall</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="supervisorApprove" targetRef="riskGateway"/>
            <bpmn:sequenceFlow id="flowLarge" name="高风险" sourceRef="riskGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${sealRisk &gt; 0}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="普通" sourceRef="riskGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_seal">
            <bpmndi:BPMNPlane id="BPMNPlane_seal" bpmnElement="seal_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="riskGateway_di" bpmnElement="riskGateway" isMarkerVisible="true">
                <dc:Bounds x="395" y="95" width="50" height="50"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="gmApprove_di" bpmnElement="gmApprove">
                <dc:Bounds x="500" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="662" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToGateway_di" bpmnElement="flowToGateway">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="395" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowLarge_di" bpmnElement="flowLarge">
                <di:waypoint x="445" y="120"/>
                <di:waypoint x="500" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowSmall_di" bpmnElement="flowSmall">
                <di:waypoint x="420" y="145"/>
                <di:waypoint x="420" y="220"/>
                <di:waypoint x="680" y="220"/>
                <di:waypoint x="680" y="138"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowGmDone_di" bpmnElement="flowGmDone">
                <di:waypoint x="600" y="120"/>
                <di:waypoint x="662" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;
}
