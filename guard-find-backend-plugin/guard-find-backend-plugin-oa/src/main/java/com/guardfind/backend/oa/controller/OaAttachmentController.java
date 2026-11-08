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
import com.guardfind.backend.oa.service.OaAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.exception.FileStorageRuntimeException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.log.annotation.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * OA 附件鉴权下载：只有申请参与人可以通过接口读取，存储 URL 不再作为授权凭证。
 */
@Tag(name = "OA 附件 API")
@Log(module = "OA 附件")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/attachment")
public class OaAttachmentController {

    private final OaAttachmentService service;
    private final FileStorageService fileStorageService;

    @Operation(summary = "下载申请附件")
    @SaCheckPermission("system:oa:attachment:download")
    @Log(ignore = true)
    @GetMapping("/{fileId}")
    public void download(@PathVariable Long fileId, HttpServletResponse response) throws IOException {
        OaAttachmentService.DownloadAttachment attachment = service.authorizeDownload(tenantId(), userId(), fileId);
        response.setContentType(attachment.contentType() == null || attachment.contentType().isBlank()
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : attachment.contentType());
        if (attachment.size() != null && attachment.size() >= 0) {
            response.setContentLengthLong(attachment.size());
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(attachment.fileName(), StandardCharsets.UTF_8)
            .build()
            .toString());
        try {
            fileStorageService.download(attachment.fileInfo()).outputStream(response.getOutputStream());
        } catch (FileStorageRuntimeException e) {
            throw new BusinessException("附件文件不存在或已被清理");
        }
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
