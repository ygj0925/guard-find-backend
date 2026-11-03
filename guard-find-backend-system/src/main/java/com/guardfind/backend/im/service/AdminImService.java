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

package com.guardfind.backend.im.service;

import com.guardfind.backend.im.model.resp.AdminConversationResp;
import com.guardfind.backend.im.model.query.AdminConversationQuery;
import com.guardfind.backend.im.model.resp.AdminMessageResp;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

import java.util.List;

/**
 * 后台↔用户私信业务接口。管理员从后台管理页面直接与 App 用户沟通。
 */
public interface AdminImService {

    /**
     * 会话列表(按用户聚合,最近消息倒序,含管理员未读数)。
     */
    PageResp<AdminConversationResp> conversations(PageQuery pageQuery, AdminConversationQuery query);

    /**
     * 与指定用户的私信记录(afterId 增量);拉取同时把用户发来的消息标已读。
     */
    List<AdminMessageResp> messages(Long userId, Long afterId);

    /**
     * 管理员向用户发送私信;用户在线时通过 WebSocket 实时推送。
     */
    AdminMessageResp send(Long userId, String content);
}
