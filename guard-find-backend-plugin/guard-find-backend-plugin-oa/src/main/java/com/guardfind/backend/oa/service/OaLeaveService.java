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
import com.guardfind.backend.oa.leave.OaLeaveDayCalculator;
import com.guardfind.backend.oa.leave.OaLeaveHook;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaLeaveBalanceResp;
import com.guardfind.backend.oa.model.OaLeaveGrantReq;
import com.guardfind.backend.oa.model.OaLeaveLedgerResp;
import com.guardfind.backend.oa.model.OaLeavePreviewResp;
import com.guardfind.backend.oa.model.OaLeaveRequestResp;
import com.guardfind.backend.oa.model.OaLeaveTemplateResp;
import com.guardfind.backend.oa.model.OaLeaveTypeReq;
import com.guardfind.backend.oa.model.OaLeaveTypeResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaWorkCalendarReq;
import com.guardfind.backend.oa.model.OaWorkCalendarResp;
import com.guardfind.backend.oa.repository.OaLeaveRepository;
import com.guardfind.backend.oa.repository.OaLeaveRepository.Balance;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假期管理：类型、工作日历、额度发放与台账、个人假期查询及请假模板初始化。
 */
@Service
public class OaLeaveService {

    public static final String TEMPLATE_FORM_KEY = "leave_request";
    public static final String TEMPLATE_MODEL_KEY = "leave_pilot";
    private static final int LEDGER_LIMIT = 200;

