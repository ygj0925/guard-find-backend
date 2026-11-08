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
import com.guardfind.backend.oa.model.OaEmployeeCreateReq;
import com.guardfind.backend.oa.model.OaEmployeeOptionResp;
import com.guardfind.backend.oa.model.OaEmployeeResp;
import com.guardfind.backend.oa.model.OaEmployeeUpdateReq;
import com.guardfind.backend.oa.model.OaHandoverResp;
import com.guardfind.backend.oa.model.OaPositionCreateReq;
import com.guardfind.backend.oa.model.OaPositionOptionResp;
import com.guardfind.backend.oa.model.OaPositionResp;
import com.guardfind.backend.oa.model.OaPositionUpdateReq;
import com.guardfind.backend.oa.model.OaResignHandoverReq;
import com.guardfind.backend.oa.service.OaStaffService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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

import java.util.List;

/**
 * OA 员工与岗位管理 API（后台）。
 *
 * <p>
 * 用户和部门沿用后台账号；岗位编码会写入 BPMN 的 position:CODE，创建后不可修改。
 * 人员/岗位选项供表单人员字段与发起页审批人绑定使用。
 * </p>
 */
@Tag(name = "OA 组织管理 API")
@Log(module = "OA 组织")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/staff")
public class OaStaffController {

    private final OaStaffService service;

    @Operation(summary = "员工分页")
    @SaCheckPermission("system:oa:employee:list")
    @GetMapping("/employee/page")
    public PageResp<OaEmployeeResp> employeePage(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaEmployeeResp> resp = new PageResp<>();
        resp.setList(service.pageEmployees(tenantId, keyword, status, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countEmployees(tenantId, keyword, status));
        return resp;
    }

    @Operation(summary = "员工详情")
    @SaCheckPermission("system:oa:employee:list")
    @GetMapping("/employee/{id}")
    public OaEmployeeResp employeeDetail(@PathVariable Long id) {
        return service.detailEmployee(tenantId(), id);
    }

    @Operation(summary = "新建员工档案")
    @SaCheckPermission("system:oa:employee:create")
    @PostMapping("/employee")
    public OaEmployeeResp employeeCreate(@Valid @RequestBody OaEmployeeCreateReq req) {
        return service.createEmployee(tenantId(), userId(), req);
    }

    @Operation(summary = "更新员工档案")
    @SaCheckPermission("system:oa:employee:update")
    @PutMapping("/employee/{id}")
    public OaEmployeeResp employeeUpdate(@PathVariable Long id, @Valid @RequestBody OaEmployeeUpdateReq req) {
        return service.updateEmployee(tenantId(), userId(), id, req);
    }

    @Operation(summary = "离职/调岗待办转交")
    @SaCheckPermission("system:oa:employee:transfer")
    @PostMapping("/employee/{id}/handover")
    public OaHandoverResp employeeHandover(@PathVariable Long id, @Valid @RequestBody OaResignHandoverReq req) {
        return service.handover(tenantId(), userId(), id, req.targetUserId(), req.reason());
    }

    @Operation(summary = "在职员工选项")
    @SaCheckPermission(value = {"system:oa:employee:list", "system:oa:launch:list",
        "system:oa:form:list"}, mode = SaMode.OR)
    @GetMapping("/employee/options")
    public List<OaEmployeeOptionResp> employeeOptions(@RequestParam(required = false) String keyword) {
        return service.employeeOptions(tenantId(), keyword);
    }

    @Operation(summary = "可建档后台用户选项")
    @SaCheckPermission("system:oa:employee:list")
    @GetMapping("/employee/user-options")
    public List<OaEmployeeOptionResp> userOptions(@RequestParam(required = false) String keyword) {
        return service.userOptions(tenantId(), keyword);
    }

    @Operation(summary = "岗位分页")
    @SaCheckPermission("system:oa:position:list")
    @GetMapping("/position/page")
    public PageResp<OaPositionResp> positionPage(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaPositionResp> resp = new PageResp<>();
        resp.setList(service.pagePositions(tenantId, keyword, status, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countPositions(tenantId, keyword, status));
        return resp;
    }

    @Operation(summary = "岗位详情")
    @SaCheckPermission("system:oa:position:list")
    @GetMapping("/position/{id}")
    public OaPositionResp positionDetail(@PathVariable Long id) {
        return service.detailPosition(tenantId(), id);
    }

    @Operation(summary = "新建岗位")
    @SaCheckPermission("system:oa:position:create")
    @PostMapping("/position")
    public OaPositionResp positionCreate(@Valid @RequestBody OaPositionCreateReq req) {
        return service.createPosition(tenantId(), userId(), req);
    }

    @Operation(summary = "更新岗位")
    @SaCheckPermission("system:oa:position:update")
    @PutMapping("/position/{id}")
    public OaPositionResp positionUpdate(@PathVariable Long id, @Valid @RequestBody OaPositionUpdateReq req) {
        return service.updatePosition(tenantId(), userId(), id, req);
    }

    @Operation(summary = "启用岗位选项")
    @SaCheckPermission(value = {"system:oa:employee:list", "system:oa:position:list",
        "system:oa:launch:list"}, mode = SaMode.OR)
    @GetMapping("/position/options")
    public List<OaPositionOptionResp> positionOptions() {
        return service.positionOptions(tenantId());
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
