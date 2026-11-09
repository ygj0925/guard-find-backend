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
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.oa.model.OaApprovalActionResp;
import com.guardfind.backend.oa.model.OaOpsAuditResp;
import com.guardfind.backend.oa.model.OaOpsExportResp;
import com.guardfind.backend.oa.model.OaOpsTerminateReq;
import com.guardfind.backend.oa.model.OaOutboxEventResp;
import com.guardfind.backend.oa.service.OaOpsService;
import com.guardfind.backend.oa.service.OaOutboxDispatcher;
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

/**
 * OA 运维 API：outbox 重试/立即投递、独立授权的流程终止、干预审计与导出任务排查。
 *
 * <p>
 * 终止权限与审批动作权限分离，避免管理员因拥有审批权限而默认获得代签/终止能力。
 * </p>
 */
@Tag(name = "OA 运维 API")
@Log(module = "OA 运维")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/ops")
public class OaOpsController {

    private final OaOpsService service;

    @Operation(summary = "outbox 事件分页")
    @SaCheckPermission("system:oa:ops:list")
    @GetMapping("/outbox")
    public PageResp<OaOutboxEventResp> outbox(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              PageQuery pageQuery) {
        OaOutboxDispatcher.OutboxPage page = service.outbox(tenantId(), status, keyword, pageQuery.getPage(), pageQuery
            .getSize());
        PageResp<OaOutboxEventResp> resp = new PageResp<>();
        resp.setList(page.items());
        resp.setTotal(page.total());
        return resp;
    }

    @Operation(summary = "重试失败事件")
    @SaCheckPermission("system:oa:ops:outbox:retry")
    @PostMapping("/outbox/{id}/retry")
    public void retry(@PathVariable Long id) {
        service.retryOutbox(tenantId(), userId(), id);
    }

    @Operation(summary = "立即投递到期事件")
    @SaCheckPermission("system:oa:ops:outbox:retry")
    @PostMapping("/outbox/dispatch")
    public OaOutboxDispatcher.DispatchResult dispatch() {
        return service.dispatchNow(tenantId(), userId());
    }

    @Operation(summary = "终止运行中申请")
    @SaCheckPermission("system:oa:ops:terminate")
    @PostMapping("/application/{id}/terminate")
    public OaApprovalActionResp terminate(@PathVariable Long id, @Valid @RequestBody OaOpsTerminateReq req) {
        return service.terminate(tenantId(), userId(), id, req);
    }

    @Operation(summary = "运维干预审计分页")
    @SaCheckPermission("system:oa:ops:list")
    @GetMapping("/audits")
    public PageResp<OaOpsAuditResp> audits(@RequestParam(required = false) String action, PageQuery pageQuery) {
        return toPage(service.audits(tenantId(), action, pageQuery.getPage(), pageQuery.getSize()));
    }

    @Operation(summary = "租户导出任务分页（只读，不提供下载）")
    @SaCheckPermission("system:oa:ops:list")
    @GetMapping("/exports")
    public PageResp<OaOpsExportResp> exports(@RequestParam(required = false) String status, PageQuery pageQuery) {
        return toPage(service.exports(tenantId(), status, pageQuery.getPage(), pageQuery.getSize()));
    }

    private static <T> PageResp<T> toPage(OaOpsService.Page<T> page) {
        PageResp<T> resp = new PageResp<>();
        resp.setList(page.items());
        resp.setTotal(page.total());
        return resp;
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
