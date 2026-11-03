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

package com.guardfind.backend.im.mapper;

import com.guardfind.backend.im.model.entity.AdminMessageDO;
import top.continew.starter.data.mapper.BaseMapper;

public interface AdminMessageMapper extends BaseMapper<AdminMessageDO> {
    @org.apache.ibatis.annotations.Select("SELECT COUNT(DISTINCT user_id) FROM gf_admin_message")
    long countConversations();

    @org.apache.ibatis.annotations.Select("SELECT m.* FROM gf_admin_message m JOIN (SELECT MAX(id) id FROM gf_admin_message GROUP BY user_id) latest ON latest.id=m.id ORDER BY m.id DESC LIMIT #{size} OFFSET #{offset}")
    java.util.List<AdminMessageDO> conversationPage(@org.apache.ibatis.annotations.Param("offset") long offset,
                                                    @org.apache.ibatis.annotations.Param("size") int size);

    @org.apache.ibatis.annotations.Select({
        "<script>SELECT user_id, COUNT(*) unread_count FROM gf_admin_message WHERE sender_type='USER' AND is_read=0 AND user_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY user_id</script>"})
    java.util.List<com.guardfind.backend.im.model.resp.AdminUnreadResp> unreadCounts(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);
}
