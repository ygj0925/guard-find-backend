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

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.guardfind.backend.system.mapper.OptionMapper;
import com.guardfind.backend.system.model.entity.OptionDO;
import com.guardfind.backend.system.model.query.OptionQuery;
import com.guardfind.backend.system.model.req.OptionReq;
import com.guardfind.backend.system.model.req.OptionValueResetReq;
import com.guardfind.backend.system.service.impl.OptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import top.continew.starter.cache.redisson.util.RedisUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptionAuthorizationTest extends AuthorizationTestSupport {
    private OptionMapper mapper;
    private OptionServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(OptionMapper.class);
        service = new OptionServiceImpl(mapper);
    }

    @Test
    void listFiltersActualDatabaseCategoriesAndReturnsTheirCategory() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(option(1L, "SITE"), option(2L, "MAIL")));
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.hasPermission("system:siteConfig:get")).thenReturn(true);
            var result = service.list(new OptionQuery());
            assertEquals(1, result.size());
            assertEquals("SITE", result.get(0).getCategory());
        }
    }

    @Test
    void categoryQueryRequiresItsOwnPermission() {
        OptionQuery query = new OptionQuery();
        query.setCategory("MAIL");
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkPermission("system:mailConfig:get")).thenThrow(new SecurityException("denied"));
            assertThrows(SecurityException.class, () -> service.list(query));
            verifyNoInteractions(mapper);
        }
    }

    @Test
    void mixedCategoryUpdateChecksAllRowsBeforeAnyWriteOrInvalidation() {
        when(mapper.selectByIds(anyCollection())).thenReturn(List.of(option(1L, "SITE"), option(2L, "MAIL")));
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            stp.when(() -> StpUtil.checkPermission("system:mailConfig:update"))
                .thenThrow(new SecurityException("denied"));
            assertThrows(SecurityException.class, () -> service.update(List
                .of(request(1L, "SITE"), request(2L, "MAIL"))));
            verify(mapper).selectByIds(anyCollection());
            verifyNoMoreInteractions(mapper);
            redis.verifyNoInteractions();
        }
    }

    @Test
    void forgedCodeCannotHideTheCategoryOfTheDatabaseId() {
        when(mapper.selectByIds(anyCollection())).thenReturn(List.of(option(2L, "MAIL")));
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            assertThrows(RuntimeException.class, () -> service.update(List.of(request(2L, "SITE"))));
            verify(mapper).selectByIds(anyCollection());
            verifyNoMoreInteractions(mapper);
            stp.verifyNoInteractions();
            redis.verifyNoInteractions();
        }
    }

    @Test
    void resetByCodesChecksTheirDatabaseCategoriesBeforeMutation() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(option(2L, "MAIL")));
        OptionValueResetReq req = new OptionValueResetReq();
        req.setCode(List.of("MAIL_KEY"));
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            stp.when(() -> StpUtil.checkPermission("system:mailConfig:update"))
                .thenThrow(new SecurityException("denied"));
            assertThrows(SecurityException.class, () -> service.resetValue(req));
            verify(mapper, never()).lambdaUpdate();
            redis.verifyNoInteractions();
        }
    }

    @Test
    void resetCategoryRequiresItsOwnUpdatePermission() {
        OptionValueResetReq req = new OptionValueResetReq();
        req.setCategory("MAIL");
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            stp.when(() -> StpUtil.checkPermission("system:mailConfig:update"))
                .thenThrow(new SecurityException("denied"));
            assertThrows(SecurityException.class, () -> service.resetValue(req));
            verifyNoInteractions(mapper);
            redis.verifyNoInteractions();
        }
    }

    private OptionDO option(Long id, String category) {
        OptionDO option = new OptionDO();
        option.setId(id);
        option.setCode(category + "_KEY");
        option.setCategory(category);
        return option;
    }

    private OptionReq request(Long id, String category) {
        OptionReq req = new OptionReq();
        req.setId(id);
        req.setCode(category + "_KEY");
        req.setValue("value");
        return req;
    }
}
