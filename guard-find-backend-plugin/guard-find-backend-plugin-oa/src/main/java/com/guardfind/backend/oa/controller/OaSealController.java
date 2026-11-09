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
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaLinkedFileResp;
import com.guardfind.backend.oa.model.OaSealExecuteReq;
import com.guardfind.backend.oa.model.OaSealReq;
import com.guardfind.backend.oa.model.OaSealRequestResp;
import com.guardfind.backend.oa.model.OaSealResp;
import com.guardfind.backend.oa.service.OaContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.x.file.storage.core.FileStorageService;
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

import java.io.IOException;
import java.util.List;

/**
 * OA 用印 API：印章档案、用印台账与模板初始化，印章保管人查看并登记待用印申请。
 */
@Tag(name = "OA 用印 API")
@Log(module = "OA 用印")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/seal")
public class OaSealController {

    private static final String LIST = "system:oa:seal:list";
    private static final String MANAGE = "system:oa:seal:manage";
    private static final String EXECUTE = "system:oa:seal:execute";

    private final OaContractService service;
    private final FileStorageService fileStorageService;

    @Operation(summary = "印章档案")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping("/seals")
    public List<OaSealResp> seals(@RequestParam(required = false) String status) {
        return service.seals(tenantId(), status);
    }

    @Operation(summary = "新建印章")
    @SaCheckPermission(MANAGE)
    @PostMapping("/seals")
    public OaSealResp createSeal(@Valid @RequestBody OaSealReq req) {
        return service.createSeal(tenantId(), userId(), req);
    }

    @Operation(summary = "修改印章")
    @SaCheckPermission(MANAGE)
    @PutMapping("/seals/{id}")
    public OaSealResp updateSeal(@PathVariable Long id, @Valid @RequestBody OaSealReq req) {
        return service.updateSeal(tenantId(), userId(), id, req);
    }

    @Operation(summary = "用印台账分页")
    @SaCheckPermission(LIST)
    @GetMapping("/requests")
    public PageResp<OaSealRequestResp> requests(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaSealRequestResp> resp = new PageResp<>();
        resp.setList(service.sealRequests(tenantId, status, null, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countSealRequests(tenantId, status, null, null));
        return resp;
    }

    @Operation(summary = "用印文件")
    @SaCheckPermission(LIST)
    @GetMapping("/requests/{id}/files")
    public List<OaLinkedFileResp> requestFiles(@PathVariable Long id) {
        return service.sealRequestFiles(tenantId(), null, null, id);
    }

    @Operation(summary = "下载用印文件")
    @SaCheckPermission(LIST)
    @Log(ignore = true)
    @GetMapping("/requests/{id}/files/{fileId}")
    public void requestFile(@PathVariable Long id,
                            @PathVariable Long fileId,
                            HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service
            .sealRequestFile(tenantId(), null, null, id, fileId), response);
    }

    @Operation(summary = "初始化用印申请模板")
    @SaCheckPermission(MANAGE)
    @PostMapping("/template")
    public OaContractTemplateResp template() {
        return service.sealTemplate(tenantId(), userId());
    }

    @Operation(summary = "我保管印章的用印申请")
    @SaCheckPermission(EXECUTE)
    @GetMapping("/keeper/requests")
    public PageResp<OaSealRequestResp> keeperRequests(@RequestParam(required = false) String status,
                                                      PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaSealRequestResp> resp = new PageResp<>();
        resp.setList(service.sealRequests(tenantId, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countSealRequests(tenantId, status, null, userId));
        return resp;
    }

    @Operation(summary = "保管人查看用印文件")
    @SaCheckPermission(EXECUTE)
    @GetMapping("/keeper/requests/{id}/files")
    public List<OaLinkedFileResp> keeperFiles(@PathVariable Long id) {
        return service.sealRequestFiles(tenantId(), null, userId(), id);
    }

    @Operation(summary = "保管人下载用印文件")
    @SaCheckPermission(EXECUTE)
    @Log(ignore = true)
    @GetMapping("/keeper/requests/{id}/files/{fileId}")
    public void keeperFile(@PathVariable Long id,
                           @PathVariable Long fileId,
                           HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service
            .sealRequestFile(tenantId(), null, userId(), id, fileId), response);
    }

    @Operation(summary = "登记用印")
    @SaCheckPermission(EXECUTE)
    @PostMapping("/keeper/requests/{id}/execute")
    public OaSealRequestResp execute(@PathVariable Long id, @Valid @RequestBody OaSealExecuteReq req) {
        return service.executeSeal(tenantId(), userId(), id, req);
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
