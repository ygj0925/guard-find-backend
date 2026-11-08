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
import com.guardfind.backend.oa.model.OaNotificationResp;
import com.guardfind.backend.oa.model.OaUnreadCountResp;
import com.guardfind.backend.oa.service.OaNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

/**
 * OA 站内通知 API：只看当前用户的收件箱。
 */
@Tag(name = "OA 通知 API")
@Log(module = "OA 通知")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/notification")
public class OaNotificationController {

    private final OaNotificationService service;

    @Operation(summary = "通知分页")
    @SaCheckPermission("system:oa:notification:list")
    @GetMapping("/page")
    public PageResp<OaNotificationResp> page(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                             PageQuery pageQuery) {
        OaNotificationService.NotificationPage page = service.page(tenantId(), userId(), unreadOnly, pageQuery
            .getPage(), pageQuery.getSize());
        PageResp<OaNotificationResp> resp = new PageResp<>();
        resp.setList(page.items());
        resp.setTotal(page.total());
        return resp;
    }

    @Operation(summary = "未读通知数")
    @SaCheckPermission("system:oa:notification:list")
    @GetMapping("/unread-count")
    public OaUnreadCountResp unreadCount() {
        return new OaUnreadCountResp(service.unreadCount(tenantId(), userId()));
    }

    @Operation(summary = "标记通知已读")
    @SaCheckPermission("system:oa:notification:read")
    @PostMapping("/{id}/read")
    public void read(@PathVariable Long id) {
        service.markRead(tenantId(), userId(), id);
    }

    @Operation(summary = "全部标记已读")
    @SaCheckPermission("system:oa:notification:read")
    @PostMapping("/read-all")
    public int readAll() {
        return service.markAllRead(tenantId(), userId());
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
