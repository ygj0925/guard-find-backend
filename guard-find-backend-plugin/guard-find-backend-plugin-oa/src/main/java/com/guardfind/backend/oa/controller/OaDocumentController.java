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
import com.guardfind.backend.oa.model.OaDocumentDetailResp;
import com.guardfind.backend.oa.model.OaDocumentInboxResp;
import com.guardfind.backend.oa.model.OaDocumentReadResp;
import com.guardfind.backend.oa.model.OaDocumentResp;
import com.guardfind.backend.oa.model.OaDocumentRevokeReq;
import com.guardfind.backend.oa.service.OaDocumentService;
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

/**
 * OA 公文知识 API：公文台账、阅读回执、撤稿与模板初始化，员工收件箱阅读与签收。
 */
@Tag(name = "OA 公文知识 API")
@Log(module = "OA 公文知识")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/document")
public class OaDocumentController {

    private static final String LIST = "system:oa:document:list";
    private static final String MANAGE = "system:oa:document:manage";
    private static final String READ = "system:oa:document:read";

    private final OaDocumentService service;
    private final FileStorageService fileStorageService;

    @Operation(summary = "公文台账分页")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping
    public PageResp<OaDocumentResp> documents(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String docType,
                                              @RequestParam(required = false) String keyword,
                                              PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaDocumentResp> resp = new PageResp<>();
        resp.setList(service.documents(tenantId, status, docType, keyword, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countDocuments(tenantId, status, docType, keyword));
        return resp;
    }

    @Operation(summary = "公文详情与阅读回执")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public OaDocumentDetailResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), id);
    }

    @Operation(summary = "下载公文附件")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @Log(ignore = true)
    @GetMapping("/{id}/files/{fileId}")
    public void file(@PathVariable Long id,
                     @PathVariable Long fileId,
                     HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service.documentFile(tenantId(), id, fileId), response);
    }

    @Operation(summary = "撤稿")
    @SaCheckPermission(MANAGE)
    @PostMapping("/{id}/revoke")
    public OaDocumentResp revoke(@PathVariable Long id, @Valid @RequestBody OaDocumentRevokeReq req) {
        return service.revoke(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化公文发布模板")
    @SaCheckPermission(MANAGE)
    @PostMapping("/template")
    public OaContractTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的公文收件箱")
    @SaCheckPermission(READ)
    @GetMapping("/inbox")
    public PageResp<OaDocumentInboxResp> inbox(@RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "false") boolean pendingOnly,
                                               PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaDocumentInboxResp> resp = new PageResp<>();
        resp.setList(service.inbox(tenantId, userId, keyword, pendingOnly, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countInbox(tenantId, userId, keyword, pendingOnly));
        return resp;
    }

    @Operation(summary = "阅读公文")
    @SaCheckPermission(READ)
    @GetMapping("/inbox/{id}")
    public OaDocumentReadResp read(@PathVariable Long id) {
        return service.read(tenantId(), userId(), id);
    }

    @Operation(summary = "签收公文")
    @SaCheckPermission(READ)
    @PostMapping("/inbox/{id}/ack")
    public OaDocumentInboxResp ack(@PathVariable Long id) {
        return service.ack(tenantId(), userId(), id);
    }

    @Operation(summary = "下载收件箱公文附件")
    @SaCheckPermission(READ)
    @Log(ignore = true)
    @GetMapping("/inbox/{id}/files/{fileId}")
    public void inboxFile(@PathVariable Long id,
                          @PathVariable Long fileId,
                          HttpServletResponse response) throws IOException {
        OaAttachmentController.stream(fileStorageService, service
            .inboxFile(tenantId(), userId(), id, fileId), response);
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
