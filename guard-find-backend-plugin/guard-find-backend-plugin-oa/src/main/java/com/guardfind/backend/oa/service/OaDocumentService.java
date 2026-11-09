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
import com.guardfind.backend.oa.document.OaDocumentHook;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaDocumentDetailResp;
import com.guardfind.backend.oa.model.OaDocumentInboxResp;
import com.guardfind.backend.oa.model.OaDocumentReadResp;
import com.guardfind.backend.oa.model.OaDocumentResp;
import com.guardfind.backend.oa.model.OaDocumentRevokeReq;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.repository.OaDocumentRepository;
import com.guardfind.backend.oa.repository.OaDocumentRepository.DeptRow;
import com.guardfind.backend.oa.repository.OaDocumentRepository.DocumentRow;
import com.guardfind.backend.oa.repository.OaDocumentRepository.PublishedVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.guardfind.backend.oa.service.OaContractService.withOptions;
import static com.guardfind.backend.oa.service.OaPurchaseService.field;
import static com.guardfind.backend.oa.service.OaPurchaseService.offset;
import static com.guardfind.backend.oa.service.OaPurchaseService.option;
import static com.guardfind.backend.oa.service.OaPurchaseService.safeSize;

/**
 * 公文与知识：管理台账（版本、回执统计、撤稿）与员工收件箱（阅读、签收）。
 * 员工只能通过本人在已发布版本上的回执访问正文与附件，撤稿或修订后旧链接失效。
 */
@Service
public class OaDocumentService {

    public static final String FORM_KEY = "document_publish";
    public static final String MODEL_KEY = "document_pilot";
    private static final String NOT_VISIBLE = "公文不存在或无权查看";
    private static final Set<String> STATUSES = Set
        .of(OaDocumentHook.STATUS_APPROVING, OaDocumentHook.STATUS_PUBLISHED, OaDocumentHook.STATUS_CLOSED, OaDocumentHook.STATUS_REVOKED);
    private static final Set<String> TYPES = Set.of(OaDocumentHook.TYPE_NOTICE, OaDocumentHook.TYPE_KNOWLEDGE);

