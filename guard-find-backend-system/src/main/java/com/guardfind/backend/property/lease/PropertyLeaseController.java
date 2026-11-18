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
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.property.operations.PropertyOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PropertyLeaseController {
    private final PropertyLeaseService leases;
    private final PropertyOperationsService operations;
    private final PropertySigningProfileService profiles;
    private final PropertyLeasePdfService pdf;
    private final PropertyIdentityVerificationService identity;

    @GetMapping("/miniapp/identity-verification/status")
    public Map<String, Object> identityStatus() {
        return identity.status(MiniappUserContextHolder.getUserIdRequired());
    }

    @PostMapping("/miniapp/identity-verification/consent")
    public Map<String, Object> identityConsent(@RequestBody PropertyIdentityVerificationService.ConsentInput input) {
        return identity.consent(MiniappUserContextHolder.getUserIdRequired(), input);
    }

    @PostMapping("/miniapp/identity-verification/session")
    public Map<String, Object> identitySession(@RequestBody PropertyIdentityVerificationService.SessionInput input) {
        return identity.start(MiniappUserContextHolder.getUserIdRequired(), input);
    }

    @GetMapping("/miniapp/identity-verification/session/{id}")
    public Map<String, Object> identitySession(@PathVariable long id) {
        return identity.session(MiniappUserContextHolder.getUserIdRequired(), id);
    }

    @GetMapping("/system/property/identity-verification/config")
    @SaCheckPermission("system:property:identity:list")
    public PropertyIdentityVerificationService.Config identityConfig() {
        return identity.config();
    }

    @PutMapping("/system/property/identity-verification/config")
    @SaCheckPermission("system:property:identity:update")
    public void identityConfig(@RequestBody PropertyIdentityVerificationService.Config input) {
        identity.saveConfig(input);
    }

    @GetMapping("/system/identity-verification/settings")
    @SaCheckPermission("system:property:identity:list")
    public Map<String, Object> identitySettings() {
        return identity.settingsView();
    }

    @PutMapping("/system/identity-verification/settings")
    @SaCheckPermission("system:property:identity:update")
    public void identitySettings(@RequestBody PropertyIdentityVerificationService.SettingsInput input) {
        identity.saveSettings(input);
    }

    @GetMapping("/system/identity-verification/status")
    @SaCheckPermission("system:property:identity:list")
    public Map<String, Object> identityStatus(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return identity.page(status, page, size);
    }

    @PostMapping("/system/identity-verification/{id}/manual-review")
    @SaCheckPermission("system:property:identity:update")
    public void identityManualReview(@PathVariable long id, @RequestBody Map<String, Object> input) {
        String decision = input.get("decision") == null ? null : input.get("decision").toString();
        String reason = input.get("reason") == null ? null : input.get("reason").toString();
        Long version = input.get("version") == null ? null : Long.valueOf(input.get("version").toString());
        identity.adminAction(id, StpUtil
            .getLoginIdAsLong(), new PropertyIdentityVerificationService.AdminAction(decision, reason, version));
    }

    @PostMapping("/system/property/identity-verification/{id}/action")
    @SaCheckPermission("system:property:identity:update")
    public Map<String, Object> identityAction(@PathVariable long id,
                                              @RequestBody PropertyIdentityVerificationService.AdminAction input) {
        return identity.adminAction(id, StpUtil.getLoginIdAsLong(), input);
    }

    @GetMapping("/miniapp/property/signing-profile")
    public Map<String, Object> signingProfile() {
        return profiles.profile(MiniappUserContextHolder.getUserIdRequired());
    }

    @PostMapping("/miniapp/property/signing-profile/seal")
    public Map<String, Object> uploadSeal(@RequestParam org.springframework.web.multipart.MultipartFile file) {
        return profiles.uploadSeal(MiniappUserContextHolder.getUserIdRequired(), file);
    }

    @DeleteMapping("/miniapp/property/signing-profile/{kind}")
    public void deleteSigningAsset(@PathVariable String kind) {
        profiles.remove(MiniappUserContextHolder.getUserIdRequired(), kind);
    }

    @GetMapping("/miniapp/property/leases/{id}/pdf")
    public Map<String, Object> download(@PathVariable long id) {
        return pdf.download(id, MiniappUserContextHolder.getUserIdRequired(), false);
    }

    @GetMapping("/system/property/leases/{id}/pdf")
    @SaCheckPermission("system:property:lease:list")
    public Map<String, Object> adminDownload(@PathVariable long id) {
        return pdf.download(id, null, true);
    }

    @GetMapping("/miniapp/property/leases")
    public Map<String, Object> mine(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String status) {
        return leases.page(MiniappUserContextHolder.getUserIdRequired(), false, status, null, null, page, size);
    }

    @GetMapping("/miniapp/property/leases/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return leases.detail(id, MiniappUserContextHolder.getUserIdRequired(), false);
    }

    @PostMapping("/miniapp/property/leases/{id}/action")
    public Map<String, Object> action(@PathVariable long id, @RequestBody PropertyLeaseService.Action input) {
        return leases.action(id, MiniappUserContextHolder.getUserIdRequired(), false, input);
    }

    @PostMapping("/miniapp/property/leases/{id}/reviews")
    public void review(@PathVariable long id, @RequestBody PropertyLeaseService.ReviewInput input) {
        leases.review(id, MiniappUserContextHolder.getUserIdRequired(), false, input);
    }

    @PostMapping("/system/property/leases/{id}/reviews")
    @SaCheckPermission("system:property:lease:update")
    public void adminReview(@PathVariable long id, @RequestBody PropertyLeaseService.ReviewInput input) {
        leases.review(id, StpUtil.getLoginIdAsLong(), true, input);
    }

    @GetMapping("/system/property/leases")
    @SaCheckPermission("system:property:lease:list")
    public Map<String, Object> page(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer expiringDays,
                                    @RequestParam(required = false) String renewalStatus,
                                    @RequestParam(required = false) String moveOutStatus) {
        return leases.page(null, true, status, keyword, expiringDays, renewalStatus, moveOutStatus, page, size);
    }

    @GetMapping("/system/property/leases/{id}")
    @SaCheckPermission("system:property:lease:list")
    public Map<String, Object> adminDetail(@PathVariable long id) {
        return leases.detail(id, null, true);
    }

    @PostMapping("/system/property/leases")
    @SaCheckPermission("system:property:lease:create")
    public String create(@RequestBody PropertyLeaseService.Draft input) {
        return leases.create(input, StpUtil.getLoginIdAsLong());
    }

    @PutMapping("/system/property/leases/{id}")
    @SaCheckPermission("system:property:lease:update")
    public void update(@PathVariable long id, @RequestBody PropertyLeaseService.Draft input) {
        leases.update(id, input, StpUtil.getLoginIdAsLong());
    }

    @PostMapping("/system/property/leases/{id}/action")
    public Map<String, Object> adminAction(@PathVariable long id, @RequestBody PropertyLeaseService.Action input) {
        operations.platform();
        String permission = switch (input.action() == null ? "" : input.action()) {
            case "PUBLISH", "RENEW_APPROVE", "RECALL", "REMIND_SIGN" -> "system:property:lease:publish";
            case "VOID", "TERMINATE" -> "system:property:lease:terminate";
            default -> "system:property:lease:update";
        };
        StpUtil.checkPermission(permission);
        return leases.action(id, StpUtil.getLoginIdAsLong(), true, input);
    }

    @GetMapping("/system/property/leases/reminders")
    @SaCheckPermission("system:property:lease:list")
    public Map<String, Object> reminders(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        return leases.reminders(page, size);
    }

    @GetMapping("/system/property/leases/settings")
    @SaCheckPermission("system:property:lease:list")
    public PropertyLeaseService.Settings settings() {
        operations.platform();
        return leases.settings();
    }

    @PutMapping("/system/property/leases/settings")
    @SaCheckPermission("system:property:lease:update")
    public void settings(@RequestBody PropertyLeaseService.Settings input) {
        leases.saveSettings(input);
    }

    @GetMapping("/system/property/leases/property-options")
    @SaCheckPermission(value = {"system:property:lease:create", "system:property:lease:update"}, mode = SaMode.OR)
    public Map<String, Object> properties(@RequestParam(defaultValue = "") String keyword,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return leases.propertyOptions(keyword, page, size);
    }

    @GetMapping("/system/property/leases/customer-options")
    @SaCheckPermission(value = {"system:property:lease:create", "system:property:lease:update"}, mode = SaMode.OR)
    public Map<String, Object> customers(@RequestParam(defaultValue = "") String keyword,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return leases.customerOptions(keyword, page, size);
    }
}
