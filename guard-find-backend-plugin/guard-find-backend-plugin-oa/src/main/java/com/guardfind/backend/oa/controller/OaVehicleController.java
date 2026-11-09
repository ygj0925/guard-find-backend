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
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaDriverReq;
import com.guardfind.backend.oa.model.OaDriverResp;
import com.guardfind.backend.oa.model.OaResourceCancelReq;
import com.guardfind.backend.oa.model.OaVehicleCandidatesResp;
import com.guardfind.backend.oa.model.OaVehicleDepartReq;
import com.guardfind.backend.oa.model.OaVehicleDispatchReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceCompleteReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceResp;
import com.guardfind.backend.oa.model.OaVehicleReq;
import com.guardfind.backend.oa.model.OaVehicleRequestResp;
import com.guardfind.backend.oa.model.OaVehicleResp;
import com.guardfind.backend.oa.model.OaVehicleReturnReq;
import com.guardfind.backend.oa.service.OaVehicleService;
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
 * OA 车辆 API：行政维护车辆、司机与维保，调度员派车并登记出车/归还，员工查看与取消自己的用车申请。
 */
@Tag(name = "OA 车辆 API")
@Log(module = "OA 车辆")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/vehicle")
public class OaVehicleController {

    private static final String LIST = "system:oa:vehicle:list";
    private static final String MANAGE = "system:oa:vehicle:manage";
    private static final String DISPATCH = "system:oa:vehicle:dispatch";
    private static final String MINE = "system:oa:vehicle:mine";

    private final OaVehicleService service;

    @Operation(summary = "车辆档案")
    @SaCheckPermission(value = {LIST, MANAGE, DISPATCH}, mode = SaMode.OR)
    @GetMapping("/vehicles")
    public List<OaVehicleResp> vehicles(@RequestParam(required = false) String status) {
        return service.vehicles(tenantId(), status);
    }

    @Operation(summary = "新建车辆")
    @SaCheckPermission(MANAGE)
    @PostMapping("/vehicles")
    public OaVehicleResp createVehicle(@Valid @RequestBody OaVehicleReq req) {
        return service.createVehicle(tenantId(), userId(), req);
    }

    @Operation(summary = "修改车辆")
    @SaCheckPermission(MANAGE)
    @PutMapping("/vehicles/{id}")
    public OaVehicleResp updateVehicle(@PathVariable Long id, @Valid @RequestBody OaVehicleReq req) {
        return service.updateVehicle(tenantId(), userId(), id, req);
    }

    @Operation(summary = "司机档案")
    @SaCheckPermission(value = {LIST, MANAGE, DISPATCH}, mode = SaMode.OR)
    @GetMapping("/drivers")
    public List<OaDriverResp> drivers(@RequestParam(required = false) String status) {
        return service.drivers(tenantId(), status);
    }

    @Operation(summary = "新建司机")
    @SaCheckPermission(MANAGE)
    @PostMapping("/drivers")
    public OaDriverResp createDriver(@Valid @RequestBody OaDriverReq req) {
        return service.createDriver(tenantId(), userId(), req);
    }

    @Operation(summary = "修改司机")
    @SaCheckPermission(MANAGE)
    @PutMapping("/drivers/{id}")
    public OaDriverResp updateDriver(@PathVariable Long id, @Valid @RequestBody OaDriverReq req) {
        return service.updateDriver(tenantId(), userId(), id, req);
    }

    @Operation(summary = "维保登记")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping("/maintenances")
    public List<OaVehicleMaintenanceResp> maintenances(@RequestParam(required = false) Long vehicleId,
                                                       @RequestParam(required = false) String status) {
        return service.maintenances(tenantId(), vehicleId, status);
    }

    @Operation(summary = "新建维保")
    @SaCheckPermission(MANAGE)
    @PostMapping("/maintenances")
    public OaVehicleMaintenanceResp createMaintenance(@Valid @RequestBody OaVehicleMaintenanceReq req) {
        return service.createMaintenance(tenantId(), userId(), req);
    }

