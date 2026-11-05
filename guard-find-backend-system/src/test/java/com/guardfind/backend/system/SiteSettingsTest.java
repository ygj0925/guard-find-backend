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
import com.guardfind.backend.common.constant.CacheConstants;
import com.guardfind.backend.system.controller.CommonController;
import com.guardfind.backend.system.mapper.OptionMapper;
import com.guardfind.backend.system.model.entity.OptionDO;
import com.guardfind.backend.system.model.req.OptionReq;
import com.guardfind.backend.system.model.req.OptionValueResetReq;
import com.guardfind.backend.system.service.*;
import com.guardfind.backend.system.service.impl.OptionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.continew.starter.cache.redisson.util.RedisUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SiteSettingsTest {
    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void anonymousAndNonAdminUsersCanReadSiteBrandingWithDefaults() {
        var mapper = mock(OptionMapper.class);
        var title = option(1L, "SITE_TITLE");
        title.setDefaultValue("默认系统名称");
        var logo = option(2L, "SITE_LOGO");
        logo.setValue("/custom-logo.png");
        when(mapper.selectByCategory("SITE")).thenReturn(List.of(title, logo));
        var service = new OptionServiceImpl(mapper);
        var controller = new CommonController(mock(FileService.class), mock(DictItemService.class), service);
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkPermission(anyString())).thenThrow(new SecurityException("denied"));
            var result = controller.listSiteOptionDict();
            assertEquals(2, result.size());
            assertEquals("SITE_TITLE", result.get(0).getLabel());
            assertEquals("默认系统名称", result.get(0).getValue());
            assertEquals("/custom-logo.png", result.get(1).getValue());
            stp.verifyNoInteractions();
            verify(mapper).selectByCategory("SITE");
            verifyNoMoreInteractions(mapper);
        }
    }

    @Test
    void publicEndpointOnlyExposesAllowlistedBrandingFields() {
        var service = mock(OptionService.class);
        when(service.getByCategory(com.guardfind.backend.system.enums.OptionCategoryEnum.SITE)).thenReturn(Map
            .of("SITE_TITLE", "泊之湾", "SITE_LOGO", "", "MAIL_PASSWORD", "secret", "SITE_INTERNAL", "private"));
        var controller = new CommonController(mock(FileService.class), mock(DictItemService.class), service);
        var result = controller.listSiteOptionDict();
        assertEquals(List.of("SITE_TITLE", "SITE_LOGO"), result.stream().map(item -> item.getLabel()).toList());
        assertEquals("", result.get(1).getValue());
        verify(service, never()).list(any());
    }

    @Test
    void updateInvalidatesOnlyAfterCommitAndAfterDatabaseWrite() {
        var mapper = mock(OptionMapper.class);
        when(mapper.selectByIds(anyCollection())).thenReturn(List.of(option(1L, "SITE_TITLE")));
        var service = new OptionServiceImpl(mapper);
        beginTransaction();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            service.update(List.of(request()));
            verify(mapper).updateById(anyCollection());
            redis.verifyNoInteractions();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            redis.verify(() -> RedisUtils.deleteByPattern(CacheConstants.OPTION_KEY_PREFIX + "*"));
        }
    }

    @Test
    void rolledBackUpdateDoesNotInvalidatePublishedConfiguration() {
        var mapper = mock(OptionMapper.class);
        when(mapper.selectByIds(anyCollection())).thenReturn(List.of(option(1L, "SITE_TITLE")));
        var service = new OptionServiceImpl(mapper);
        beginTransaction();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            service.update(List.of(request()));
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            redis.verifyNoInteractions();
        }
    }

    @Test
    void resetAlsoDefersInvalidationUntilCommit() {
        var mapper = mock(OptionMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(option(1L, "SITE_TITLE")));
        var update = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(update).set(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class), isNull());
        when(mapper.lambdaUpdate()).thenReturn(update);
        var service = new OptionServiceImpl(mapper);
        var req = new OptionValueResetReq();
        req.setCategory("SITE");
        beginTransaction();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
            MockedStatic<RedisUtils> redis = mockStatic(RedisUtils.class)) {
            service.resetValue(req);
            verify(update).update();
            redis.verifyNoInteractions();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            redis.verify(() -> RedisUtils.deleteByPattern(CacheConstants.OPTION_KEY_PREFIX + "*"));
        }
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private OptionDO option(Long id, String code) {
        var option = new OptionDO();
        option.setId(id);
        option.setCategory("SITE");
        option.setCode(code);
        return option;
    }

    private OptionReq request() {
        var req = new OptionReq();
        req.setId(1L);
        req.setCode("SITE_TITLE");
        req.setValue("泊之湾");
        return req;
    }
}
