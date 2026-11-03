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
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.system.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.enums.LimitType;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Locale;

/** Both clients use the configured backend file storage, with server-selected paths. */
@RestController
@RequiredArgsConstructor
public class PropertyImageController {
    private final FileService files;

    @PostMapping("/miniapp/property/images")
    @RateLimiter(name = "MINIAPP:PROPERTY:IMAGE", rate = 30, interval = 60, type = LimitType.IP)
    public Map<String, String> miniapp(@RequestPart("file") MultipartFile file) throws IOException {
        return upload(file, "miniapp/" + MiniappUserContextHolder.getUserIdRequired());
    }

    @PostMapping("/system/property/images")
    @SaCheckPermission(value = {"system:property:create", "system:property:update"}, mode = SaMode.OR)
    public Map<String, String> admin(@RequestPart("file") MultipartFile file) throws IOException {
        return upload(file, "admin/" + StpUtil.getLoginIdAsLong());
    }

    private Map<String, String> upload(MultipartFile file, String actor) throws IOException {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
            throw new BusinessException("请选择不超过5MB的图片");
        String name = file.getOriginalFilename();
        if (name == null || name.contains("/") || name.contains("\\") || !name.toLowerCase(Locale.ROOT)
            .matches(".+[.](jpg|jpeg|png)"))
            throw new BusinessException("仅支持 JPG、JPEG、PNG 图片");
        try (var input = ImageIO.createImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
                throw new BusinessException("图片内容无效");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG"))
                    throw new BusinessException("图片格式无效");
                if ((long)reader.getWidth(0) * reader.getHeight(0) > 30000000L)
                    throw new BusinessException("图片尺寸过大，请压缩后上传");
            } finally {
                reader.dispose();
            }
        }
        var info = files.upload(file, "property/" + actor + "/" + LocalDate.now() + "/");
        if (info == null || info.getUrl() == null || !info.getUrl().matches("^https?://[^\\s]+$"))
            throw new BusinessException("请先在后台存储管理中配置图片的公网访问地址");
        return Map.of("url", info.getUrl());
    }
}
