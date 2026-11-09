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
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.oa.model.OaWorkReportCommentReq;
import com.guardfind.backend.oa.model.OaWorkReportDetailResp;
import com.guardfind.backend.oa.model.OaWorkReportRemindReq;
import com.guardfind.backend.oa.model.OaWorkReportResp;
import com.guardfind.backend.oa.model.OaWorkReportSaveReq;
import com.guardfind.backend.oa.model.OaWorkReportSummaryResp;
import com.guardfind.backend.oa.service.OaWorkReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import java.time.LocalDate;
import java.util.Map;

/**
 * OA 工作报告 API：本人填写日报/周报，负责人/主管查看团队报告、批注、统计提交情况并催交。
 */
@Tag(name = "OA 工作报告 API")
@Log(module = "OA 工作报告")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/report")
public class OaWorkReportController {

    private static final String MINE = "system:oa:report:mine";
    private static final String STAT = "system:oa:report:stat";

    private final OaWorkReportService service;

    @Operation(summary = "我的报告分页")
    @SaCheckPermission(MINE)
    @GetMapping("/mine")
    public PageResp<OaWorkReportResp> mine(@RequestParam(required = false) String type, PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaWorkReportResp> resp = new PageResp<>();
        resp.setList(service.mine(tenantId, userId, type, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countMine(tenantId, userId, type));
        return resp;
    }

    @Operation(summary = "我某周期的报告")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/period")
    public OaWorkReportResp minePeriod(@RequestParam String type,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.minePeriod(tenantId(), userId(), type, date);
    }

    @Operation(summary = "保存或提交我的报告")
    @SaCheckPermission(MINE)
    @PostMapping("/mine")
    public OaWorkReportResp save(@Valid @RequestBody OaWorkReportSaveReq req) {
        return service.save(tenantId(), userId(), req);
    }

    @Operation(summary = "删除我的草稿")
    @SaCheckPermission(MINE)
    @DeleteMapping("/mine/{id}")
    public void deleteDraft(@PathVariable Long id) {
        service.deleteDraft(tenantId(), userId(), id);
    }

    @Operation(summary = "团队报告分页")
    @SaCheckPermission(value = {MINE, STAT}, mode = SaMode.OR)
    @GetMapping("/team")
    public PageResp<OaWorkReportResp> team(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) Long authorId,
                                           PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        boolean stat = StpUtil.hasPermission(STAT);
        PageResp<OaWorkReportResp> resp = new PageResp<>();
        resp.setList(service.team(tenantId, userId, stat, type, date, deptId, authorId, pageQuery.getPage(), pageQuery
            .getSize()));
        resp.setTotal(service.countTeam(tenantId, userId, stat, type, date, deptId, authorId));
        return resp;
    }

    @Operation(summary = "报告详情与批注")
    @SaCheckPermission(value = {MINE, STAT}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public OaWorkReportDetailResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), userId(), StpUtil.hasPermission(STAT), id);
    }

    @Operation(summary = "批注报告")
    @SaCheckPermission(value = {MINE, STAT}, mode = SaMode.OR)
    @PostMapping("/{id}/comments")
    public OaWorkReportDetailResp comment(@PathVariable Long id, @Valid @RequestBody OaWorkReportCommentReq req) {
        return service.comment(tenantId(), userId(), StpUtil.hasPermission(STAT), id, req);
    }

    @Operation(summary = "周期提交统计")
    @SaCheckPermission(value = {MINE, STAT}, mode = SaMode.OR)
    @GetMapping("/summary")
    public OaWorkReportSummaryResp summary(@RequestParam String type,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) Long deptId) {
        return service.summary(tenantId(), userId(), StpUtil.hasPermission(STAT), type, date, deptId);
    }

    @Operation(summary = "催交未提交成员")
    @SaCheckPermission(value = {MINE, STAT}, mode = SaMode.OR)
    @PostMapping("/remind")
    public Map<String, Integer> remind(@Valid @RequestBody OaWorkReportRemindReq req) {
        return Map.of("reminded", service.remind(tenantId(), userId(), StpUtil.hasPermission(STAT), req));
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