    private final OaDocumentRepository repository;
    private final OaAttachmentService attachmentService;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaDocumentService(OaDocumentRepository repository,
                             OaAttachmentService attachmentService,
                             OaFormService formService,
                             OaProcessModelService modelService,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.attachmentService = attachmentService;
        this.formService = formService;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaDocumentResp> documents(long tenantId,
                                          String status,
                                          String docType,
                                          String keyword,
                                          int page,
                                          int size) {
        int limit = safeSize(size);
        return repository
            .documents(tenantId, checked(status, STATUSES, "公文状态"), checked(docType, TYPES, "公文类型"), keyword, offset(page, limit), limit);
    }

    public long countDocuments(long tenantId, String status, String docType, String keyword) {
        return repository
            .countDocuments(tenantId, checked(status, STATUSES, "公文状态"), checked(docType, TYPES, "公文类型"), keyword);
    }

    public OaDocumentDetailResp detail(long tenantId, long id) {
        OaDocumentResp document = requireDocument(tenantId, id);
        return new OaDocumentDetailResp(document, repository.versions(tenantId, id), document.publishedVersion() > 0
            ? repository.receipts(tenantId, id, document.publishedVersion())
            : List.of(), attachmentService.files(tenantId, repository.versionApplicationIds(tenantId, id)));
    }

    public OaAttachmentService.DownloadAttachment documentFile(long tenantId, long id, long fileId) {
        requireDocument(tenantId, id);
        return attachmentService.authorizeApplicationDownload(tenantId, fileId, repository
            .versionApplicationIds(tenantId, id));
    }

    public OaDocumentResp revoke(long tenantId, long userId, long id, OaDocumentRevokeReq req) {
        transactionTemplate.executeWithoutResult(status -> {
            DocumentRow document = repository.lockDocument(tenantId, id);
            if (document == null) {
                throw new BusinessException("公文不存在");
            }
            if (!OaDocumentHook.STATUS_PUBLISHED.equals(document.status())) {
                throw new BusinessException("公文当前状态为" + OaDocumentHook.label(document.status()) + "，不能撤稿");
            }
            if (document.pendingApplicationId() != null) {
                throw new BusinessException("公文有审批中的修订，不能撤稿");
            }
            if (repository.revoke(tenantId, id, userId, req.reason().trim(), LocalDateTime.now()) != 1) {
                throw new BusinessException("公文状态已变化，请刷新后重试");
            }
        });
        return repository.document(tenantId, id);
    }

    public List<OaDocumentInboxResp> inbox(long tenantId,
                                           long userId,
                                           String keyword,
                                           boolean pendingOnly,
                                           int page,
                                           int size) {
        int limit = safeSize(size);
        return repository.inbox(tenantId, userId, keyword, pendingOnly, offset(page, limit), limit);
    }

    public long countInbox(long tenantId, long userId, String keyword, boolean pendingOnly) {
        return repository.countInbox(tenantId, userId, keyword, pendingOnly);
    }

    /**
     * 打开即记首次阅读时间；不在本版本回执内（含撤稿、非受众）一律按不存在处理。
     */
    public OaDocumentReadResp read(long tenantId, long userId, long id) {
        OaDocumentInboxResp item = requireInboxItem(tenantId, userId, id);
        repository.markRead(tenantId, id, item.versionNo(), userId, LocalDateTime.now());
        PublishedVersion published = repository.publishedVersion(tenantId, id);
        return new OaDocumentReadResp(repository.inboxItem(tenantId, userId, id), published.content(), published
            .changeNote(), attachmentService.files(tenantId, List.of(published.applicationId())));
    }

    public OaDocumentInboxResp ack(long tenantId, long userId, long id) {
        OaDocumentInboxResp item = requireInboxItem(tenantId, userId, id);
        if (!item.requireAck()) {
            throw new BusinessException("该公文无需签收");
        }
        repository.markAck(tenantId, id, item.versionNo(), userId, LocalDateTime.now());
        return repository.inboxItem(tenantId, userId, id);
    }

    public OaAttachmentService.DownloadAttachment inboxFile(long tenantId, long userId, long id, long fileId) {
        requireInboxItem(tenantId, userId, id);
        PublishedVersion published = repository.publishedVersion(tenantId, id);
        return attachmentService.authorizeApplicationDownload(tenantId, fileId, List.of(published.applicationId()));
    }

    public OaContractTemplateResp template(long tenantId, long userId) {
        String schema = documentSchema(repository.depts(tenantId));
        return transactionTemplate.execute(status -> {
            OaFormResp form = formService
                .create(tenantId, userId, new OaFormCreateReq(FORM_KEY, "公文发布", "公文发布试点模板，业务字段 key 不可修改", schema));
            OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
            if (!published.valid()) {
                throw new BusinessException("公文发布表单发布失败：" + String.join("；", published.errors()));
            }
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(MODEL_KEY, "公文发布", "document", "直属上级审批；通知公告再由总经理岗位审批；通过后按发布范围生成阅读回执", BPMN, OaDocumentHook.BUSINESS_TYPE));
            return new OaContractTemplateResp(form.id(), model.id(), model.modelKey());
        });
    }

