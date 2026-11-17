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
import com.guardfind.backend.property.model.resp.PropertyHomeResp;
import com.guardfind.backend.property.service.PropertyHomeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PropertyHomeController {
    private final PropertyHomeService homeService;

    @GetMapping("/miniapp/property/home")
    public PropertyHomeResp home() {
        return homeService.getPublic();
    }

    @GetMapping("/system/property/home")
    @SaCheckPermission("system:property:list")
    public PropertyHomeResp homeForAdmin() {
        return homeService.getForAdmin();
    }

    @PutMapping("/system/property/home")
    @SaCheckPermission("system:property:update")
    public void save(@Valid @RequestBody PropertyHomeResp home) {
        homeService.save(home);
    }
}
