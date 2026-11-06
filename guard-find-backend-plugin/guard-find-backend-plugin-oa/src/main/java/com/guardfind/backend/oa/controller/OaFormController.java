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
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaFormUpdateReq;
import com.guardfind.backend.oa.model.OaFormVersionDetailResp;
import com.guardfind.backend.oa.model.OaFormVersionOptionResp;
import com.guardfind.backend.oa.model.OaFormVersionResp;
import com.guardfind.backend.oa.service.OaFormService;
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
 * OA 表单管理 API（后台）。
 *
 * <p>
 * 草稿可编辑并带乐观锁；发布时写入不可变版本，流程发布只能绑定已发布版本。
 * </p>
 */
@Tag(name = "OA 表单管理 API")
@Log(module = "OA 表单")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/form")
public class OaFormController {

    private final OaFormService service;

    @Operation(summary = "表单分页")
    @SaCheckPermission("system:oa:form:list")
    @GetMapping("/page")
    public PageResp<OaFormResp> page(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaFormResp> resp = new PageResp<>();
        resp.setList(service.page(tenantId, keyword, status, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.count(tenantId, keyword, status));
        return resp;
    }

    @Operation(summary = "表单详情")
    @SaCheckPermission("system:oa:form:list")
    @GetMapping("/{id}")
    public OaFormResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), id);
    }

    @Operation(summary = "新建表单草稿")
    @SaCheckPermission("system:oa:form:create")
    @PostMapping
    public OaFormResp create(@Valid @RequestBody OaFormCreateReq req) {
        return service.create(tenantId(), userId(), req);
    }

    @Operation(summary = "保存表单草稿")
    @SaCheckPermission("system:oa:form:update")
    @PutMapping("/{id}")
    public OaFormResp update(@PathVariable Long id, @Valid @RequestBody OaFormUpdateReq req) {
        return service.update(tenantId(), userId(), id, req);
    }

    @Operation(summary = "发布表单版本")
    @SaCheckPermission("system:oa:form:publish")
    @PostMapping("/{id}/publish")
    public OaFormPublishResp publish(@PathVariable Long id) {
        return service.publish(tenantId(), userId(), id);
    }

    @Operation(summary = "停用表单")
    @SaCheckPermission("system:oa:form:disable")
    @PostMapping("/{id}/disable")
    public OaFormResp disable(@PathVariable Long id) {
        return service.disable(tenantId(), userId(), id);
    }

    @Operation(summary = "表单发布版本列表")
    @SaCheckPermission("system:oa:form:list")
    @GetMapping("/{id}/versions")
    public List<OaFormVersionResp> versions(@PathVariable Long id) {
        return service.versions(tenantId(), id);
    }

    @Operation(summary = "可绑定表单版本选项")
    @SaCheckPermission("system:oa:form:list")
    @GetMapping("/version-options")
    public List<OaFormVersionOptionResp> versionOptions() {
        return service.versionOptions(tenantId());
    }

    @Operation(summary = "表单发布版本详情")
    @SaCheckPermission("system:oa:form:list")
    @GetMapping("/version/{versionId}")
    public OaFormVersionDetailResp versionDetail(@PathVariable Long versionId) {
        return service.versionDetail(tenantId(), versionId);
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
