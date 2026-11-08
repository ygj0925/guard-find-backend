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
import com.guardfind.backend.oa.model.OaExpenseCategoryReq;
import com.guardfind.backend.oa.model.OaExpenseCategoryResp;
import com.guardfind.backend.oa.model.OaExpenseDetailResp;
import com.guardfind.backend.oa.model.OaExpensePayReq;
import com.guardfind.backend.oa.model.OaExpensePreviewResp;
import com.guardfind.backend.oa.model.OaExpenseResp;
import com.guardfind.backend.oa.model.OaExpenseTemplateResp;
import com.guardfind.backend.oa.service.OaExpenseService;
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
import java.util.Map;

/**
 * OA 报销 API：后台维护费用类型与报销台账，财务登记支付，员工查询自己的报销。
 */
@Tag(name = "OA 报销 API")
@Log(module = "OA 报销")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/expense")
public class OaExpenseController {

    private final OaExpenseService service;

    @Operation(summary = "费用类型列表")
    @SaCheckPermission(value = {"system:oa:expense:list", "system:oa:expense:mine"}, mode = SaMode.OR)
    @GetMapping("/categories")
    public List<OaExpenseCategoryResp> categories(@RequestParam(defaultValue = "false") boolean enabledOnly) {
        return service.categories(tenantId(), enabledOnly);
    }

    @Operation(summary = "新建费用类型")
    @SaCheckPermission("system:oa:expense:manage")
    @PostMapping("/categories")
    public OaExpenseCategoryResp createCategory(@Valid @RequestBody OaExpenseCategoryReq req) {
        return service.createCategory(tenantId(), userId(), req);
    }

    @Operation(summary = "修改费用类型")
    @SaCheckPermission("system:oa:expense:manage")
    @PutMapping("/categories/{id}")
    public OaExpenseCategoryResp updateCategory(@PathVariable Long id, @Valid @RequestBody OaExpenseCategoryReq req) {
        return service.updateCategory(tenantId(), userId(), id, req);
    }

    @Operation(summary = "报销台账分页")
    @SaCheckPermission(value = {"system:oa:expense:list", "system:oa:expense:pay"}, mode = SaMode.OR)
    @GetMapping("/records")
    public PageResp<OaExpenseResp> records(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword,
                                           PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaExpenseResp> resp = new PageResp<>();
        resp.setList(service.records(tenantId, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRecords(tenantId, status, keyword, null));
        return resp;
    }

    @Operation(summary = "报销单详情")
    @SaCheckPermission(value = {"system:oa:expense:list", "system:oa:expense:pay"}, mode = SaMode.OR)
    @GetMapping("/records/{id}")
    public OaExpenseDetailResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), id);
    }

    @Operation(summary = "登记支付")
    @SaCheckPermission("system:oa:expense:pay")
    @PostMapping("/records/{id}/pay")
    public OaExpenseResp pay(@PathVariable Long id, @Valid @RequestBody OaExpensePayReq req) {
        return service.pay(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化报销模板")
    @SaCheckPermission("system:oa:expense:manage")
    @PostMapping("/template")
    public OaExpenseTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的报销")
    @SaCheckPermission("system:oa:expense:mine")
    @GetMapping("/mine")
    public PageResp<OaExpenseResp> mine(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaExpenseResp> resp = new PageResp<>();
        resp.setList(service.records(tenantId, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRecords(tenantId, status, null, userId));
        return resp;
    }

    @Operation(summary = "我的报销详情")
    @SaCheckPermission("system:oa:expense:mine")
    @GetMapping("/mine/{id}")
    public OaExpenseDetailResp myDetail(@PathVariable Long id) {
        return service.myDetail(tenantId(), userId(), id);
    }

    @Operation(summary = "报销合计预估")
    @SaCheckPermission(value = {"system:oa:expense:mine", "system:oa:launch:list"}, mode = SaMode.OR)
    @PostMapping("/preview")
    public OaExpensePreviewResp preview(@RequestBody(required = false) Map<String, Object> formData) {
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
