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

import com.guardfind.backend.im.model.resp.ImConversationResp;
import com.guardfind.backend.im.model.resp.ImMessageResp;

import java.util.List;

/**
 * IM 私信业务接口(C 端)。轮询方案:客户端定时拉取新消息(3-5 秒间隔)。
 * WebSocket 未通过鸿蒙验证,一期用 REST 轮询;后续可升级 WS 推送。
 */
public interface ImService {

    /**
     * 获取或创建与目标用户的会话(幂等)
     */
    ImConversationResp getOrCreate(Long targetUserId);

    /**
     * 我的会话列表(按最后消息时间倒序,含对方信息和未读数)
     */
    List<ImConversationResp> conversations();

    /**
     * 获取某会话的消息列表(可选 afterId 增量拉取)
     */
    List<ImMessageResp> messages(Long conversationId, Long afterId);

    /**
     * 发送消息
     */
    ImMessageResp send(Long conversationId, String content);

    /**
     * 标记会话已读
     */
    void markRead(Long conversationId);

    /**
     * 删除会话(仅自己视角隐藏,对方不受影响)
     */
    void delete(Long conversationId);

    /**
     * 联系客服:拉取我与官方客服的消息(可选 afterId 增量);拉取即把客服回复标已读。
     * senderId 固定映射:自己的消息为本人 ID,客服消息为 0。
     */
    List<ImMessageResp> supportMessages(Long afterId);

    /**
     * 联系客服:发送消息给官方客服(管理员在后台"用户沟通"页接待)。
     */
    ImMessageResp sendSupport(String content);
}
