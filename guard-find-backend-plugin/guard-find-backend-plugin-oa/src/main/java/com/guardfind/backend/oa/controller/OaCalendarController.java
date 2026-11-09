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
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.oa.model.OaCalendarEventReq;
import com.guardfind.backend.oa.model.OaCalendarEventResp;
import com.guardfind.backend.oa.model.OaDeptOptionResp;
import com.guardfind.backend.oa.service.OaCalendarService;
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
import top.continew.starter.log.annotation.Log;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OA 日程 API：个人日程与成员邀请、部门日程发布、按区间查看可见日程。
 */
@Tag(name = "OA 日程 API")
@Log(module = "OA 日程")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/calendar")
public class OaCalendarController {

    private static final String MINE = "system:oa:calendar:mine";
    private static final String DEPT = "system:oa:calendar:dept";

    private final OaCalendarService service;

    @Operation(summary = "区间内可见日程")
    @SaCheckPermission(MINE)
    @GetMapping
    public List<OaCalendarEventResp> events(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return service.events(tenantId(), userId(), from, to);
    }

    @Operation(summary = "日程详情")
    @SaCheckPermission(MINE)
    @GetMapping("/{id}")
    public OaCalendarEventResp detail(@PathVariable Long id) {
        return service.detail(tenantId(), userId(), id);
    }

    @Operation(summary = "可发布部门日程的部门")
    @SaCheckPermission(DEPT)
    @GetMapping("/depts")
    public List<OaDeptOptionResp> depts() {
        return service.publishableDepts(tenantId(), userId());
    }

    @Operation(summary = "新建日程")
    @SaCheckPermission(MINE)
    @PostMapping
    public OaCalendarEventResp create(@Valid @RequestBody OaCalendarEventReq req) {
        return service.create(tenantId(), userId(), StpUtil.hasPermission(DEPT), req);
    }

    @Operation(summary = "修改日程")
    @SaCheckPermission(MINE)
    @PutMapping("/{id}")
    public OaCalendarEventResp update(@PathVariable Long id, @Valid @RequestBody OaCalendarEventReq req) {
        return service.update(tenantId(), userId(), StpUtil.hasPermission(DEPT), id, req);
    }

    @Operation(summary = "取消日程")
    @SaCheckPermission(MINE)
    @PostMapping("/{id}/cancel")
    public OaCalendarEventResp cancel(@PathVariable Long id) {
        return service.cancel(tenantId(), userId(), id);
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
