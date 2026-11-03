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

package com.guardfind.backend.property.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.enums.LimitType;

import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.property.model.req.PropertyReq;
import com.guardfind.backend.property.model.resp.PropertyResp;
import com.guardfind.backend.property.service.PropertyService;

import java.util.concurrent.TimeUnit;

/**
 * 房产房源 API(C 端)。浏览已上架=所有用户;管理=仅房东本人。
 */
@Tag(name = "房产房源 API")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/miniapp/property")
public class PropertyController {

    private final PropertyService propertyService;

    @Operation(summary = "创建房源")
    @RateLimiter(name = "MINIAPP:PROPERTY:CREATE", rate = 10, interval = 1, unit = TimeUnit.HOURS, type = LimitType.IP)
    @PostMapping
    public Long create(@RequestBody @Valid PropertyReq req) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return propertyService.create(userId, req);
    }

    @Operation(summary = "房源广场", description = "已上架房源,支持城市/类型/租售筛选")
    @Parameter(name = "city", description = "城市")
    @Parameter(name = "propertyType", description = "house/apartment/villa/shop")
    @Parameter(name = "priceType", description = "rent/sale")
    @GetMapping("/list")
    public PageResp<PropertyResp> list(@RequestParam(required = false) String city,
                                       @RequestParam(required = false) String propertyType,
                                       @RequestParam(required = false) String priceType,
                                       @RequestParam(required = false) String rentalMode,
                                       PageQuery pageQuery) {
        return propertyService.page(city, propertyType, priceType, rentalMode, pageQuery);
    }

    @Operation(summary = "我的房源", description = "含草稿/已下架")
    @GetMapping("/mine")
    public PageResp<PropertyResp> mine(PageQuery pageQuery) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        return propertyService.mine(userId, pageQuery);
    }

    @Operation(summary = "房源详情", description = "浏览计数自增")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @GetMapping("/{id}")
    public PropertyResp detail(@PathVariable Long id) {
        return propertyService.detail(id);
    }

    @Operation(summary = "编辑房源", description = "仅房东本人")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody @Valid PropertyReq req) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        propertyService.update(id, userId, req);
    }

    @Operation(summary = "删除房源", description = "仅房东本人")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        propertyService.delete(id, userId);
    }

    @Operation(summary = "上架/下架", description = "仅房东本人;1 草稿 / 2 上架 / 3 下架")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @PostMapping("/{id}/status")
    public void updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        Long userId = MiniappUserContextHolder.getUserIdRequired();
        propertyService.updateStatus(id, userId, status);
    }
}
