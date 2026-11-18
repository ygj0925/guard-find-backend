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

import com.guardfind.backend.property.model.query.PropertyMapQuery;
import com.guardfind.backend.property.operations.OperationsStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PropertyMapService {
    private final OperationsStore db;

    private String from(PropertyMapQuery query, List<Object> args) {
        if (!query.isValid())
            throw new BusinessException("地图范围或价格区间无效");
        StringBuilder sql = new StringBuilder(" FROM gf_property p LEFT JOIN gf_property_operation o ON o.property_id=p.id WHERE p.status=2 AND p.deleted=0 AND (o.availability IS NULL OR o.availability='ACTIVE')");
        if (query.getCity() != null && !query.getCity().isBlank()) {
            sql.append(" AND p.city=?");
            args.add(query.getCity().trim());
        }
        if (query.getCommunity() != null && !query.getCommunity().isBlank()) {
            sql.append(" AND p.community=?");
            args.add(query.getCommunity().trim());
        }
        if (query.getDistrict() != null && !query.getDistrict().isBlank()) {
            sql.append(" AND p.district=?");
            args.add(query.getDistrict().trim());
        }
        if (query.getPriceUnit() != null) {
            sql.append(" AND p.price_unit=?");
            args.add(query.getPriceUnit());
        }
        if (query.getPriceType() != null) {
            sql.append(" AND p.price_type=?");
            args.add(query.getPriceType());
        }
        if (query.getMinPrice() != null) {
            sql.append(" AND p.price>=?");
            args.add(query.getMinPrice());
        }
        if (query.getMaxPrice() != null) {
            sql.append(" AND p.price<=?");
            args.add(query.getMaxPrice());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            sql.append(" AND (p.title LIKE ? ESCAPE '!' OR p.community LIKE ? ESCAPE '!' OR p.location_label LIKE ? ESCAPE '!')");
            for (int i = 0; i < 3; i++)
                args.add(OperationsStore.like(query.getKeyword()));
        }
        return sql.toString();
    }

    public Map<String, Object> markers(PropertyMapQuery query) {
        var args = new ArrayList<Object>();
        var sql = from(query, args) + " AND p.public_latitude BETWEEN -90 AND 90 AND p.public_longitude BETWEEN -180 AND 180";
        if (query.getSouth() != null) {
            sql += " AND p.public_latitude BETWEEN ? AND ? AND p.public_longitude BETWEEN ? AND ?";
            args.addAll(List.of(query.getSouth(), query.getNorth(), query.getWest(), query.getEast()));
        }
        // Do not include room address, owner/contact details or private meeting instructions in map payloads.
        var rows = db
            .rows("SELECT p.id,p.title,p.city,p.district,p.community,p.price,p.price_type,p.price_unit,p.public_latitude latitude,p.public_longitude longitude,p.location_label" + sql + " ORDER BY p.id DESC LIMIT 201", args
                .toArray());
        return Map.of("list", rows.subList(0, Math.min(200, rows.size())), "truncated", rows
            .size() > 200, "coordinateSystem", "GCJ-02");
    }

    public List<Map<String, Object>> communities(PropertyMapQuery query) {
        var args = new ArrayList<Object>();
        var sql = from(query, args) + " AND p.community IS NOT NULL AND p.community<>''";
        return db
            .rows("SELECT p.city,p.district,p.community,COUNT(*) property_count" + sql + " GROUP BY p.city,p.district,p.community ORDER BY property_count DESC,p.community LIMIT 20", args
                .toArray());
    }
}
