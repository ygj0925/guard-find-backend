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

package com.guardfind.backend.oa.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.oa.model.OaLeaveBalanceResp;
import com.guardfind.backend.oa.model.OaLeaveGrantReq;
import com.guardfind.backend.oa.model.OaLeaveLedgerResp;
import com.guardfind.backend.oa.model.OaLeavePreviewResp;
import com.guardfind.backend.oa.model.OaLeaveRequestResp;
import com.guardfind.backend.oa.model.OaLeaveTemplateResp;
import com.guardfind.backend.oa.model.OaLeaveTypeReq;
import com.guardfind.backend.oa.model.OaLeaveTypeResp;
import com.guardfind.backend.oa.model.OaWorkCalendarReq;
import com.guardfind.backend.oa.model.OaWorkCalendarResp;
import com.guardfind.backend.oa.service.OaLeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * OA 假期管理 API：后台维护类型/日历/额度，员工查询自己的余额与请假记录。
 */
@Tag(name = "OA 假期 API")
@Log(module = "OA 假期")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/leave")
public class OaLeaveController {

    private final OaLeaveService service;

    @Operation(summary = "假期类型列表")
    @SaCheckPermission(value = {"system:oa:leave:list", "system:oa:leave:mine"}, mode = SaMode.OR)
    @GetMapping("/types")
    public List<OaLeaveTypeResp> types(@RequestParam(defaultValue = "false") boolean enabledOnly) {
        return service.types(tenantId(), enabledOnly);
    }

    @Operation(summary = "新建假期类型")
    @SaCheckPermission("system:oa:leave:manage")
    @PostMapping("/types")
    public OaLeaveTypeResp createType(@Valid @RequestBody OaLeaveTypeReq req) {
        return service.createType(tenantId(), userId(), req);
    }

    @Operation(summary = "修改假期类型")
    @SaCheckPermission("system:oa:leave:manage")
    @PutMapping("/types/{id}")
    public OaLeaveTypeResp updateType(@PathVariable Long id, @Valid @RequestBody OaLeaveTypeReq req) {
        return service.updateType(tenantId(), userId(), id, req);
    }

    @Operation(summary = "工作日历例外")
    @SaCheckPermission("system:oa:leave:list")
    @GetMapping("/calendar")
    public List<OaWorkCalendarResp> calendar(@RequestParam int year) {
        return service.calendar(tenantId(), year);
    }

    @Operation(summary = "登记工作日历")
    @SaCheckPermission("system:oa:leave:manage")
    @PutMapping("/calendar")
    public void saveCalendar(@Valid @RequestBody OaWorkCalendarReq req) {
        service.saveCalendar(tenantId(), userId(), req);
    }

    @Operation(summary = "删除工作日历登记")
    @SaCheckPermission("system:oa:leave:manage")
    @DeleteMapping("/calendar/{date}")
    public void deleteCalendar(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteCalendar(tenantId(), date);
    }

    @Operation(summary = "额度分页")
    @SaCheckPermission("system:oa:leave:list")
    @GetMapping("/balances")
    public PageResp<OaLeaveBalanceResp> balances(@RequestParam(required = false) Integer year,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Long userId,
                                                 PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaLeaveBalanceResp> resp = new PageResp<>();
        resp.setList(service.balances(tenantId, year, keyword, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countBalances(tenantId, year, keyword, userId));
        return resp;
    }

    @Operation(summary = "发放或调整额度")
    @SaCheckPermission("system:oa:leave:manage")
    @PostMapping("/balances/grant")
    public OaLeaveBalanceResp grant(@Valid @RequestBody OaLeaveGrantReq req) {
        return service.grant(tenantId(), userId(), req);
    }

    @Operation(summary = "额度台账")
    @SaCheckPermission("system:oa:leave:list")
    @GetMapping("/balances/{id}/ledger")
    public List<OaLeaveLedgerResp> ledger(@PathVariable Long id) {
        return service.ledger(tenantId(), id);
    }

    @Operation(summary = "初始化请假模板")
    @SaCheckPermission("system:oa:leave:manage")
    @PostMapping("/template")
    public OaLeaveTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的假期余额")
    @SaCheckPermission("system:oa:leave:mine")
    @GetMapping("/mine/balances")
    public List<OaLeaveBalanceResp> myBalances(@RequestParam(required = false) Integer year) {
        return service.myBalances(tenantId(), userId(), year);
    }

    @Operation(summary = "我的请假记录")
    @SaCheckPermission("system:oa:leave:mine")
    @GetMapping("/mine/requests")
    public PageResp<OaLeaveRequestResp> myRequests(PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaLeaveRequestResp> resp = new PageResp<>();
        resp.setList(service.myRequests(tenantId, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countMyRequests(tenantId, userId));
        return resp;
    }

    @Operation(summary = "请假天数与余额预估")
    @SaCheckPermission(value = {"system:oa:leave:mine", "system:oa:launch:list"}, mode = SaMode.OR)
    @PostMapping("/preview")
    public OaLeavePreviewResp preview(@RequestBody(required = false) Map<String, Object> formData) {
        return service.preview(tenantId(), userId(), formData);
    }

    private static long tenantId() {
        Long tenantId = UserContextHolder.getTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    private static long userId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new IllegalStateException("当前用户未登录");
        }
        return userId;
    }
}
