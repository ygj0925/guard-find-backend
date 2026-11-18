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

package com.guardfind.backend.property.lease;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.io.IOException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class PropertyLeaseLifecycleController {
    private final PropertyLeaseLifecycleService service;
    private final PropertyLeaseEvidenceService evidence;

    @PostMapping("/system/property/leases/{id}/reopen")
    @SaCheckPermission({"system:property:lease:update", "system:property:status"})
    public void reopen(@PathVariable long id) {
        service.reopen(id, StpUtil.getLoginIdAsLong());
    }

    @PostMapping("/miniapp/property/leases/{id}/files")
    public Map<String, String> upload(@PathVariable long id,
                                      @RequestPart("file") MultipartFile file) throws IOException {
        return evidence.upload(id, MiniappUserContextHolder.getUserIdRequired(), false, file);
    }

    @GetMapping("/miniapp/property/leases/{id}/files/{fileId}")
    public Map<String, String> file(@PathVariable long id, @PathVariable long fileId) {
        return evidence.read(id, fileId, MiniappUserContextHolder.getUserIdRequired(), false);
    }

    @PostMapping("/system/property/leases/{id}/files")
    @SaCheckPermission("system:property:lease:update")
    public Map<String, String> adminUpload(@PathVariable long id,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        return evidence.upload(id, StpUtil.getLoginIdAsLong(), true, file);
    }

    @GetMapping("/system/property/leases/{id}/files/{fileId}")
    @SaCheckPermission("system:property:lease:list")
    public Map<String, String> adminFile(@PathVariable long id, @PathVariable long fileId) {
        return evidence.read(id, fileId, StpUtil.getLoginIdAsLong(), true);
    }

    @GetMapping("/miniapp/property/leases/{id}/lifecycle")
    public Map<String, Object> mine(@PathVariable long id) {
        return service.detail(id, MiniappUserContextHolder.getUserIdRequired(), false);
    }

    @PostMapping("/miniapp/property/leases/{id}/bills/{billId}/action")
    public void bill(@PathVariable long id,
                     @PathVariable long billId,
                     @RequestBody PropertyLeaseLifecycleService.BillAction input) {
        service.billAction(id, billId, MiniappUserContextHolder.getUserIdRequired(), false, input);
    }

    @PostMapping("/miniapp/property/leases/{id}/documents/{documentId}/action")
    public void document(@PathVariable long id,
                         @PathVariable long documentId,
                         @RequestBody PropertyLeaseLifecycleService.DocumentAction input) {
        service.documentAction(id, documentId, MiniappUserContextHolder.getUserIdRequired(), false, input);
    }

    @GetMapping("/system/property/leases/{id}/lifecycle")
    @SaCheckPermission("system:property:lease:list")
    public Map<String, Object> admin(@PathVariable long id) {
        return service.detail(id, StpUtil.getLoginIdAsLong(), true);
    }

    @PostMapping("/system/property/leases/{id}/bills")
    @SaCheckPermission("system:property:lease:update")
    public void createBill(@PathVariable long id, @RequestBody PropertyLeaseLifecycleService.BillInput input) {
        service.createBill(id, StpUtil.getLoginIdAsLong(), input);
    }

    @PostMapping("/system/property/leases/{id}/bills/generate")
    @SaCheckPermission("system:property:lease:update")
    public void generate(@PathVariable long id, @RequestBody PropertyLeaseLifecycleService.ScheduleInput input) {
        service.generate(id, StpUtil.getLoginIdAsLong(), input);
    }

    @PostMapping("/system/property/leases/{id}/bills/{billId}/action")
    @SaCheckPermission("system:property:lease:update")
    public void adminBill(@PathVariable long id,
                          @PathVariable long billId,
                          @RequestBody PropertyLeaseLifecycleService.BillAction input) {
        service.billAction(id, billId, StpUtil.getLoginIdAsLong(), true, input);
    }

    @PostMapping("/system/property/leases/{id}/documents")
    @SaCheckPermission("system:property:lease:update")
    public void createDocument(@PathVariable long id, @RequestBody PropertyLeaseLifecycleService.DocumentInput input) {
        service.createDocument(id, StpUtil.getLoginIdAsLong(), input);
    }

    @PostMapping("/system/property/leases/{id}/documents/{documentId}/action")
    @SaCheckPermission("system:property:lease:update")
    public void adminDocument(@PathVariable long id,
                              @PathVariable long documentId,
                              @RequestBody PropertyLeaseLifecycleService.DocumentAction input) {
        service.documentAction(id, documentId, StpUtil.getLoginIdAsLong(), true, input);
    }
}
