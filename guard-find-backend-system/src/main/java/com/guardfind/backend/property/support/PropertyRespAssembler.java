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

package com.guardfind.backend.property.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.guardfind.backend.property.model.entity.PropertyDO;
import com.guardfind.backend.property.model.resp.PropertyResp;

import java.util.List;

/**
 * 房源实体 → 响应装配(C 端与管理端共用,纯静态无依赖)
 */
public final class PropertyRespAssembler {

    private PropertyRespAssembler() {
    }

    public static PropertyResp toResp(PropertyDO property) {
        PropertyResp resp = new PropertyResp();
        resp.setId(property.getId());
        resp.setUserId(property.getUserId());
        resp.setTitle(property.getTitle());
        resp.setDescription(property.getDescription());
        resp.setPropertyType(property.getPropertyType());
        resp.setLayout(property.getLayout());
        resp.setAreaSqm(property.getAreaSqm());
        resp.setFloorInfo(property.getFloorInfo());
        resp.setOrientation(property.getOrientation());
        resp.setDecoration(property.getDecoration());
        resp.setPrice(property.getPrice());
        resp.setPriceType(property.getPriceType());
        resp.setCommunity(property.getCommunity());
        resp.setAvailableFrom(property.getAvailableFrom());
        resp.setRentalMode(property.getRentalMode());
        resp.setAddress(property.getAddress());
        resp.setDistrict(property.getDistrict());
        resp.setCity(property.getCity());
        resp.setFacilities(StrUtil.isBlank(property.getFacilities())
            ? List.of()
            : JSONUtil.toList(property.getFacilities(), String.class));
        resp.setContactName(property.getContactName());
        resp.setContactPhone(property.getContactPhone());
        resp.setStatus(property.getStatus());
        resp.setViewCount(property.getViewCount());
        resp.setImages(StrUtil.isBlank(property.getImages())
            ? List.of()
            : JSONUtil.toList(property.getImages(), String.class));
        resp.setCreateTime(property.getCreateTime());
        return resp;
    }
}
