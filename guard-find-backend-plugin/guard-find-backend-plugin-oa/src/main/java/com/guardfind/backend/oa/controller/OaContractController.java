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
import com.guardfind.backend.oa.model.OaContractArchiveReq;
import com.guardfind.backend.oa.model.OaContractDetailResp;
import com.guardfind.backend.oa.model.OaContractResp;
import com.guardfind.backend.oa.model.OaContractSignReq;
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaLinkedFileResp;
import com.guardfind.backend.oa.model.OaSealRequestResp;
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
 * OA 合同 API：合同台账、签署与归档登记、模板初始化，经办人查看本人合同与用印申请。
 */
@Tag(name = "OA 合同 API")
@Log(module = "OA 合同")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/contract")
public class OaContractController {

    private static final String LIST = "system:oa:contract:list";
    private static final String MANAGE = "system:oa:contract:manage";
    private static final String EXECUTE = "system:oa:contract:execute";
    private static final String MINE = "system:oa:contract:mine";

    private final OaContractService service;
    private final FileStorageService fileStorageService;

    @Operation(summary = "合同台账分页")
    @SaCheckPermission(value = {LIST, EXECUTE}, mode = SaMode.OR)
    @GetMapping
    public PageResp<OaContractResp> contracts(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer expiringDays,
                                              PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaContractResp> resp = new PageResp<>();
        resp.setList(service.contracts(tenantId, status, keyword, null, expiringDays, pageQuery.getPage(), pageQuery
            .getSize()));
        resp.setTotal(service.countContracts(tenantId, status, keyword, null, expiringDays));
        return resp;
    }

    @Operation(summary = "合同详情")
    @SaCheckPermission(value = {LIST, EXECUTE}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public OaContractDetailResp detail(@PathVariable Long id) {
        return service.contractDetail(tenantId(), null, id);
    }

    @Operation(summary = "下载合同附件")
    @SaCheckPermission(value = {LIST, EXECUTE}, mode = SaMode.OR)
    @Log(ignore = true)
    @GetMapping("/{id}/files/{fileId}")
    public void file(@PathVariable Long id,
                     @PathVariable Long fileId,
                     HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service.contractFile(tenantId(), null, id, fileId), response);
    }

    @Operation(summary = "登记签署")
    @SaCheckPermission(EXECUTE)
    @PostMapping("/{id}/sign")
    public OaContractResp sign(@PathVariable Long id, @Valid @RequestBody OaContractSignReq req) {
        return service.sign(tenantId(), userId(), id, req);
    }

    @Operation(summary = "登记归档")
    @SaCheckPermission(EXECUTE)
    @PostMapping("/{id}/archive")
    public OaContractResp archive(@PathVariable Long id, @Valid @RequestBody OaContractArchiveReq req) {
        return service.archive(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化合同审批模板")
    @SaCheckPermission(MANAGE)
    @PostMapping("/template")
    public OaContractTemplateResp template() {
        return service.contractTemplate(tenantId(), userId());
    }

    @Operation(summary = "我的合同")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/contracts")
    public PageResp<OaContractResp> myContracts(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String keyword,
                                                PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaContractResp> resp = new PageResp<>();
        resp.setList(service.contracts(tenantId, status, keyword, userId, null, pageQuery.getPage(), pageQuery
            .getSize()));
        resp.setTotal(service.countContracts(tenantId, status, keyword, userId, null));
        return resp;
    }

    @Operation(summary = "我的合同详情")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/contracts/{id}")
    public OaContractDetailResp myContract(@PathVariable Long id) {
        return service.contractDetail(tenantId(), userId(), id);
    }

    @Operation(summary = "下载我的合同附件")
    @SaCheckPermission(MINE)
    @Log(ignore = true)
    @GetMapping("/mine/contracts/{id}/files/{fileId}")
    public void myContractFile(@PathVariable Long id,
                               @PathVariable Long fileId,
                               HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service
            .contractFile(tenantId(), userId(), id, fileId), response);
    }

    @Operation(summary = "我的用印申请")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/seal-requests")
    public PageResp<OaSealRequestResp> mySealRequests(@RequestParam(required = false) String status,
                                                      PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaSealRequestResp> resp = new PageResp<>();
        resp.setList(service.sealRequests(tenantId, status, userId, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countSealRequests(tenantId, status, userId, null));
        return resp;
    }

    @Operation(summary = "我的用印文件")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/seal-requests/{id}/files")
    public List<OaLinkedFileResp> mySealFiles(@PathVariable Long id) {
        return service.sealRequestFiles(tenantId(), userId(), null, id);
    }

    @Operation(summary = "下载我的用印文件")
    @SaCheckPermission(MINE)
    @Log(ignore = true)
    @GetMapping("/mine/seal-requests/{id}/files/{fileId}")
    public void mySealFile(@PathVariable Long id,
                           @PathVariable Long fileId,
                           HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service
            .sealRequestFile(tenantId(), userId(), null, id, fileId), response);
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
