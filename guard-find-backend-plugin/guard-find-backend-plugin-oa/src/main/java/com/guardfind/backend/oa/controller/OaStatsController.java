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
import com.guardfind.backend.oa.model.OaExportCreateReq;
import com.guardfind.backend.oa.model.OaExportTaskResp;
import com.guardfind.backend.oa.model.OaStatsResp;
import com.guardfind.backend.oa.model.OaWorkbenchResp;
import com.guardfind.backend.oa.service.OaExportService;
import com.guardfind.backend.oa.service.OaStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.log.annotation.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

/**
 * OA 工作台与统计报表 API：工作台只看本人数据；统计、导出各自独立授权，导出文件仅发起人可下载。
 */
@Tag(name = "OA 工作台与统计 API")
@Log(module = "OA 统计")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa")
public class OaStatsController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OaStatsService statsService;
    private final OaExportService exportService;

    @Operation(summary = "我的工作台")
    @SaCheckPermission("system:oa:workbench:view")
    @Log(ignore = true)
    @GetMapping("/workbench")
    public OaWorkbenchResp workbench() {
        return statsService.workbench(tenantId(), userId());
    }

    @Operation(summary = "统计概览")
    @SaCheckPermission("system:oa:stats:list")
    @GetMapping("/stats/overview")
    public OaStatsResp overview(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) String businessType) {
        return statsService.overview(tenantId(), from, to, businessType);
    }

    @Operation(summary = "创建导出任务")
    @SaCheckPermission("system:oa:stats:export")
    @PostMapping("/stats/exports")
    public OaExportTaskResp createExport(@Valid @RequestBody OaExportCreateReq req) {
        return exportService.create(tenantId(), userId(), req);
    }

    @Operation(summary = "我的导出任务")
    @SaCheckPermission("system:oa:stats:export")
    @GetMapping("/stats/exports")
    public List<OaExportTaskResp> exports() {
        return exportService.mine(tenantId(), userId());
    }

    @Operation(summary = "下载导出文件")
    @SaCheckPermission("system:oa:stats:export")
    @GetMapping("/stats/exports/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        OaExportService.DownloadFile file = exportService.authorizeDownload(tenantId(), userId(), id);
        response.setContentType(XLSX);
        response.setContentLengthLong(Files.size(file.path()));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(file.fileName(), StandardCharsets.UTF_8)
            .build()
            .toString());
        Files.copy(file.path(), response.getOutputStream());
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
