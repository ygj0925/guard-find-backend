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

package com.guardfind.backend.property.operations;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import cn.dev33.satoken.stp.StpUtil;
import top.continew.starter.log.annotation.Log;

@RestController
@RequiredArgsConstructor
@Log(module = "房源运营")
public class PropertyOperationsController {
    private final PropertyOperationsService operations;
    private final PropertyBookingOperations bookings;
    private final PropertyNotificationService notifications;
    private final OperationsStore db;

    @GetMapping("/miniapp/property/operations/settings")
    public Map<String, Object> publicSettings() {
        return operations.publicSettings();
    }

    @PostMapping("/miniapp/property/operations/policy")
    public void accept(@RequestBody Map<String, String> body) {
        operations.acceptPolicy(MiniappUserContextHolder.getUserIdRequired(), body.get("version"));
    }

    @GetMapping("/miniapp/property/{id}/operations")
    public Map<String, Object> property(@PathVariable long id) {
        return operations.property(id, MiniappUserContextHolder.getUserId(), false);
    }

    @PostMapping("/miniapp/property/{id}/consult")
    public void consult(@PathVariable long id, @RequestParam(defaultValue = "true") boolean support) {
        operations.consult(MiniappUserContextHolder.getUserIdRequired(), id, support);
    }

    @GetMapping("/miniapp/property/operations/bookings")
    public Map<String, Object> mine(@RequestParam(defaultValue = "guest") String role,
                                    @RequestParam(required = false) Integer status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return bookings.mine(MiniappUserContextHolder.getUserIdRequired(), role, status, page, size);
    }

    @GetMapping("/miniapp/property/operations/booking/{id}")
    public Map<String, Object> booking(@PathVariable long id) {
        return bookings.detail(id, MiniappUserContextHolder.getUserIdRequired(), false);
    }

    @PostMapping("/miniapp/property/operations/booking/{id}/action")
    public void action(@PathVariable long id, @RequestBody PropertyBookingOperations.Action input) {
        bookings.action(id, MiniappUserContextHolder.getUserIdRequired(), false, input);
    }

    @PostMapping("/miniapp/property/operations/booking/{id}/subscribe")
    public void subscribe(@PathVariable long id, @RequestBody Map<String, String> body) {
        notifications.subscribe(MiniappUserContextHolder.getUserIdRequired(), id, body.get("templateId"));
    }

    @GetMapping("/miniapp/property/operations/support")
    public Map<String, Object> support() {
        return operations.support(MiniappUserContextHolder.getUserIdRequired(), false);
    }

    @GetMapping("/system/property/operations/settings")
    @SaCheckPermission("system:property:settings")
    public OperationsSettings settings() {
        operations.platform();
        return operations.settings();
    }

    @PutMapping("/system/property/operations/settings")
    @SaCheckPermission("system:property:settings")
    public void settings(@Valid @RequestBody OperationsSettings input) {
        operations.saveSettings(input);
    }

    @GetMapping("/system/property/operations/property/{id}")
    @SaCheckPermission("system:property:list")
    public Map<String, Object> adminProperty(@PathVariable long id) {
        operations.platform();
        return operations.property(id, null, true);
    }

    @PutMapping("/system/property/operations/property/{id}")
    @SaCheckPermission("system:property:update")
    public void saveProperty(@PathVariable long id, @RequestBody PropertyOperationsService.PropertyInput input) {
        operations.saveProperty(id, input);
    }

    @GetMapping("/system/property/operations/customers")
    @SaCheckPermission("system:property:customer:list")
    public Map<String, Object> customers(@RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "false") boolean managers,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        operations.platform();
        return operations.users(keyword, managers, page, size);
    }

    @GetMapping("/system/property/operations/customer-options")
    @SaCheckPermission(value = {"system:property:create", "system:property:update",
        "system:property:manager:set"}, mode = SaMode.OR)
    public Map<String, Object> options(@RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "false") boolean managers) {
        operations.platform();
        return operations.users(keyword, managers, 1, 50);
    }

    @PostMapping("/system/property/operations/customer/{id}/status")
    @SaCheckPermission("system:property:customer:update")
    public void status(@PathVariable long id, @RequestParam int status) {
        operations.userStatus(id, status);
    }

    @GetMapping("/system/property/operations/bookings")
    @SaCheckPermission("system:property:booking:list")
    public Map<String, Object> list(@RequestParam(required = false) Integer status,
                                    @RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(defaultValue = "") String dateFrom,
                                    @RequestParam(defaultValue = "") String dateTo,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return bookings.page(keyword, status, dateFrom, dateTo, page, size);
    }

    @GetMapping("/system/property/operations/booking/{id}")
    @SaCheckPermission("system:property:booking:list")
    public Map<String, Object> detail(@PathVariable long id) {
        operations.platform();
        return bookings.detail(id, null, true);
    }

    @PostMapping("/system/property/operations/booking/{id}/action")
    @SaCheckPermission("system:property:booking:update")
    public void adminAction(@PathVariable long id, @RequestBody PropertyBookingOperations.Action input) {
        bookings.action(id, StpUtil.getLoginIdAsLong(), true, input);
    }

    @GetMapping("/system/property/operations/stats")
    @SaCheckPermission("system:property:metrics")
    public Map<String, Object> stats(@RequestParam(defaultValue = "30") int days) {
        return operations.stats(days);
    }

    @GetMapping("/system/property/operations/notifications")
    @SaCheckPermission("system:property:settings")
    public Map<String, Object> notifications(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return notifications.records(page, size);
    }

    @GetMapping("/system/im/workflow/{id}")
    @SaCheckPermission("system:im:list")
    public Map<String, Object> workflow(@PathVariable long id) {
        operations.platform();
        return operations.support(id, true);
    }

    @PostMapping("/system/im/workflow/{id}")
    @SaCheckPermission("system:im:workflow")
    public void workflow(@PathVariable long id, @RequestBody PropertyOperationsService.SupportInput input) {
        operations.supportAction(id, StpUtil.getLoginIdAsLong(), input);
    }

    @GetMapping("/system/im/workflow")
    @SaCheckPermission("system:im:list")
    public Map<String, Object> tickets(@RequestParam(defaultValue = "") String state,
                                       @RequestParam(required = false) Long owner,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        operations.platform();
        return operations.tickets(state, owner, page, size);
    }

    @GetMapping("/system/im/workflow-admins/options")
    @SaCheckPermission("system:im:workflow")
    public List<Map<String, Object>> admins() {
        operations.platform();
        return operations.supportAdmins();
    }
}
