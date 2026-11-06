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
import com.guardfind.backend.oa.model.OaLaunchDetailResp;
import com.guardfind.backend.oa.model.OaLaunchItemResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaLaunchSubmitResp;
import com.guardfind.backend.oa.service.OaLaunchService;
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
 * OA 发起流程 API（前台）。
 *
 * <p>
 * 列表与详情只暴露当前用户可发起且仍有生效版本的流程；提交需要 requestId 保证幂等。
 * </p>
 */
@Tag(name = "OA 发起流程 API")
@Log(module = "OA 发起流程")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/launch")
public class OaLaunchController {

    private final OaLaunchService service;

    @Operation(summary = "可发起流程分页")
    @SaCheckPermission("system:oa:launch:list")
    @GetMapping("/page")
    public PageResp<OaLaunchItemResp> page(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String category,
                                           PageQuery pageQuery) {
        OaLaunchService.LaunchPage page = service.page(tenantId(), userId(), keyword, category, pageQuery
            .getPage(), pageQuery.getSize());
        PageResp<OaLaunchItemResp> resp = new PageResp<>();
        resp.setList(page.items());
        resp.setTotal(page.total());
        return resp;
    }

    @Operation(summary = "发起详情")
    @SaCheckPermission("system:oa:launch:list")
    @GetMapping("/{releaseId}")
    public OaLaunchDetailResp detail(@PathVariable Long releaseId) {
        return service.detail(tenantId(), userId(), releaseId);
    }

    @Operation(summary = "提交并发起流程")
    @SaCheckPermission("system:oa:launch:submit")
    @PostMapping("/{releaseId}/submit")
    public OaLaunchSubmitResp submit(@PathVariable Long releaseId, @Valid @RequestBody OaLaunchSubmitReq req) {
        return service.submit(tenantId(), userId(), releaseId, req);
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
