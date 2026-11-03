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

package com.guardfind.backend.im.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappUserDO;
import com.guardfind.backend.im.mapper.AdminMessageMapper;
import com.guardfind.backend.im.model.entity.AdminMessageDO;
import com.guardfind.backend.im.model.query.AdminConversationQuery;
import com.guardfind.backend.im.model.resp.AdminConversationResp;
import com.guardfind.backend.im.model.resp.AdminMessageResp;
import com.guardfind.backend.im.service.AdminImService;
import com.guardfind.backend.im.websocket.ImWebSocketHandler;
import com.guardfind.backend.system.model.entity.user.UserDO;
import com.guardfind.backend.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 后台↔用户私信实现。消息按 user_id 聚合为会话;
 * 管理员发消息时通过 ImWebSocketHandler 实时推给在线用户,离线用户由 App 端轮询兜底。
 */
@Service
@RequiredArgsConstructor
public class AdminImServiceImpl implements AdminImService {

    private static final String SENDER_ADMIN = "ADMIN";
    private static final String SENDER_USER = "USER";

    private final AdminMessageMapper adminMessageMapper;
    private final MiniappUserMapper appUserMapper;
    private final ObjectProvider<UserService> userServiceProvider;
    private final ObjectProvider<ImWebSocketHandler> wsHandlerProvider;

    @Override
    public PageResp<AdminConversationResp> conversations(PageQuery pageQuery, AdminConversationQuery query) {
        int page = pageQuery == null || pageQuery.getPage() == null ? 1 : Math.max(1, pageQuery.getPage());
        int size = pageQuery == null || pageQuery.getSize() == null
            ? 10
            : Math.max(1, Math.min(100, pageQuery.getSize()));
        String keyword = query == null ? null : StrUtil.trimToNull(query.getKeyword());
        Long searchUserId = null;
        if (keyword != null && keyword.matches("[0-9]+")) {
            try {
                searchUserId = Long.valueOf(keyword);
            } catch (NumberFormatException ignored) {
                // 超过 Long 范围的数字仍可作为昵称搜索，不转换成不精确的数值。
            }
        }
        String escapedKeyword = keyword == null ? null : keyword.replace("!", "!!").replace("%", "!%")
            .replace("_", "!_");
        boolean onlyUnread = query != null && Boolean.TRUE.equals(query.getOnlyUnread());
        Long beforeId = query == null ? null : query.getBeforeId();
        long offset = beforeId == null ? (long)(page - 1) * size : 0;
        List<AdminMessageDO> recent = adminMessageMapper.conversationPage(offset, size, escapedKeyword, searchUserId,
            onlyUnread, beforeId);
        Map<Long, AdminMessageDO> lastByUser = recent.stream()
            .collect(Collectors.toMap(AdminMessageDO::getUserId, m -> m));
        List<Long> pageUserIds = recent.stream().map(AdminMessageDO::getUserId).toList();
        Map<Long, Integer> unreadByUser = pageUserIds.isEmpty()
            ? Map.of()
            : adminMessageMapper.unreadCounts(pageUserIds)
                .stream()
                .collect(Collectors
                    .toMap(com.guardfind.backend.im.model.resp.AdminUnreadResp::getUserId, com.guardfind.backend.im.model.resp.AdminUnreadResp::getUnreadCount));
        Map<Long, MiniappUserDO> users = pageUserIds.isEmpty()
            ? Map.of()
            : appUserMapper.selectByIds(pageUserIds).stream().collect(Collectors.toMap(MiniappUserDO::getId, u -> u));
        List<AdminConversationResp> list = pageUserIds.stream().map(userId -> {
            AdminMessageDO last = lastByUser.get(userId);
            AdminConversationResp resp = new AdminConversationResp();
            resp.setUserId(userId);
            resp.setLastMessage(last.getContent());
            resp.setLastMessageId(last.getId());
            resp.setLastTime(last.getCreateTime());
            resp.setUnreadCount(unreadByUser.getOrDefault(userId, 0));
            MiniappUserDO user = users.get(userId);
            if (user != null) {
                resp.setNickname(user.getNickname());
                resp.setAvatar(user.getAvatar());
            }
            return resp;
        }).toList();
        PageResp<AdminConversationResp> pageResp = new PageResp<>();
        pageResp.setList(list);
        pageResp.setTotal(adminMessageMapper.countConversations(escapedKeyword, searchUserId, onlyUnread));
        return pageResp;
    }

    @Override
    public List<AdminMessageResp> messages(Long userId, Long afterId) {
        List<AdminMessageDO> msgs = adminMessageMapper.selectList(Wrappers.<AdminMessageDO>lambdaQuery()
            .eq(AdminMessageDO::getUserId, userId)
            .gt(afterId != null && afterId > 0, AdminMessageDO::getId, afterId)
            .orderByAsc(AdminMessageDO::getId)
            .last("LIMIT 100"));
        // 拉取即把用户发来的未读标已读
        if (!msgs.isEmpty())
            adminMessageMapper.update(null, Wrappers.<AdminMessageDO>update()
                .eq("user_id", userId)
                .in("id", msgs.stream().map(AdminMessageDO::getId).toList())
                .eq("sender_type", SENDER_USER)
                .eq("is_read", 0)
                .set("is_read", 1));
        return msgs.stream().map(AdminImServiceImpl::toResp).toList();
    }

    @Override
    public AdminMessageResp send(Long userId, String content) {
        if (StrUtil.isBlank(content)) {
            throw new BusinessException("消息内容不能为空");
        }
        String trimmed = StrUtil.trim(content);
        if (trimmed.length() > 2000) {
            throw new BusinessException("消息不能超过 2000 个字符");
        }
        MiniappUserDO user = appUserMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getAccountStatus())) {
            throw new BusinessException("用户不存在");
        }
        AdminMessageDO msg = new AdminMessageDO();
        msg.setUserId(userId);
        msg.setSenderType(SENDER_ADMIN);
        msg.setAdminId(StpUtil.getLoginIdAsLong());
        msg.setAdminName(currentAdminName());
        msg.setContent(trimmed);
        msg.setIsRead(0);
        msg.setCreateTime(LocalDateTime.now());
        adminMessageMapper.insert(msg);

        // 用户在线时实时推送(与 C 端 IM 共用同一条 /miniapp/ws/im 连接)
        ImWebSocketHandler wsHandler = wsHandlerProvider.getIfAvailable();
        if (wsHandler != null) {
            com.guardfind.backend.im.support.AfterCommit.run(() -> wsHandler.pushAdminMessage(userId, msg
                .getId(), trimmed, msg.getAdminName()));
        }

        return toResp(msg);
    }

    private String currentAdminName() {
        UserService userService = userServiceProvider.getIfAvailable();
        if (userService != null) {
            UserDO admin = userService.getById(StpUtil.getLoginIdAsLong());
            if (admin != null && StrUtil.isNotBlank(admin.getNickname())) {
                return admin.getNickname();
            }
        }
        return "官方客服";
    }

    private static AdminMessageResp toResp(AdminMessageDO msg) {
        AdminMessageResp resp = new AdminMessageResp();
        resp.setId(msg.getId());
        resp.setSenderType(msg.getSenderType());
        resp.setAdminName(msg.getAdminName());
        resp.setContent(msg.getContent());
        resp.setCreateTime(msg.getCreateTime());
        return resp;
    }
}
