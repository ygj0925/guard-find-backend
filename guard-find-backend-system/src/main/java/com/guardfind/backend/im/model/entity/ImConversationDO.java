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
import com.guardfind.backend.common.base.model.entity.BaseDO;
import lombok.Data;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * IM 会话实体(用户 A < 用户 B,保证唯一对)
 */
@Data
@TableName("gf_im_conversation")
public class ImConversationDO extends BaseDO {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer hiddenA;
    private Integer hiddenB;
    private Long userAId;
    private Long userBId;
    private String lastMessage;
    private LocalDateTime lastTime;
    private Integer unreadA;
    private Integer unreadB;
}