    public String documentSchema(List<DeptRow> depts) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(withOptions(field(OaDocumentHook.FIELD_TYPE, "类型", "radio", true), List
            .of(option("通知公告", OaDocumentHook.TYPE_NOTICE), option("知识文章", OaDocumentHook.TYPE_KNOWLEDGE))));
        fields.add(field(OaDocumentHook.FIELD_TITLE, "标题", "text", true));
        fields.add(field(OaDocumentHook.FIELD_CATEGORY, "分类", "text", false));
        fields.add(field(OaDocumentHook.FIELD_CONTENT, "正文", "textarea", true));
        fields.add(withOptions(field(OaDocumentHook.FIELD_SCOPE, "发布范围", "radio", true), List
            .of(option("全员", OaDocumentHook.SCOPE_ALL), option("指定部门或人员", OaDocumentHook.SCOPE_CUSTOM))));
        if (!depts.isEmpty()) {
            fields
                .add(withOptions(field(OaDocumentHook.FIELD_DEPTS, "发布部门（含下级）", "checkbox", false), deptOptions(depts)));
        }
        fields.add(field(OaDocumentHook.FIELD_USERS, "发布人员", "person", false));
        fields.add(withOptions(field(OaDocumentHook.FIELD_REQUIRE_ACK, "是否需要签收", "radio", true), List
            .of(option("需要签收", "Y"), option("仅需阅读", "N"))));
        fields.add(field(OaDocumentHook.FIELD_FILES, "附件", "attachment", false));
        fields.add(field(OaDocumentHook.FIELD_BASE_NO, "原文号（修订时填写）", "text", false));
        fields.add(field(OaDocumentHook.FIELD_CHANGE_NOTE, "修订说明", "textarea", false));
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("公文模板 schema 序列化失败", e);
        }
    }

    private static List<Map<String, Object>> deptOptions(List<DeptRow> depts) {
        Map<Long, DeptRow> byId = new HashMap<>();
        depts.forEach(dept -> byId.put(dept.id(), dept));
        List<Map<String, Object>> options = new ArrayList<>();
        for (DeptRow dept : depts.subList(0, Math.min(depts.size(), OaFormSchemaValidator.MAX_OPTIONS))) {
            options.add(option(path(dept, byId), Long.toString(dept.id())));
        }
        return options;
    }

    private static String path(DeptRow dept, Map<Long, DeptRow> byId) {
        List<String> names = new ArrayList<>();
        DeptRow current = dept;
        while (current != null && names.size() < 10) {
            names.add(0, current.name());
            current = byId.get(current.parentId());
        }
        return String.join("/", names);
    }

    private OaDocumentResp requireDocument(long tenantId, long id) {
        OaDocumentResp document = repository.document(tenantId, id);
        if (document == null) {
            throw new BusinessException("公文不存在");
        }
        return document;
    }

    private OaDocumentInboxResp requireInboxItem(long tenantId, long userId, long id) {
        OaDocumentInboxResp item = repository.inboxItem(tenantId, userId, id);
        if (item == null) {
            throw new BusinessException(NOT_VISIBLE);
        }
        return item;
    }

    private static String checked(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw new BusinessException(label + "无效: " + value);
        }
        return value;
    }

    public static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_document" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="document_pilot" name="公文发布" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="typeGateway" name="通知公告？" default="flowKnowledge">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowNotice</bpmn:outgoing>
              <bpmn:outgoing>flowKnowledge</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="gmApprove" name="总经理审批" flowable:candidateUsers="${finalApprover}">
              <bpmn:incoming>flowNotice</bpmn:incoming>
              <bpmn:outgoing>flowGmDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowGmDone</bpmn:incoming>
              <bpmn:incoming>flowKnowledge</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="supervisorApprove" targetRef="typeGateway"/>
            <bpmn:sequenceFlow id="flowNotice" name="通知公告" sourceRef="typeGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${docNotice &gt; 0}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowKnowledge" name="知识文章" sourceRef="typeGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_document">
            <bpmndi:BPMNPlane id="BPMNPlane_document" bpmnElement="document_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="typeGateway_di" bpmnElement="typeGateway" isMarkerVisible="true">
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
              <bpmndi:BPMNEdge id="flowNotice_di" bpmnElement="flowNotice">
                <di:waypoint x="445" y="120"/>
                <di:waypoint x="500" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowKnowledge_di" bpmnElement="flowKnowledge">
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
