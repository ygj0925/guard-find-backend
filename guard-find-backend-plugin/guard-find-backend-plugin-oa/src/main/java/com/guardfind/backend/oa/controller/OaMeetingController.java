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
import com.guardfind.backend.oa.model.OaMeetingBookReq;
import com.guardfind.backend.oa.model.OaMeetingBookingResp;
import com.guardfind.backend.oa.model.OaMeetingRoomReq;
import com.guardfind.backend.oa.model.OaMeetingRoomResp;
import com.guardfind.backend.oa.model.OaResourceCancelReq;
import com.guardfind.backend.oa.service.OaMeetingService;
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
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import java.time.LocalDate;
import java.util.List;

/**
 * OA 会议室 API：行政维护会议室档案与预订台账，员工查看空闲时段并直接预订免审批会议室。
 */
@Tag(name = "OA 会议室 API")
@Log(module = "OA 会议室")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/meeting")
public class OaMeetingController {

    private static final String LIST = "system:oa:meeting:list";
    private static final String MANAGE = "system:oa:meeting:manage";
    private static final String BOOK = "system:oa:meeting:book";

    private final OaMeetingService service;

    @Operation(summary = "会议室档案")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping("/rooms")
    public List<OaMeetingRoomResp> rooms(@RequestParam(required = false) String status) {
        return service.rooms(tenantId(), status);
    }

    @Operation(summary = "新建会议室")
    @SaCheckPermission(MANAGE)
    @PostMapping("/rooms")
    public OaMeetingRoomResp createRoom(@Valid @RequestBody OaMeetingRoomReq req) {
        return service.createRoom(tenantId(), userId(), req);
    }

    @Operation(summary = "修改会议室")
    @SaCheckPermission(MANAGE)
    @PutMapping("/rooms/{id}")
    public OaMeetingRoomResp updateRoom(@PathVariable Long id, @Valid @RequestBody OaMeetingRoomReq req) {
        return service.updateRoom(tenantId(), userId(), id, req);
    }

    @Operation(summary = "预订台账分页")
    @SaCheckPermission(value = {LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping("/bookings")
    public PageResp<OaMeetingBookingResp> bookings(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Long roomId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                   PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaMeetingBookingResp> resp = new PageResp<>();
        resp.setList(service.bookings(tenantId, status, roomId, null, date, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countBookings(tenantId, status, roomId, null, date));
        return resp;
    }

    @Operation(summary = "行政取消预订")
    @SaCheckPermission(MANAGE)
    @PostMapping("/bookings/{id}/cancel")
    public OaMeetingBookingResp cancel(@PathVariable Long id, @Valid @RequestBody OaResourceCancelReq req) {
        return service.cancelByAdmin(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化会议室预订审批模板")
    @SaCheckPermission(MANAGE)
    @PostMapping("/template")
    public OaContractTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "可预订会议室")
    @SaCheckPermission(BOOK)
    @GetMapping("/book/rooms")
    public List<OaMeetingRoomResp> bookableRooms() {
        return service.rooms(tenantId(), "ENABLED");
    }

    @Operation(summary = "某天占用时段")
    @SaCheckPermission(value = {BOOK, LIST, MANAGE}, mode = SaMode.OR)
    @GetMapping("/book/schedule")
    public List<OaMeetingBookingResp> schedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.schedule(tenantId(), date);
    }

    @Operation(summary = "我的预订")
    @SaCheckPermission(BOOK)
    @GetMapping("/book/bookings")
    public PageResp<OaMeetingBookingResp> myBookings(@RequestParam(required = false) String status,
                                                     PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaMeetingBookingResp> resp = new PageResp<>();
        resp.setList(service.bookings(tenantId, status, null, userId, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countBookings(tenantId, status, null, userId, null));
        return resp;
    }

    @Operation(summary = "直接预订免审批会议室")
    @SaCheckPermission(BOOK)
    @PostMapping("/book/bookings")
    public OaMeetingBookingResp book(@Valid @RequestBody OaMeetingBookReq req) {
        return service.book(tenantId(), userId(), req);
    }

    @Operation(summary = "取消我的预订")
    @SaCheckPermission(BOOK)
    @PostMapping("/book/bookings/{id}/cancel")
    public OaMeetingBookingResp cancelMine(@PathVariable Long id, @Valid @RequestBody OaResourceCancelReq req) {
        return service.cancelMine(tenantId(), userId(), id, req);
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
