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

package com.guardfind.backend.system;

import cn.hutool.core.util.ClassUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.guardfind.backend.common.config.mybatis.MyBatisPlusMetaObjectHandler;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.common.enums.DisEnableStatusEnum;
import com.guardfind.backend.system.config.file.FileRecorderImpl;
import com.guardfind.backend.system.enums.FileTypeEnum;
import com.guardfind.backend.system.mapper.FileMapper;
import com.guardfind.backend.system.mapper.StorageMapper;
import com.guardfind.backend.system.model.entity.FileDO;
import com.guardfind.backend.system.model.entity.StorageDO;
import com.guardfind.backend.system.service.StorageService;
import com.guardfind.backend.system.service.impl.FileServiceImpl;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.dromara.x.file.storage.core.FileInfo;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.upload.UploadPretreatment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.starter.cache.redisson.util.RedisLockUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileUploadAuditTest {
    private static final String PARENT_PATH = "property/miniapp/42/";

    private FileMapper mapper;
    private LambdaQueryChainWrapper<FileDO> query;
    private FileServiceImpl service;
    private FileRecorderImpl recorder;
    private UploadPretreatment pretreatment;
    private RedisLockUtils lock;
    private StorageDO storage;
    private FileInfo uploaded;
    private final cn.hutool.core.lang.Dict attrs = cn.hutool.core.lang.Dict.create();
    private final List<FileDO> inserted = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        mapper = mock(FileMapper.class);
        query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.ne(any(), any())).thenReturn(query);
        when(mapper.lambdaQuery()).thenReturn(query);
        var handler = new MyBatisPlusMetaObjectHandler();
        doAnswer(invocation -> {
            FileDO file = invocation.getArgument(0);
            // Exercise the unchanged production fill logic, including its null-only fallback.
            handler.insertFill(SystemMetaObject.forObject(file));
            file.setId((long)inserted.size() + 1);
            inserted.add(file);
            return 1;
        }).when(mapper).insert(any(FileDO.class));

        storage = new StorageDO();
        storage.setId(7L);
        storage.setCode("local");
        storage.setStatus(DisEnableStatusEnum.ENABLE);
        var storages = mock(StorageService.class);
        when(storages.getByCode(null)).thenReturn(storage);
        recorder = new FileRecorderImpl(mapper, mock(StorageMapper.class));

        uploaded = mock(FileInfo.class, RETURNS_DEEP_STUBS);
        when(uploaded.getAttr()).thenReturn(attrs);
        when(uploaded.getFilename()).thenReturn("a.png");
        when(uploaded.getOriginalFilename()).thenReturn("a.png");
        when(uploaded.getPath()).thenReturn(PARENT_PATH);
        when(uploaded.getUrl()).thenReturn("https://example.com/a.png");
        when(uploaded.getExt()).thenReturn("png");
        when(uploaded.getCreateTime()).thenReturn(new Date());
        when(uploaded.getMetadata()).thenReturn(Map.of());
        when(uploaded.getThMetadata()).thenReturn(Map.of());
        when(uploaded.getHashInfo().getSha256()).thenReturn("sha256");

        pretreatment = mock(UploadPretreatment.class, RETURNS_SELF);
        when(pretreatment.putAttr(anyString(), any())).thenAnswer(invocation -> {
            attrs.put(invocation.getArgument(0), invocation.getArgument(1));
            return pretreatment;
        });
        when(pretreatment.upload()).thenAnswer(invocation -> {
            assertTrue(recorder.save(uploaded));
            return uploaded;
        });
        var fileStorage = mock(FileStorageService.class);
        when(fileStorage.of(any(Object.class))).thenReturn(pretreatment);
        service = new FileServiceImpl(fileStorage);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "storageService", storages);
        lock = mock(RedisLockUtils.class);
        when(lock.isLocked()).thenReturn(true);
    }

    @Test
    void explicitCreatorReachesEveryDirectoryAndRecorderWithoutBackendIdentity() {
        try (var backend = mockStatic(UserContextHolder.class); var locks = mockStatic(RedisLockUtils.class)) {
            locks.when(() -> RedisLockUtils.tryLock(anyString())).thenReturn(lock);
            assertSame(uploaded, service.upload(image(), PARENT_PATH, null, 42L));
            assertUploadCreator(42L);
            // Only the existing meta handler reads the backend ID; upload never installs a context.
            backend.verify(UserContextHolder::getUserId, times(inserted.size()));
            backend.verifyNoMoreInteractions();
        }
    }

    @Test
    void explicitCreatorSurvivesMetaFillWithDifferentBackendIdentity() {
        try (var backend = mockStatic(UserContextHolder.class); var locks = mockStatic(RedisLockUtils.class)) {
            backend.when(UserContextHolder::getUserId).thenReturn(99L);
            locks.when(() -> RedisLockUtils.tryLock(anyString())).thenReturn(lock);
            service.upload(image(), PARENT_PATH, null, 42L);
            assertUploadCreator(42L);
            backend.verify(UserContextHolder::getUserId, times(inserted.size()));
            backend.verifyNoMoreInteractions();
        }
    }

    @Test
    void recorderWithoutCreatorStillUsesExistingMetaFill() {
        attrs.put(ClassUtil.getClassName(StorageDO.class, false), storage);
        try (var backend = mockStatic(UserContextHolder.class)) {
            backend.when(UserContextHolder::getUserId).thenReturn(99L);
            assertTrue(recorder.save(uploaded));
            assertEquals(1, inserted.size());
            assertEquals(99L, inserted.get(0).getCreateUser());
            assertFalse(attrs.containsKey("createUser"));
            backend.verify(UserContextHolder::getUserId);
            backend.verifyNoMoreInteractions();
        }
    }

    @Test
    void legacyMultipartUploadUsesBackendCreator() {
        try (var backend = mockStatic(UserContextHolder.class); var locks = mockStatic(RedisLockUtils.class)) {
            backend.when(UserContextHolder::getUserId).thenReturn(99L);
            locks.when(() -> RedisLockUtils.tryLock(anyString())).thenReturn(lock);
            service.upload(image(), PARENT_PATH, null);
            assertUploadCreator(99L);
            backend.verify(UserContextHolder::getUserId, times(inserted.size() + 1));
            backend.verifyNoMoreInteractions();
        }
    }

    @Test
    void fileUploadKeepsBackendCreatorSemantics() {
        try (var backend = mockStatic(UserContextHolder.class); var locks = mockStatic(RedisLockUtils.class)) {
            backend.when(UserContextHolder::getUserId).thenReturn(99L);
            locks.when(() -> RedisLockUtils.tryLock(anyString())).thenReturn(lock);
            service.upload(new File("a.png"), PARENT_PATH, null);
            assertUploadCreator(99L);
            backend.verify(UserContextHolder::getUserId, times(inserted.size() + 1));
            backend.verifyNoMoreInteractions();
        }
    }

    @Test
    void publicDirectoryCreationUsesBackendCreatorAndLeavesExistingDirectoryUntouched() {
        var existing = new FileDO();
        existing.setStorageId(storage.getId());
        existing.setCreateUser(8L);
        when(query.one()).thenReturn(existing, null, null);
        try (var backend = mockStatic(UserContextHolder.class); var locks = mockStatic(RedisLockUtils.class)) {
            backend.when(UserContextHolder::getUserId).thenReturn(99L);
            locks.when(() -> RedisLockUtils.tryLock(anyString())).thenReturn(lock);
            service.createParentDir(PARENT_PATH, storage);
            assertEquals(8L, existing.getCreateUser());
            assertEquals(List.of("/property/miniapp", "/property/miniapp/42"), inserted.stream()
                .map(FileDO::getPath)
                .toList());
            assertTrue(inserted.stream().allMatch(file -> file.getCreateUser().equals(99L)));
            verify(mapper, times(2)).insert(any(FileDO.class));
            backend.verify(UserContextHolder::getUserId, times(inserted.size() + 1));
            backend.verifyNoMoreInteractions();
        }
    }

    private void assertUploadCreator(Long createUser) {
        assertEquals(createUser, attrs.get("createUser"));
        verify(pretreatment).putAttr("createUser", createUser);
        assertEquals(4, inserted.size());
        assertEquals(List.of("/property", "/property/miniapp", "/property/miniapp/42"), inserted.stream()
            .filter(file -> file.getType() == FileTypeEnum.DIR)
            .map(FileDO::getPath)
            .toList());
        assertEquals(FileTypeEnum.IMAGE, inserted.get(3).getType());
        for (var file : inserted) {
            assertEquals(createUser, file.getCreateUser());
            assertEquals(storage.getId(), file.getStorageId());
            assertNotNull(file.getCreateTime());
            assertNull(file.getUpdateUser());
        }
        verify(uploaded).setId("4");
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("file", "a.png", "image/png", new byte[] {1});
    }
}
