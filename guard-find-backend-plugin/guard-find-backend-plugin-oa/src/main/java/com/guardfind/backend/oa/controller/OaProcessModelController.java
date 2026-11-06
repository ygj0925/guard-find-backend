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
import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.model.OaBpmnValidateReq;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaProcessModelUpdateReq;
import com.guardfind.backend.oa.model.OaProcessPublishReq;
import com.guardfind.backend.oa.model.OaProcessPublishResp;
import com.guardfind.backend.oa.model.OaProcessReleaseResp;
import com.guardfind.backend.oa.service.OaProcessModelService;
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
 * OA 流程模型管理 API（后台）。
 *
 * <p>
 * 保存只写草稿；发布由服务端重新校验、部署并生成不可变版本。发布失败不会产生版本记录。
 * </p>
 */
@Tag(name = "OA 流程模型管理 API")
@Log(module = "OA 流程模型")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/process/model")
public class OaProcessModelController {

    private final OaProcessModelService service;

    @Operation(summary = "流程模型分页")
    @SaCheckPermission("system:oa:process:list")
    @GetMapping("/page")
    public PageResp<OaProcessModelResp> page(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaProcessModelResp> resp = new PageResp<>();
        resp.setList(service.page(tenantId, keyword, status, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.count(tenantId, keyword, status));
        return resp;
    }

    @Operation(summary = "流程模型详情")
    @SaCheckPermission("system:oa:process:list")
    @GetMapping("/{id}")
    public OaProcessModelResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), id);
    }

    @Operation(summary = "新建流程模型草稿")
    @SaCheckPermission("system:oa:process:create")
    @PostMapping
    public OaProcessModelResp create(@Valid @RequestBody OaProcessModelCreateReq req) {
        return service.create(tenantId(), userId(), req);
    }

    @Operation(summary = "保存流程模型草稿")
    @SaCheckPermission("system:oa:process:update")
    @PutMapping("/{id}")
    public OaProcessModelResp update(@PathVariable Long id, @Valid @RequestBody OaProcessModelUpdateReq req) {
        return service.update(tenantId(), userId(), id, req);
    }

    @Operation(summary = "校验 BPMN XML")
    @SaCheckPermission("system:oa:process:list")
    @PostMapping("/validate")
    public OaBpmnValidationResult validate(@Valid @RequestBody OaBpmnValidateReq req) {
        return service.validate(req.bpmnXml());
    }

    @Operation(summary = "发布流程模型")
    @SaCheckPermission("system:oa:process:publish")
    @PostMapping("/{id}/publish")
    public OaProcessPublishResp publish(@PathVariable Long id,
                                        @Valid @RequestBody(required = false) OaProcessPublishReq req) {
        return service.publish(tenantId(), userId(), id, req == null ? new OaProcessPublishReq(null, null, null) : req);
    }

    @Operation(summary = "停用流程模型")
    @SaCheckPermission("system:oa:process:disable")
    @PostMapping("/{id}/disable")
    public OaProcessModelResp disable(@PathVariable Long id) {
        return service.disable(tenantId(), userId(), id);
    }

    @Operation(summary = "流程发布版本列表")
    @SaCheckPermission("system:oa:process:list")
    @GetMapping("/{id}/releases")
    public List<OaProcessReleaseResp> releases(@PathVariable Long id) {
        return service.releases(tenantId(), id);
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
