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
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaApprovalActionResp;
import com.guardfind.backend.oa.model.OaApprovalDetailResp;
import com.guardfind.backend.oa.model.OaDoneRecordResp;
import com.guardfind.backend.oa.model.OaTodoTaskResp;
import com.guardfind.backend.oa.service.OaApprovalService;
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
 * OA 审批中心 API（前台）：只看当前处理人的待办与已办，动作按任务身份授权。
 */
@Tag(name = "OA 审批中心 API")
@Log(module = "OA 审批中心")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/approval")
public class OaApprovalController {

    private final OaApprovalService service;

    @Operation(summary = "待办分页")
    @SaCheckPermission("system:oa:approval:list")
    @GetMapping("/todo")
    public PageResp<OaTodoTaskResp> todo(@RequestParam(required = false) String keyword, PageQuery pageQuery) {
        OaApprovalService.ApprovalPage<OaTodoTaskResp> page = service.todo(tenantId(), userId(), keyword, pageQuery
            .getPage(), pageQuery.getSize());
        return toPage(page);
    }

    @Operation(summary = "已办分页")
    @SaCheckPermission("system:oa:approval:list")
    @GetMapping("/done")
    public PageResp<OaDoneRecordResp> done(@RequestParam(required = false) String keyword, PageQuery pageQuery) {
        OaApprovalService.ApprovalPage<OaDoneRecordResp> page = service.done(tenantId(), userId(), keyword, pageQuery
            .getPage(), pageQuery.getSize());
        return toPage(page);
    }

    @Operation(summary = "审批任务详情")
    @SaCheckPermission("system:oa:approval:list")
    @GetMapping("/task/{taskId}")
    public OaApprovalDetailResp detail(@PathVariable String taskId) {
        return service.detail(tenantId(), userId(), taskId);
    }

    @Operation(summary = "审批通过")
    @SaCheckPermission("system:oa:approval:action")
    @PostMapping("/task/{taskId}/approve")
    public OaApprovalActionResp approve(@PathVariable String taskId, @Valid @RequestBody OaApprovalActionReq req) {
        return service.approve(tenantId(), userId(), taskId, req);
    }

    @Operation(summary = "驳回申请")
    @SaCheckPermission("system:oa:approval:action")
    @PostMapping("/task/{taskId}/reject")
    public OaApprovalActionResp reject(@PathVariable String taskId, @Valid @RequestBody OaApprovalActionReq req) {
        return service.reject(tenantId(), userId(), taskId, req);
    }

    @Operation(summary = "退回上一人工节点")
    @SaCheckPermission("system:oa:approval:action")
    @PostMapping("/task/{taskId}/return")
    public OaApprovalActionResp returnToPrevious(@PathVariable String taskId,
                                                 @Valid @RequestBody OaApprovalActionReq req) {
        return service.returnToPrevious(tenantId(), userId(), taskId, req);
    }

    @Operation(summary = "发起人撤回")
    @SaCheckPermission("system:oa:application:withdraw")
    @PostMapping("/application/{applicationId}/withdraw")
    public OaApprovalActionResp withdraw(@PathVariable Long applicationId,
                                         @Valid @RequestBody OaApprovalActionReq req) {
        return service.withdraw(tenantId(), userId(), applicationId, req);
    }

    private static <T> PageResp<T> toPage(OaApprovalService.ApprovalPage<T> page) {
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
