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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * IM WebSocket Handler:处理实时消息推送和接收。
 *
 * <p>
 * 协议(纯 JSON 文本帧):
 * 客户端发送: {"action":"send","conversationId":123,"content":"hello"}
 * 服务端推送: {"action":"new_message","conversationId":123,"senderId":456,"content":"hello","messageId":789}
 * 服务端推送: {"action":"unread_update","conversationId":123,"unreadCount":3}
 * </p>
 *
 * <p>
 * 连接鉴权:握手阶段通过 URL 参数 token 验证 Sa-Token。
 * 连接建立后,session 与 userId 绑定,服务端可向指定用户推送。
 * 断线后客户端自动降级为 REST 轮询。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImWebSocketHandler extends TextWebSocketHandler {

    /** userId → 该用户所有活跃的 WebSocketSession(可能多设备) */
    private static final Map<Long, Set<WebSocketSession>> USER_SESSIONS = new ConcurrentHashMap<>();

    private final com.guardfind.backend.miniapp.mapper.MiniappUserMapper users;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = getUserId(session);
        if (userId == null) {
            log.warn("[ws:im] 连接缺少有效 token,关闭");
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (Exception ignored) {
            }
            return;
        }
        USER_SESSIONS.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>()).add(session);
        log.info("[ws:im] 用户 {} 连接,当前在线会话数 {}", userId, USER_SESSIONS.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long)session.getAttributes().get("userId");
        if (userId != null) {
            Set<WebSocketSession> sessions = USER_SESSIONS.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty())
                    USER_SESSIONS.remove(userId);
            }
            log.info("[ws:im] 用户 {} 断开", userId);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = getUserId(session);
        if (userId == null)
            return;
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String action = node.path("action").asText("");
            if ("ping".equals(action)) {
                synchronized (session) {
                    session.sendMessage(new TextMessage("{\"action\":\"pong\"}"));
                }
            }
            // 一期:发送仍走 REST(服务端做校验/限流/通知),WS 仅用于接收推送
            // 二期:可在 WS 中直接处理发送,减少 REST 往返
        } catch (Exception e) {
            log.warn("[ws:im] 处理消息异常: {}", e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long userId = (Long)session.getAttributes().get("userId");
        if (userId != null) {
            Set<WebSocketSession> sessions = USER_SESSIONS.get(userId);
            if (sessions != null)
                sessions.remove(session);
        }
    }

    /**
     * 向指定用户的所有活跃连接推送新消息
     */
    public void pushNewMessage(Long targetUserId, Long conversationId, Long senderId, String content, Long messageId) {
        Set<WebSocketSession> sessions = USER_SESSIONS.get(targetUserId);
        if (sessions == null || sessions.isEmpty())
            return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("action", "new_message", "conversationId", conversationId
                .toString(), "senderId", senderId.toString(), "content", content, "messageId", messageId.toString()));
        } catch (Exception e) {
            log.warn("[ws:im] 序列化推送消息失败: {}", e.getMessage());
            return;
        }
        TextMessage textMessage = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            try {
                synchronized (session) {
                    if (session.isOpen() && getUserId(session) != null)
                        session.sendMessage(textMessage);
                }
            } catch (Exception e) {
                log.warn("[ws:im] 推送消息到用户 {} 失败: {}", targetUserId, e.getMessage());
            }
        }
    }

    /**
     * 向指定用户推送后台客服消息(管理员与用户沟通)
     */
    public void pushAdminMessage(Long targetUserId, Long messageId, String content, String adminName) {
        Set<WebSocketSession> sessions = USER_SESSIONS.get(targetUserId);
        if (sessions == null || sessions.isEmpty())
            return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of("action", "admin_message", "messageId", messageId
                .toString(), "content", content, "adminName", adminName == null ? "官方客服" : adminName));
        } catch (Exception e) {
            log.warn("[ws:im] 序列化客服消息失败: {}", e.getMessage());
            return;
        }
        TextMessage textMessage = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            try {
                synchronized (session) {
                    if (session.isOpen() && getUserId(session) != null)
                        session.sendMessage(textMessage);
                }
            } catch (Exception e) {
                log.warn("[ws:im] 推送客服消息到用户 {} 失败: {}", targetUserId, e.getMessage());
            }
        }
    }

    /**
     * 从 WebSocketSession attributes 中获取用户 ID(握手时由 Interceptor 写入)
     */
    private Long getUserId(WebSocketSession session) {
        Object token = session.getAttributes().get("token");
        if (token == null || com.guardfind.backend.miniapp.util.StpMiniappUtil.getLoginId(token.toString()) == null)
            return null;
        Object userId = session.getAttributes().get("userId");
        if (!(userId instanceof Long))
            return null;
        var user = users.selectById((Long)userId);
        return user != null && Integer.valueOf(1).equals(user.getAccountStatus()) ? (Long)userId : null;
    }
}
