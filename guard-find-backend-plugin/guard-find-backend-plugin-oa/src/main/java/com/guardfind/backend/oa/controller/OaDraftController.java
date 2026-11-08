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
import com.guardfind.backend.oa.model.OaDraftDetailResp;
import com.guardfind.backend.oa.model.OaDraftResp;
import com.guardfind.backend.oa.model.OaDraftSaveReq;
import com.guardfind.backend.oa.model.OaDraftSaveResp;
import com.guardfind.backend.oa.service.OaDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/**
 * OA 申请草稿 API：只操作当前用户自己的草稿，沿用发起权限。
 */
@Tag(name = "OA 申请草稿 API")
@Log(module = "OA 申请草稿")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/draft")
public class OaDraftController {

    private final OaDraftService service;

    @Operation(summary = "我的草稿分页")
    @SaCheckPermission("system:oa:launch:submit")
    @GetMapping("/page")
    public PageResp<OaDraftResp> page(@RequestParam(required = false) String keyword, PageQuery pageQuery) {
        OaDraftService.DraftPage page = service.page(tenantId(), userId(), keyword, pageQuery.getPage(), pageQuery
            .getSize());
        PageResp<OaDraftResp> resp = new PageResp<>();
        resp.setList(page.items());
        resp.setTotal(page.total());
        return resp;
    }

    @Operation(summary = "草稿详情")
    @SaCheckPermission("system:oa:launch:submit")
    @GetMapping("/{id}")
    public OaDraftDetailResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), userId(), id);
    }

    @Operation(summary = "新建草稿")
    @SaCheckPermission("system:oa:launch:submit")
    @PostMapping
    public OaDraftSaveResp create(@Valid @RequestBody OaDraftSaveReq req) {
        return service.create(tenantId(), userId(), req);
    }

    @Operation(summary = "更新草稿")
    @SaCheckPermission("system:oa:launch:submit")
    @PutMapping("/{id}")
    public OaDraftSaveResp update(@PathVariable Long id, @Valid @RequestBody OaDraftSaveReq req) {
        return service.update(tenantId(), userId(), id, req);
    }

    @Operation(summary = "删除草稿")
    @SaCheckPermission("system:oa:launch:submit")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(tenantId(), userId(), id);
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
