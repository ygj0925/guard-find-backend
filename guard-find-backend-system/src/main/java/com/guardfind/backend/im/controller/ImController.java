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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.enums.LimitType;

import com.guardfind.backend.im.model.resp.ImConversationResp;
import com.guardfind.backend.im.model.resp.ImMessageResp;
import com.guardfind.backend.im.service.ImService;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * IM 私信 API(C 端)。轮询方案:客户端 3-5 秒拉一次新消息。
 */
@Tag(name = "IM 私信 API")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/miniapp/im")
public class ImController {

    private final ImService imService;

    @Operation(summary = "获取或创建会话", description = "与目标用户的 1:1 会话,幂等")
    @Parameter(name = "targetUserId", description = "对方用户 ID", required = true)
    @PostMapping("/conversation/{targetUserId}")
    public ImConversationResp getOrCreate(@PathVariable Long targetUserId) {
        return imService.getOrCreate(targetUserId);
    }

    @Operation(summary = "我的会话列表", description = "按最后消息时间倒序,含未读数")
    @GetMapping("/conversations")
    public List<ImConversationResp> conversations() {
        return imService.conversations();
    }

    @Operation(summary = "获取消息", description = "afterId 传上次最后一条消息 ID 可增量拉取;自动标记已读")
    @Parameter(name = "id", description = "会话 ID", required = true)
    @Parameter(name = "afterId", description = "此 ID 之后的消息,增量拉取")
    @GetMapping("/conversation/{id}/messages")
    public List<ImMessageResp> messages(@PathVariable Long id, @RequestParam(required = false) Long afterId) {
        return imService.messages(id, afterId);
    }

    @Operation(summary = "发送消息", description = "纯文本,2000 字以内")
    @Parameter(name = "id", description = "会话 ID", required = true)
    @RateLimiter(name = "APP:IM:SEND", rate = 60, interval = 1, unit = TimeUnit.MINUTES, type = LimitType.IP)
    @PostMapping("/conversation/{id}/message")
    public ImMessageResp send(@PathVariable Long id, @RequestBody @Valid SendMsgReq req) {
        return imService.send(id, req.content());
    }

    @Operation(summary = "标记会话已读")
    @Parameter(name = "id", description = "会话 ID", required = true)
    @PostMapping("/conversation/{id}/read")
    public void markRead(@PathVariable Long id) {
        imService.markRead(id);
    }

    @Operation(summary = "删除会话", description = "仅自己视角清零,对方不受影响")
    @Parameter(name = "id", description = "会话 ID", required = true)
    @DeleteMapping("/conversation/{id}")
    public void delete(@PathVariable Long id) {
        imService.delete(id);
    }

    @Operation(summary = "联系客服-拉取消息", description = "我与官方客服的消息,afterId 增量;客服消息 senderId=0")
    @Parameter(name = "afterId", description = "此 ID 之后的消息,增量拉取")
    @GetMapping("/support/messages")
    public List<ImMessageResp> supportMessages(@RequestParam(required = false) Long afterId) {
        return imService.supportMessages(afterId);
    }

    @Operation(summary = "联系客服-发送消息", description = "发送给官方客服,管理员在后台接待")
    @RateLimiter(name = "APP:IM:SUPPORT", rate = 30, interval = 1, unit = TimeUnit.MINUTES, type = LimitType.IP)
    @PostMapping("/support/message")
    public ImMessageResp sendSupport(@RequestBody @Valid SendMsgReq req) {
        return imService.sendSupport(req.content());
    }

    public record SendMsgReq(@NotBlank(message = "消息内容不能为空") String content) {}
}
