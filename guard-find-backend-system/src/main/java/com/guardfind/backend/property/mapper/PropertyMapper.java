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

package com.guardfind.backend.property.mapper;

import com.guardfind.backend.property.model.entity.PropertyDO;
import top.continew.starter.data.mapper.BaseMapper;

public interface PropertyMapper extends BaseMapper<PropertyDO> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM gf_property WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    PropertyDO lockById(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT p.* FROM gf_property p JOIN gf_property_favorite f ON f.property_id=p.id WHERE f.user_id=#{userId} AND p.deleted=0 AND p.status=2 ORDER BY f.create_time DESC, f.id DESC")
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PropertyDO> favoritePage(com.baomidou.mybatisplus.extension.plugins.pagination.Page<PropertyDO> page,
                                                                                        @org.apache.ibatis.annotations.Param("userId") Long userId);

    @org.apache.ibatis.annotations.Select("SELECT p.* FROM gf_property p JOIN gf_property_view_history h ON h.property_id=p.id WHERE h.user_id=#{userId} AND p.deleted=0 AND p.status=2 ORDER BY h.view_time DESC, h.id DESC")
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PropertyDO> historyPage(com.baomidou.mybatisplus.extension.plugins.pagination.Page<PropertyDO> page,
                                                                                       @org.apache.ibatis.annotations.Param("userId") Long userId);
}
