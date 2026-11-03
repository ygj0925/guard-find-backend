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
