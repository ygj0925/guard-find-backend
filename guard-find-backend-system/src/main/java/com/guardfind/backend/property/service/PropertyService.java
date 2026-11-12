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

package com.guardfind.backend.property.service;

import com.guardfind.backend.property.model.query.PropertyQuery;
import com.guardfind.backend.property.model.req.PropertyReq;
import com.guardfind.backend.property.model.resp.PropertyResp;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

/**
 * 房产房源业务接口(C 端)。
 * 权限:浏览已上架=所有登录用户;管理(增删改/上下架)=仅房东本人。
 */
public interface PropertyService {

    Long create(Long userId, PropertyReq req);

    PageResp<PropertyResp> page(PropertyQuery query, PageQuery pageQuery);

    PageResp<PropertyResp> mine(Long userId, PageQuery pageQuery);

    PropertyResp detail(Long id);

    void update(Long id, Long userId, PropertyReq req);

    void delete(Long id, Long userId);

    void updateStatus(Long id, Long userId, Integer status);

    /**
     * 后台:全量房源分页(含草稿/下架),支持状态/城市/租售筛选。
     */
    PageResp<PropertyResp> pageForAdmin(Integer status, String city, String priceType, PageQuery pageQuery);

    /**
     * 后台:强制上下架(1 草稿 / 2 上架 / 3 下架),不受房东身份限制。
     */
    void updateStatusForAdmin(Long id, Integer status);

    /**
     * 后台:删除房源。
     */
    void deleteForAdmin(Long id);

    /**
     * 后台:设置/取消用户的房源管家身份。
     */
    void setManager(Long userId, boolean isManager);

    /**
     * 后台:管家用户分页(仅返回 is_property_manager = 1 的用户)。
     */
    PageResp<com.guardfind.backend.property.model.resp.PropertyManagerResp> listManagers(PageQuery pageQuery);

    PropertyResp detailForAdmin(Long id);

    Long createForAdmin(Long ownerId, PropertyReq req);

    void updateForAdmin(Long id, PropertyReq req);
}
