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

package com.guardfind.backend.im.websocket;

import com.guardfind.backend.miniapp.util.StpMiniappUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * IM WebSocket 握手拦截器:验证 Sa-Token 并将 userId 写入 session attributes。
 * 客户端连接 URL: ws://host/miniapp/ws/im?token={saToken}
 */
@Slf4j
@Component
@lombok.RequiredArgsConstructor
public class ImHandshakeInterceptor implements HandshakeInterceptor {

    private final com.guardfind.backend.miniapp.mapper.MiniappUserMapper users;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = servletRequest.getServletRequest().getParameter("token");
            if (token != null && !token.isBlank()) {
                try {
                    Object loginId = StpMiniappUtil.stpLogic().getLoginIdByToken(token);
                    if (loginId != null) {
                        var user = users.selectById(Long.parseLong(loginId.toString()));
                        if (user == null || !Integer.valueOf(1).equals(user.getAccountStatus()))
                            return false;
                        attributes.put("token", token);
                        attributes.put("userId", Long.parseLong(loginId.toString()));
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("[ws:im] token 验证失败: {}", e.getMessage());
                }
            }
        }
        log.warn("[ws:im] WebSocket 握手未通过鉴权(app 登录域)");
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
