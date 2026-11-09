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
import com.guardfind.backend.oa.model.OaDeptOptionResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetLedgerResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetReq;
import com.guardfind.backend.oa.model.OaPurchaseBudgetResp;
import com.guardfind.backend.oa.model.OaPurchaseDetailResp;
import com.guardfind.backend.oa.model.OaPurchaseOrderReq;
import com.guardfind.backend.oa.model.OaPurchaseReceiveReq;
import com.guardfind.backend.oa.model.OaPurchaseResp;
import com.guardfind.backend.oa.model.OaPurchaseTemplateResp;
import com.guardfind.backend.oa.service.OaPurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import java.util.List;

/**
 * OA 采购 API：部门预算、采购台账、采购经办下单与验收，员工查询自己的采购。
 */
@Tag(name = "OA 采购 API")
@Log(module = "OA 采购")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/purchase")
public class OaPurchaseController {

    private final OaPurchaseService service;

    @Operation(summary = "部门选项")
    @SaCheckPermission(value = {"system:oa:purchase:list", "system:oa:purchase:manage"}, mode = SaMode.OR)
    @GetMapping("/depts")
    public List<OaDeptOptionResp> depts() {
        return service.depts(tenantId());
    }

    @Operation(summary = "部门年度预算")
    @SaCheckPermission(value = {"system:oa:purchase:list", "system:oa:purchase:execute"}, mode = SaMode.OR)
    @GetMapping("/budgets")
    public List<OaPurchaseBudgetResp> budgets(@RequestParam(required = false) Integer year) {
        return service.budgets(tenantId(), year);
    }

    @Operation(summary = "预算台账")
    @SaCheckPermission(value = {"system:oa:purchase:list", "system:oa:purchase:execute"}, mode = SaMode.OR)
    @GetMapping("/budgets/{id}/ledger")
    public List<OaPurchaseBudgetLedgerResp> budgetLedger(@PathVariable Long id) {
        return service.budgetLedger(tenantId(), id);
    }

    @Operation(summary = "新建或调整部门预算")
    @SaCheckPermission("system:oa:purchase:manage")
    @PostMapping("/budgets")
    public OaPurchaseBudgetResp saveBudget(@Valid @RequestBody OaPurchaseBudgetReq req) {
        return service.saveBudget(tenantId(), userId(), req);
    }

    @Operation(summary = "采购台账分页")
    @SaCheckPermission(value = {"system:oa:purchase:list", "system:oa:purchase:execute"}, mode = SaMode.OR)
    @GetMapping("/records")
    public PageResp<OaPurchaseResp> purchases(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaPurchaseResp> resp = new PageResp<>();
        resp.setList(service.purchases(tenantId, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countPurchases(tenantId, status, keyword, null));
        return resp;
    }

    @Operation(summary = "采购详情")
    @SaCheckPermission(value = {"system:oa:purchase:list", "system:oa:purchase:execute"}, mode = SaMode.OR)
    @GetMapping("/records/{id}")
    public OaPurchaseDetailResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), null, id);
    }

    @Operation(summary = "登记下单")
    @SaCheckPermission("system:oa:purchase:execute")
    @PostMapping("/records/{id}/order")
    public OaPurchaseResp order(@PathVariable Long id, @Valid @RequestBody OaPurchaseOrderReq req) {
        return service.order(tenantId(), userId(), id, req);
    }

    @Operation(summary = "登记验收")
    @SaCheckPermission("system:oa:purchase:execute")
    @PostMapping("/records/{id}/receive")
    public OaPurchaseDetailResp receive(@PathVariable Long id, @Valid @RequestBody OaPurchaseReceiveReq req) {
        return service.receive(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化采购模板")
    @SaCheckPermission("system:oa:purchase:manage")
    @PostMapping("/template")
    public OaPurchaseTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的采购")
    @SaCheckPermission("system:oa:asset:mine")
    @GetMapping("/mine")
    public PageResp<OaPurchaseResp> mine(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaPurchaseResp> resp = new PageResp<>();
        resp.setList(service.purchases(tenantId, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countPurchases(tenantId, status, null, userId));
        return resp;
    }

    @Operation(summary = "我的采购详情")
    @SaCheckPermission("system:oa:asset:mine")
    @GetMapping("/mine/{id}")
    public OaPurchaseDetailResp myDetail(@PathVariable Long id) {
        return service.detail(tenantId(), userId(), id);
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
