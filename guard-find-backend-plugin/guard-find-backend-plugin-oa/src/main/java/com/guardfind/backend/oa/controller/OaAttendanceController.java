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
import com.guardfind.backend.oa.model.OaAttendanceDailyResp;
import com.guardfind.backend.oa.model.OaAttendanceImportReq;
import com.guardfind.backend.oa.model.OaAttendanceImportResp;
import com.guardfind.backend.oa.model.OaAttendanceRecordResp;
import com.guardfind.backend.oa.model.OaAttendanceRequestResp;
import com.guardfind.backend.oa.model.OaAttendanceSummaryResp;
import com.guardfind.backend.oa.model.OaAttendanceTemplateResp;
import com.guardfind.backend.oa.model.OaScheduleResp;
import com.guardfind.backend.oa.model.OaScheduleSaveReq;
import com.guardfind.backend.oa.model.OaShiftReq;
import com.guardfind.backend.oa.model.OaShiftResp;
import com.guardfind.backend.oa.service.OaAttendanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * OA 假勤 API：班次、排班、考勤导入、日报与月度汇总、补卡/加班/外出台账，员工查询自己的考勤与申请。
 */
@Tag(name = "OA 假勤 API")
@Log(module = "OA 假勤")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/attendance")
public class OaAttendanceController {

    private final OaAttendanceService service;

    @Operation(summary = "班次列表")
    @SaCheckPermission("system:oa:attendance:list")
    @GetMapping("/shifts")
    public List<OaShiftResp> shifts(@RequestParam(required = false) String status) {
        return service.shifts(tenantId(), status);
    }

    @Operation(summary = "新增班次")
    @SaCheckPermission("system:oa:attendance:manage")
    @PostMapping("/shifts")
    public OaShiftResp createShift(@Valid @RequestBody OaShiftReq req) {
        return service.createShift(tenantId(), userId(), req);
    }

    @Operation(summary = "修改班次")
    @SaCheckPermission("system:oa:attendance:manage")
    @PutMapping("/shifts/{id}")
    public OaShiftResp updateShift(@PathVariable Long id, @Valid @RequestBody OaShiftReq req) {
        return service.updateShift(tenantId(), userId(), id, req);
    }

    @Operation(summary = "排班查询")
    @SaCheckPermission("system:oa:attendance:list")
    @GetMapping("/schedules")
    public List<OaScheduleResp> schedules(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                          @RequestParam(required = false) Long userId) {
        return service.schedules(tenantId(), start, end, userId);
    }

    @Operation(summary = "批量排班")
    @SaCheckPermission("system:oa:attendance:manage")
    @PostMapping("/schedules")
    public int saveSchedules(@Valid @RequestBody OaScheduleSaveReq req) {
        return service.saveSchedules(tenantId(), userId(), req);
    }

    @Operation(summary = "考勤记录分页")
    @SaCheckPermission("system:oa:attendance:list")
    @GetMapping("/records")
    public PageResp<OaAttendanceRecordResp> records(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
                                                    @RequestParam(required = false) Long userId,
                                                    PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaAttendanceRecordResp> resp = new PageResp<>();
        resp.setList(service.records(tenantId, start, end, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRecords(tenantId, start, end, userId));
        return resp;
    }

    @Operation(summary = "考勤导入预检")
    @SaCheckPermission("system:oa:attendance:import")
    @PostMapping("/import/preview")
    public OaAttendanceImportResp previewImport(@Valid @RequestBody OaAttendanceImportReq req) {
        return service.previewImport(tenantId(), req);
    }

    @Operation(summary = "考勤导入提交")
    @SaCheckPermission("system:oa:attendance:import")
    @PostMapping("/import/commit")
    public OaAttendanceImportResp commitImport(@Valid @RequestBody OaAttendanceImportReq req) {
        return service.commitImport(tenantId(), userId(), req);
    }

    @Operation(summary = "员工考勤日报")
    @SaCheckPermission("system:oa:attendance:list")
    @GetMapping("/daily")
    public List<OaAttendanceDailyResp> daily(@RequestParam Long userId,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return service.daily(tenantId(), userId, start, end);
    }

    @Operation(summary = "月度考勤汇总")
    @SaCheckPermission("system:oa:attendance:list")
    @GetMapping("/summary")
    public List<OaAttendanceSummaryResp> summary(@RequestParam String month,
                                                 @RequestParam(required = false) String keyword) {
        return service.summary(tenantId(), month(month), keyword);
    }

    @Operation(summary = "补卡/加班/外出台账")
    @SaCheckPermission("system:oa:attendance:list")
    @GetMapping("/requests")
    public PageResp<OaAttendanceRequestResp> requests(@RequestParam(required = false) String kind,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String keyword,
                                                      PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaAttendanceRequestResp> resp = new PageResp<>();
        resp.setList(service.requests(tenantId, kind, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRequests(tenantId, kind, status, keyword, null));
        return resp;
    }

    @Operation(summary = "初始化补卡、加班与外出模板")
    @SaCheckPermission("system:oa:attendance:manage")
    @PostMapping("/template")
    public OaAttendanceTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的考勤日报")
    @SaCheckPermission("system:oa:attendance:mine")
    @GetMapping("/mine/daily")
    public List<OaAttendanceDailyResp> myDaily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return service.daily(tenantId(), userId(), start, end);
    }

    @Operation(summary = "我的补卡/加班/外出申请")
    @SaCheckPermission("system:oa:attendance:mine")
    @GetMapping("/mine/requests")
    public PageResp<OaAttendanceRequestResp> myRequests(@RequestParam(required = false) String kind,
                                                        @RequestParam(required = false) String status,
                                                        PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaAttendanceRequestResp> resp = new PageResp<>();
        resp.setList(service.requests(tenantId, kind, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countRequests(tenantId, kind, status, null, userId));
        return resp;
    }

    private static YearMonth month(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BusinessException("月份格式必须是 yyyy-MM");
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
