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
