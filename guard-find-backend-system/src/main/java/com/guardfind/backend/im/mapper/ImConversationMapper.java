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

import com.guardfind.backend.im.model.entity.ImConversationDO;
import top.continew.starter.data.mapper.BaseMapper;

public interface ImConversationMapper extends BaseMapper<ImConversationDO> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM gf_im_conversation WHERE id=#{id} AND deleted=0 FOR UPDATE")
    ImConversationDO lockById(@org.apache.ibatis.annotations.Param("id") Long id);
}
