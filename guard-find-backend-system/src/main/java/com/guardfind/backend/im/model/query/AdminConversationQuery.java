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

package com.guardfind.backend.im.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "后台私信会话查询")
public class AdminConversationQuery {
    @Size(max = 64)
    @Schema(description = "用户昵称模糊搜索或完整用户 ID，空白表示全部")
    private String keyword;

    @Schema(description = "仅返回存在用户未读消息的会话，默认 false")
    private Boolean onlyUnread;

    @Positive
    @Schema(description = "上一批最后一条会话的 lastMessageId，用于向下滚动加载；省略时兼容 page 分页")
    private Long beforeId;
}
