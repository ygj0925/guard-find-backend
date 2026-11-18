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

package com.guardfind.backend.property.publishing;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/system/property/publishing")
@RequiredArgsConstructor
public class PropertyPublishingController {
    private final PropertyPublishingService service;

    @GetMapping("/capabilities")
    @SaCheckPermission("system:property:publishing:list")
    public Map<String, Object> capabilities() {
        return service.capabilities();
    }

    @GetMapping("/accounts")
    @SaCheckPermission(value = {"system:property:publishing:list",
        "system:property:publishing:accounts"}, mode = SaMode.OR)
    public List<Map<String, Object>> accounts() {
        return service.accounts();
    }

    @PostMapping("/accounts")
    @SaCheckPermission("system:property:publishing:accounts")
    public String account(@RequestBody PropertyPublishingService.Account input) {
        return service.saveAccount(null, input);
    }

    @PutMapping("/accounts/{id}")
    @SaCheckPermission("system:property:publishing:accounts")
    public void account(@PathVariable long id, @RequestBody PropertyPublishingService.Account input) {
        service.saveAccount(id, input);
    }

    @GetMapping("/settings")
    @SaCheckPermission("system:property:publishing:list")
    public Map<String, Object> settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    @SaCheckPermission("system:property:publishing:accounts")
    public void settings(@RequestBody PropertyPublishingService.Settings input) {
        service.settings(input);
    }

    @GetMapping("/properties")
    @SaCheckPermission("system:property:publishing:edit")
    public Map<String, Object> properties(@RequestParam(defaultValue = "") String keyword) {
        return service.properties(keyword);
    }

    @GetMapping("/preview")
    @SaCheckPermission("system:property:publishing:edit")
    public Map<String, Object> preview(@RequestParam long propertyId) {
        return service.preview(propertyId);
    }

    @GetMapping("/jobs")
    @SaCheckPermission("system:property:publishing:list")
    public Map<String, Object> jobs(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long propertyId) {
        return service.jobs(page, size, status, propertyId);
    }

    @GetMapping("/jobs/{id}")
    @SaCheckPermission("system:property:publishing:list")
    public Map<String, Object> job(@PathVariable long id) {
        return service.job(id);
    }

    @PostMapping("/jobs")
    @SaCheckPermission("system:property:publishing:edit")
    public Map<String, Object> create(@RequestBody PropertyPublishingService.Create input) {
        return service.create(input, StpUtil.getLoginIdAsLong());
    }

    @PutMapping("/jobs/{id}")
    @SaCheckPermission("system:property:publishing:edit")
    public void edit(@PathVariable long id, @RequestBody PropertyPublishingService.Edit input) {
        service.edit(id, input, StpUtil.getLoginIdAsLong());
    }

    @PostMapping("/jobs/{id}/action")
    @SaCheckPermission("system:property:publishing:publish")
    public void action(@PathVariable long id, @RequestBody PropertyPublishingService.Action input) {
        service.action(id, input, StpUtil.getLoginIdAsLong());
    }

    @GetMapping("/jobs/{id}/package")
    @SaCheckPermission("system:property:publishing:list")
    public Map<String, Object> export(@PathVariable long id) {
        return service.exportPackage(id);
    }
}
