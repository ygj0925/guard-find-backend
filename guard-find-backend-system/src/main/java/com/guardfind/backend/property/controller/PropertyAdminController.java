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

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import com.guardfind.backend.property.model.req.*;
import com.guardfind.backend.property.model.resp.PropertyBookingResp;
import com.guardfind.backend.property.service.impl.PropertyInteractionServiceImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import com.guardfind.backend.property.model.resp.PropertyManagerResp;
import com.guardfind.backend.property.model.resp.PropertyResp;
import com.guardfind.backend.property.service.PropertyService;

/**
 * 房源管理 API(后台)
 *
 * <p>
 * 权限码与 {@code guardfind_business_data.sql} 中登记的菜单权限逐字一致。
 * </p>
 */
@Tag(name = "房源管理 API(后台)")
@Log(module = "房源管理")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/property")
public class PropertyAdminController {

    private final PropertyService propertyService;
    private final PropertyInteractionServiceImpl interactions;

    @Operation(summary = "房源分页", description = "全量房源(含草稿/下架),支持状态/城市/租售筛选")
    @SaCheckPermission("system:property:list")
    @GetMapping("/page")
    public PageResp<PropertyResp> page(@RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String city,
                                       @RequestParam(required = false) String priceType,
                                       PageQuery pageQuery) {
        return propertyService.pageForAdmin(status, city, priceType, pageQuery);
    }

    @Operation(summary = "上下架", description = "后台强制设置状态;1 草稿 / 2 上架 / 3 下架")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @SaCheckPermission("system:property:status")
    @PostMapping("/{id}/status")
    public void updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        propertyService.updateStatusForAdmin(id, status);
    }

    @Operation(summary = "删除房源", description = "后台强制删除")
    @Parameter(name = "id", description = "房源 ID", required = true)
    @SaCheckPermission("system:property:delete")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        propertyService.deleteForAdmin(id);
    }

    @Operation(summary = "管家用户分页", description = "已标记为房源管家的用户")
    @SaCheckPermission("system:property:manager:list")
    @GetMapping("/manager")
    public PageResp<PropertyManagerResp> managers(PageQuery pageQuery) {
        return propertyService.listManagers(pageQuery);
    }

    @Operation(summary = "设置/取消管家", description = "将用户标记为房源管家(可在 App 发布房源并展示认证标识)")
    @Parameter(name = "userId", description = "用户 ID", required = true)
    @SaCheckPermission("system:property:manager:set")
    @PostMapping("/manager/{userId}")
    public void setManager(@PathVariable Long userId, @RequestParam boolean isManager) {
        propertyService.setManager(userId, isManager);
    }

    @GetMapping("/{id}")
    @SaCheckPermission("system:property:list")
    public PropertyResp detail(@PathVariable Long id) {
        return propertyService.detailForAdmin(id);
    }

    @PostMapping
    @SaCheckPermission("system:property:create")
    public Long create(@Valid @RequestBody PropertyAdminCreateReq req) {
        if (req.getStatus() != 1)
            cn.dev33.satoken.stp.StpUtil.checkPermission("system:property:status");
        return propertyService.createForAdmin(req.getOwnerId(), req);
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:property:update")
    public void update(@PathVariable Long id, @Valid @RequestBody PropertyReq req) {
        if (!java.util.Objects.equals(propertyService.detailForAdmin(id).getStatus(), req.getStatus()))
            cn.dev33.satoken.stp.StpUtil.checkPermission("system:property:status");
        propertyService.updateForAdmin(id, req);
    }

    @GetMapping("/bookings")
    @SaCheckPermission("system:property:booking:list")
    public PageResp<PropertyBookingResp> bookings(@RequestParam(required = false) Integer status,
                                                  @RequestParam(required = false) Long propertyId,
                                                  PageQuery query) {
        return interactions.bookingsForAdmin(status, propertyId, query);
    }

    @PostMapping("/booking/{id}/status")
    @SaCheckPermission("system:property:booking:update")
    public void bookingStatus(@PathVariable Long id, @RequestParam Integer status) {
        interactions.updateBookingStatusForAdmin(id, status);
    }
}