    private final OaLeaveRepository repository;
    private final OaLeaveHook hook;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaLeaveService(OaLeaveRepository repository,
                          OaLeaveHook hook,
                          OaFormService formService,
                          OaProcessModelService modelService,
                          ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.hook = hook;
        this.formService = formService;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaLeaveTypeResp> types(long tenantId, boolean enabledOnly) {
        return repository.types(tenantId, enabledOnly);
    }

    public OaLeaveTypeResp createType(long tenantId, long userId, OaLeaveTypeReq req) {
        String code = req.code() == null ? "" : req.code().trim();
        if (code.isEmpty()) {
            throw new BusinessException("类型编码不能为空");
        }
        if (repository.typeCodeExists(tenantId, code)) {
            throw new BusinessException("类型编码已存在");
        }
        try {
            long id = repository.insertType(tenantId, code, req.name().trim(), req.balanceRequired(), req
                .maxDaysPerRequest(), status(req.status()), sort(req.sort()), req.remark(), userId, LocalDateTime
                    .now());
            return requireType(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("类型编码已存在");
        }
    }

    public OaLeaveTypeResp updateType(long tenantId, long userId, long id, OaLeaveTypeReq req) {
        OaLeaveTypeResp current = requireType(tenantId, id);
        int version = req.version() == null ? current.version() : req.version();
        int rows = repository.updateType(tenantId, id, req.name().trim(), req.balanceRequired(), req
            .maxDaysPerRequest(), status(req.status()), sort(req.sort()), req.remark(), userId, LocalDateTime
                .now(), version);
        if (rows == 0) {
            throw new BusinessException("假期类型已被其他人修改，请刷新后重试");
        }
        return requireType(tenantId, id);
    }

    public List<OaWorkCalendarResp> calendar(long tenantId, int year) {
        return repository.calendar(tenantId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    public void saveCalendar(long tenantId, long userId, OaWorkCalendarReq req) {
        String name = req.name() == null || req.name().isBlank() ? null : req.name().trim();
        try {
            repository.upsertCalendar(tenantId, req.calendarDate(), req.dayType(), name, userId, LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该日期已被其他人登记，请刷新后重试");
        }
    }

    public void deleteCalendar(long tenantId, LocalDate date) {
        if (repository.deleteCalendar(tenantId, date) == 0) {
            throw new BusinessException("该日期未登记");
        }
    }

    public List<OaLeaveBalanceResp> balances(long tenantId,
                                             Integer year,
                                             String keyword,
                                             Long userId,
                                             int page,
                                             int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * safeSize;
        return repository.balances(tenantId, year, keyword, userId, offset, safeSize);
    }

    public long countBalances(long tenantId, Integer year, String keyword, Long userId) {
        return repository.countBalances(tenantId, year, keyword, userId);
    }

    public OaLeaveBalanceResp grant(long tenantId, long operatorId, OaLeaveGrantReq req) {
        if (req.days().signum() == 0) {
            throw new BusinessException("调整天数不能为 0");
        }
        if (req.days().multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0) {
            throw new BusinessException("调整天数必须是 0.5 的整数倍");
        }
        if (!repository.userExists(tenantId, req.userId())) {
            throw new BusinessException("员工不存在或不属于当前租户");
        }
        OaLeaveTypeResp type = requireType(tenantId, req.leaveTypeId());
        if (!type.balanceRequired()) {
            throw new BusinessException(type.name() + "不扣减余额，无需发放额度");
        }
        String remark = req.remark() == null || req.remark().isBlank() ? null : req.remark().trim();
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            repository.insertBalanceIfAbsent(tenantId, req.userId(), type.id(), req.periodYear(), now);
            Balance locked = repository.lockBalance(tenantId, req.userId(), type.id(), req.periodYear());
            if (repository.adjustTotal(tenantId, locked.id(), req.days(), now) != 1) {
                throw new BusinessException("调整后总额不能低于已扣减与审批中占用之和（当前已用 " + plain(locked
                    .usedDays()) + " 天、占用 " + plain(locked.occupiedDays()) + " 天）");
            }
            repository.insertLedger(tenantId, locked.id(), null, "GRANT", req.days(), repository
                .balanceById(tenantId, locked.id()), operatorId, remark, now);
            return repository.balanceDetail(tenantId, locked.id());
        });
    }

    public List<OaLeaveLedgerResp> ledger(long tenantId, long balanceId) {
        if (repository.balanceById(tenantId, balanceId) == null) {
            throw new BusinessException("余额记录不存在");
        }
        return repository.ledger(tenantId, balanceId, LEDGER_LIMIT);
    }

    public List<OaLeaveBalanceResp> myBalances(long tenantId, long userId, Integer year) {
        return repository.balances(tenantId, year, null, userId, 0, 100);
    }

    public List<OaLeaveRequestResp> myRequests(long tenantId, long userId, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * safeSize;
        return repository.requests(tenantId, userId, offset, safeSize);
    }

    public long countMyRequests(long tenantId, long userId) {
        return repository.countRequests(tenantId, userId);
    }

    public OaLeavePreviewResp preview(long tenantId, long userId, Map<String, Object> formData) {
        return hook.preview(tenantId, userId, formData == null ? Map.of() : formData);
    }

    /**
     * 初始化请假试点：缺省假期类型、已发布请假表单与 LEAVE 业务类型的草稿流程模型。
     */
    public OaLeaveTemplateResp template(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            if (repository.types(tenantId, false).isEmpty()) {
                repository.insertType(tenantId, "ANNUAL", "年假", true, null, "ENABLED", 10, "按年度发放额度", userId, now);
                repository.insertType(tenantId, "SICK", "病假", false, null, "ENABLED", 20, null, userId, now);
                repository.insertType(tenantId, "PERSONAL", "事假", false, null, "ENABLED", 30, null, userId, now);
            }
            List<OaLeaveTypeResp> enabled = repository.types(tenantId, true);
            if (enabled.isEmpty()) {
                throw new BusinessException("没有启用的假期类型，请先启用至少一个类型");
            }
            OaFormResp form = formService
                .create(tenantId, userId, new OaFormCreateReq(TEMPLATE_FORM_KEY, "请假单", "请假试点模板，字段 key 不可修改", templateSchema(enabled)));
            OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
            if (!published.valid()) {
                throw new BusinessException("请假表单发布失败：" + String.join("；", published.errors()));
            }
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(TEMPLATE_MODEL_KEY, "请假审批", "hr", "直属上级审批；超过 3 天再由 HR 岗位审批", TEMPLATE_BPMN, OaLeaveHook.BUSINESS_TYPE));
            return new OaLeaveTemplateResp(form.id(), published.versionId(), model.id(), model.modelKey());
        });
    }

