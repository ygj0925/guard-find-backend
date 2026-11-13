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

import com.guardfind.backend.property.model.req.PropertyBookingReq;
import com.guardfind.backend.property.model.resp.PropertyBookingResp;
import com.guardfind.backend.property.model.resp.PropertyResp;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

/**
 * 房源互动业务接口:收藏、浏览历史、预约看房。
 */
public interface PropertyInteractionService {

    /**
     * 切换收藏状态,返回切换后是否已收藏。
     */
    boolean toggleFavorite(Long userId, Long propertyId);

    /**
     * 当前用户是否已收藏该房源。
     */
    boolean isFavorited(Long userId, Long propertyId);

    java.util.Set<Long> favoritedIds(Long userId, java.util.List<Long> propertyIds);

    /**
     * 我的收藏(收藏时间倒序)。
     */
    PageResp<PropertyResp> favorites(Long userId, PageQuery pageQuery);

    /**
     * 浏览历史(最近浏览时间倒序)。
     */
    PageResp<PropertyResp> history(Long userId, PageQuery pageQuery);

    /**
     * 记录浏览(每用户每房源仅一行,重复浏览刷新时间;失败静默不影响详情)。
     */
    void recordView(Long userId, Long propertyId);

    /**
     * 创建看房预约,返回预约 ID;同时站内信通知房东。
     */
    Long createBooking(Long userId, Long propertyId, PropertyBookingReq req);

    /**
     * 预约列表。role=guest:我预约的;role=owner:我收到的(房东视角)。
     */
    PageResp<PropertyBookingResp> bookings(Long userId, String role, PageQuery pageQuery);

    /**
     * 预约状态流转:guest 可取消(4);owner 可确认(2)/拒绝(3)。
     */
    void updateBookingStatus(Long userId, Long bookingId, Integer status);

    void cleanupForDelete(Long propertyId);
}
