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

package com.guardfind.backend.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import com.guardfind.backend.im.model.query.AdminConversationQuery;
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

import com.guardfind.backend.im.model.resp.AdminConversationResp;
import com.guardfind.backend.im.model.resp.AdminMessageResp;
import com.guardfind.backend.im.service.AdminImService;

import java.util.List;
import java.util.Map;

/**
 * 后台↔用户私信 API(后台沟通页面)
 *
 * <p>
 * 权限码与 {@code guardfind_business_data.sql} 中登记的菜单权限逐字一致。
 * </p>
 */
@Tag(name = "后台私信 API")
@Log(module = "用户沟通")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/im")
public class AdminImController {

    private final AdminImService adminImService;

    @Operation(summary = "会话列表", description = "全量昵称模糊搜索或用户 ID 精确搜索，支持未读筛选；最近消息倒序，beforeId 游标滚动加载，total 为全部匹配会话数")
    @SaCheckPermission("system:im:list")
    @GetMapping("/conversations")
    public PageResp<AdminConversationResp> conversations(PageQuery pageQuery, @Valid AdminConversationQuery query) {
        return adminImService.conversations(pageQuery, query);
    }

    @Operation(summary = "私信记录", description = "与指定用户的消息,支持 afterId 增量拉取;拉取即标已读")
    @Parameter(name = "userId", description = "用户 ID", required = true)
    @SaCheckPermission("system:im:list")
    @GetMapping("/conversation/{userId}/messages")
    public List<AdminMessageResp> messages(@PathVariable Long userId, @RequestParam(required = false) Long afterId) {
        return adminImService.messages(userId, afterId);
    }

    @Operation(summary = "发送私信", description = "管理员向用户发送私信;用户在线时实时推送")
    @Parameter(name = "userId", description = "用户 ID", required = true)
    @SaCheckPermission("system:im:send")
    @PostMapping("/conversation/{userId}/message")
    public AdminMessageResp send(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        return adminImService.send(userId, body.get("content"));
    }
}