    @Operation(summary = "完成维保")
    @SaCheckPermission(MANAGE)
    @PostMapping("/maintenances/{id}/complete")
    public OaVehicleMaintenanceResp completeMaintenance(@PathVariable Long id,
                                                        @Valid @RequestBody OaVehicleMaintenanceCompleteReq req) {
        return service.completeMaintenance(tenantId(), userId(), id, req);
    }

    @Operation(summary = "用车申请台账分页")
    @SaCheckPermission(value = {LIST, DISPATCH}, mode = SaMode.OR)
    @GetMapping("/requests")
    public PageResp<OaVehicleRequestResp> requests(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long vehicleId,
                                                   PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaVehicleRequestResp> resp = new PageResp<>();
        resp.setList(service.requests(tenantId, status, null, vehicleId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRequests(tenantId, status, null, vehicleId));
        return resp;
    }

    @Operation(summary = "用车申请详情")
    @SaCheckPermission(value = {LIST, DISPATCH}, mode = SaMode.OR)
    @GetMapping("/requests/{id}")
    public OaVehicleRequestResp request(@PathVariable Long id) {
        return service.request(tenantId(), null, id);
    }

    @Operation(summary = "可派车辆与司机")
    @SaCheckPermission(DISPATCH)
    @GetMapping("/requests/{id}/candidates")
    public OaVehicleCandidatesResp candidates(@PathVariable Long id) {
        return service.candidates(tenantId(), id);
    }

    @Operation(summary = "派车")
    @SaCheckPermission(DISPATCH)
    @PostMapping("/requests/{id}/dispatch")
    public OaVehicleRequestResp dispatch(@PathVariable Long id, @Valid @RequestBody OaVehicleDispatchReq req) {
        return service.dispatch(tenantId(), userId(), id, req);
    }

    @Operation(summary = "登记出车")
    @SaCheckPermission(DISPATCH)
    @PostMapping("/requests/{id}/depart")
    public OaVehicleRequestResp depart(@PathVariable Long id, @Valid @RequestBody OaVehicleDepartReq req) {
        return service.depart(tenantId(), userId(), id, req);
    }

    @Operation(summary = "登记归还")
    @SaCheckPermission(DISPATCH)
    @PostMapping("/requests/{id}/return")
    public OaVehicleRequestResp returnVehicle(@PathVariable Long id, @Valid @RequestBody OaVehicleReturnReq req) {
        return service.returnVehicle(tenantId(), userId(), id, req);
    }

    @Operation(summary = "调度取消用车")
    @SaCheckPermission(DISPATCH)
    @PostMapping("/requests/{id}/cancel")
    public OaVehicleRequestResp cancel(@PathVariable Long id, @Valid @RequestBody OaResourceCancelReq req) {
        return service.cancelByDispatcher(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化用车审批模板")
    @SaCheckPermission(MANAGE)
    @PostMapping("/template")
    public OaContractTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的用车申请")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/requests")
    public PageResp<OaVehicleRequestResp> myRequests(@RequestParam(required = false) String status,
                                                     PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaVehicleRequestResp> resp = new PageResp<>();
        resp.setList(service.requests(tenantId, status, userId, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRequests(tenantId, status, userId, null));
        return resp;
    }

    @Operation(summary = "我的用车申请详情")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/requests/{id}")
    public OaVehicleRequestResp myRequest(@PathVariable Long id) {
        return service.request(tenantId(), userId(), id);
    }

    @Operation(summary = "取消我的用车")
    @SaCheckPermission(MINE)
    @PostMapping("/mine/requests/{id}/cancel")
    public OaVehicleRequestResp cancelMine(@PathVariable Long id, @Valid @RequestBody OaResourceCancelReq req) {
        return service.cancelMine(tenantId(), userId(), id, req);
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