    public String templateSchema(List<OaLeaveTypeResp> types) {
        List<Map<String, Object>> typeOptions = new ArrayList<>();
        for (OaLeaveTypeResp type : types) {
            typeOptions.add(option(type.name(), type.code()));
        }
        List<Map<String, Object>> halves = List
            .of(option("上午", OaLeaveDayCalculator.AM), option("下午", OaLeaveDayCalculator.PM));
        List<Map<String, Object>> fields = List
            .of(field(OaLeaveHook.FIELD_TYPE, "假期类型", "radio", true, typeOptions), field(OaLeaveHook.FIELD_START_DATE, "开始日期", "date", true, null), field(OaLeaveHook.FIELD_START_HALF, "开始时段", "radio", false, halves), field(OaLeaveHook.FIELD_END_DATE, "结束日期", "date", true, null), field(OaLeaveHook.FIELD_END_HALF, "结束时段", "radio", false, halves), field("reason", "请假事由", "textarea", true, null));
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("请假模板 schema 序列化失败", e);
        }
    }

    private static Map<String, Object> field(String key,
                                             String label,
                                             String type,
                                             boolean required,
                                             List<Map<String, Object>> options) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", "f_" + key);
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        if (options != null) {
            field.put("options", options);
        }
        return field;
    }

    private static Map<String, Object> option(String label, String value) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("label", label);
        option.put("value", value);
        return option;
    }

    private OaLeaveTypeResp requireType(long tenantId, long id) {
        OaLeaveTypeResp type = repository.type(tenantId, id);
        if (type == null) {
            throw new BusinessException("假期类型不存在");
        }
        return type;
    }

    private static String status(String status) {
        return status == null || status.isBlank() ? "ENABLED" : status;
    }

    private static int sort(Integer sort) {
        return sort == null ? 0 : sort;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public static final String TEMPLATE_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_leave" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="leave_pilot" name="请假审批" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="daysGateway" name="超过 3 天？" default="flowShort">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowLong</bpmn:outgoing>
              <bpmn:outgoing>flowShort</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="hrApprove" name="HR 审批" flowable:candidateUsers="${hrReviewer}">
              <bpmn:incoming>flowLong</bpmn:incoming>
              <bpmn:outgoing>flowHrDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowHrDone</bpmn:incoming>
              <bpmn:incoming>flowShort</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="supervisorApprove" targetRef="daysGateway"/>
            <bpmn:sequenceFlow id="flowLong" name="大于 3 天" sourceRef="daysGateway" targetRef="hrApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${leaveDays &gt; 3}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowShort" name="3 天及以内" sourceRef="daysGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowHrDone" sourceRef="hrApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_leave">
            <bpmndi:BPMNPlane id="BPMNPlane_leave" bpmnElement="leave_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="daysGateway_di" bpmnElement="daysGateway" isMarkerVisible="true">
                <dc:Bounds x="395" y="95" width="50" height="50"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="hrApprove_di" bpmnElement="hrApprove">
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
              <bpmndi:BPMNEdge id="flowLong_di" bpmnElement="flowLong">
                <di:waypoint x="445" y="120"/>
                <di:waypoint x="500" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowShort_di" bpmnElement="flowShort">
                <di:waypoint x="420" y="145"/>
                <di:waypoint x="420" y="220"/>
                <di:waypoint x="680" y="220"/>
                <di:waypoint x="680" y="138"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowHrDone_di" bpmnElement="flowHrDone">
                <di:waypoint x="600" y="120"/>
                <di:waypoint x="662" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;
}
