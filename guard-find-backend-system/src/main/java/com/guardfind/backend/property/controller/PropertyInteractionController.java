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

package com.guardfind.backend.property.controller;

import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.property.model.req.PropertyBookingReq;
import com.guardfind.backend.property.model.resp.PropertyBookingResp;
import com.guardfind.backend.property.model.resp.PropertyResp;
import com.guardfind.backend.property.service.PropertyInteractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.Map;

/**
 * 房源互动 API(C 端):收藏、浏览历史、预约看房。
 */
@Tag(name = "房源互动 API")
@Log(module = "房源互动")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/miniapp/property")
public class PropertyInteractionController {

    private final PropertyInteractionService interactionService;

    @Operation(summary = "收藏/取消收藏", description = "切换收藏状态,返回切换后是否已收藏")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @PostMapping("/{id}/favorite")
    public Map<String, Boolean> toggleFavorite(@PathVariable Long id) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return Map.of("favorited", interactionService.toggleFavorite(userId, id));
    }

    @Operation(summary = "我的收藏", description = "收藏时间倒序")
    @GetMapping("/favorites")
    public PageResp<PropertyResp> favorites(PageQuery pageQuery) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return interactionService.favorites(userId, pageQuery);
    }

    @Operation(summary = "浏览历史", description = "最近浏览时间倒序")
    @GetMapping("/history")
    public PageResp<PropertyResp> history(PageQuery pageQuery) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return interactionService.history(userId, pageQuery);
    }

    @Operation(summary = "创建看房预约", description = "仅可预约他人已上架房源;预约后站内信通知房东")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @PostMapping("/{id}/booking")
    public Long createBooking(@PathVariable Long id, @RequestBody @Valid PropertyBookingReq req) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return interactionService.createBooking(userId, id, req);
    }

    @Operation(summary = "预约列表", description = "role=guest 我预约的(默认);role=owner 我收到的(房东视角)")
    @Parameter(name = "role", description = "guest/owner")
    @GetMapping("/bookings")
    public PageResp<PropertyBookingResp> bookings(@RequestParam(defaultValue = "guest") String role,
                                                  PageQuery pageQuery) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return interactionService.bookings(userId, role, pageQuery);
    }

    @Operation(summary = "预约状态流转", description = "预约人:取消(4);房东:确认(2)/拒绝(3)")
    @Parameter(name = "id", description = "预约 ID", required = true)
    @PostMapping("/booking/{id}/status")
    public void updateBookingStatus(@PathVariable Long id, @RequestParam Integer status) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        interactionService.updateBookingStatus(userId, id, status);
    }
}
