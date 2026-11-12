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

package com.guardfind.backend.property;

import cn.dev33.satoken.stp.StpUtil;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.property.controller.PropertyImageController;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.exception.MiniappBizException;
import com.guardfind.backend.system.service.FileService;
import org.dromara.x.file.storage.core.FileInfo;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import top.continew.starter.core.exception.BusinessException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PropertyImageTest {
    FileService storage;
    PropertyImageController controller;

    @BeforeEach
    void setup() {
        storage = mock(FileService.class);
        controller = new PropertyImageController(storage);
        MiniappUserContextHolder.setUserId(1L);
    }

    @AfterEach
    void cleanup() {
        MiniappUserContextHolder.clear();
    }

    @Test
    void rejectsFakeImageWithoutWritingStorage() {
        var file = new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes());
        assertThrows(BusinessException.class, () -> controller.miniapp(file));
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsEmptyOrOversizedUploads() {
        assertThrows(BusinessException.class, () -> controller
            .miniapp(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])));
        assertThrows(BusinessException.class, () -> controller
            .miniapp(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1])));
        verifyNoInteractions(storage);
    }

    @Test
    void writesValidImageWithCurrentMiniappCreatorAndReturnsPublicUrl() throws Exception {
        MiniappUserContextHolder.setUserId(42L);
        var file = validImage();
        var info = new FileInfo();
        info.setUrl("https://example.com/a.png");
        when(storage.upload(eq(file), anyString(), isNull(), eq(42L))).thenReturn(info);
        try (var backend = mockStatic(UserContextHolder.class)) {
            assertEquals("https://example.com/a.png", controller.miniapp(file).get("url"));
            verify(storage).upload(eq(file), startsWith("property/miniapp/42/"), isNull(), eq(42L));
            verifyNoMoreInteractions(storage);
            backend.verifyNoInteractions();
        }
    }

    @Test
    void rejectsUnauthenticatedMiniappWithoutWritingStorage() throws Exception {
        MiniappUserContextHolder.clear();
        var file = validImage();
        assertThrows(MiniappBizException.class, () -> controller.miniapp(file));
        verifyNoInteractions(storage);
    }

    @Test
    void adminUsesBackendLoginIdInsteadOfMiniappIdentity() throws Exception {
        var file = validImage();
        var info = new FileInfo();
        info.setUrl("https://example.com/a.png");
        when(storage.upload(eq(file), anyString(), isNull(), eq(99L))).thenReturn(info);
        try (var backend = mockStatic(StpUtil.class)) {
            backend.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            assertEquals("https://example.com/a.png", controller.admin(file).get("url"));
            verify(storage).upload(eq(file), startsWith("property/admin/99/"), isNull(), eq(99L));
            verifyNoMoreInteractions(storage);
            backend.verify(StpUtil::getLoginIdAsLong);
            backend.verifyNoMoreInteractions();
        }
    }

    private MockMultipartFile validImage() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "a.png", "image/png", bytes.toByteArray());
    }
}
