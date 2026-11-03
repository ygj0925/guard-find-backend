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

package com.guardfind.backend.im.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 后台与用户私信实体。管理员与 App 用户分属两个登录域,不走 gf_im_conversation(会话键为 App 用户对),
 * 独立建表以 user_id 聚合会话,避免 sys_user 与 gf_app_user 的 ID 空间冲突。
 */
@Data
@TableName("gf_admin_message")
public class AdminMessageDO {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    /** 发送方(ADMIN/USER) */
    private String senderType;
    /** 管理员 ID(sender_type=ADMIN 时) */
    private Long adminId;
    private String adminName;
    private String content;
    /** 接收方是否已读(0:否;1:是) */
    private Integer isRead;
    private LocalDateTime createTime;
}
