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
import com.guardfind.backend.oa.model.OaApplicationDetailResp;
import com.guardfind.backend.oa.model.OaApplicationResp;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaUrgeResp;
import com.guardfind.backend.oa.service.OaApplicationService;
import com.guardfind.backend.oa.service.OaUrgeService;
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
 * OA 我的申请 API（前台），只查询当前登录人发起的申请。
 */
@Tag(name = "OA 我的申请 API")
@Log(module = "OA 我的申请")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/application")
public class OaApplicationController {

    private final OaApplicationService service;
    private final OaUrgeService urgeService;

    @Operation(summary = "我的申请分页")
    @SaCheckPermission("system:oa:application:list")
    @GetMapping("/page")
    public PageResp<OaApplicationResp> page(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String status,
                                            PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaApplicationResp> resp = new PageResp<>();
        resp.setList(service.page(tenantId, userId, keyword, status, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.count(tenantId, userId, keyword, status));
        return resp;
    }

    @Operation(summary = "我的申请详情")
    @SaCheckPermission("system:oa:application:list")
    @GetMapping("/{id}")
    public OaApplicationDetailResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), userId(), id);
    }

    @Operation(summary = "催办当前审批任务")
    @SaCheckPermission("system:oa:application:urge")
    @PostMapping("/{id}/urge")
    public OaUrgeResp urge(@PathVariable Long id, @Valid @RequestBody OaApprovalActionReq req) {
        return urgeService.urge(tenantId(), userId(), id, req);
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
