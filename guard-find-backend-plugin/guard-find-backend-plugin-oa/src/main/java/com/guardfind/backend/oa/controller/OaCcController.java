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
import com.guardfind.backend.oa.model.OaCcResp;
import com.guardfind.backend.oa.service.OaCcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

/**
 * OA 抄送我的 API：只返回当前登录人收到的抄送，查看详情即标记已读。
 */
@Tag(name = "OA 抄送我的 API")
@Log(module = "OA 抄送")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/cc")
public class OaCcController {

    private final OaCcService service;

    @Operation(summary = "抄送我的分页")
    @SaCheckPermission("system:oa:cc:list")
    @GetMapping("/page")
    public PageResp<OaCcResp> page(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String status,
                                   PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaCcResp> resp = new PageResp<>();
        resp.setList(service.page(tenantId, userId, keyword, status, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.count(tenantId, userId, keyword, status));
        return resp;
    }

    @Operation(summary = "抄送详情（标记已读）")
    @SaCheckPermission("system:oa:cc:list")
    @GetMapping("/{applicationId}")
    public OaApplicationDetailResp detail(@PathVariable Long applicationId) {
        return service.detail(tenantId(), userId(), applicationId);
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
