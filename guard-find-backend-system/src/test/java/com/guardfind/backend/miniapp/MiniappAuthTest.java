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

package com.guardfind.backend.miniapp;

import com.guardfind.backend.common.config.TenantExtensionProperties;
import com.guardfind.backend.miniapp.config.MiniappWebConfiguration;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.exception.MiniappBizException;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappUserDO;
import com.guardfind.backend.miniapp.util.StpMiniappUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import java.util.ArrayList;
import top.continew.starter.extension.tenant.context.TenantContextHolder;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MiniappAuthTest {
    MiniappUserMapper users;
    HandlerInterceptor auth;
    org.mockito.MockedStatic<TenantContextHolder> tenantContext;

    @BeforeEach
    void setup() {
        tenantContext = mockStatic(TenantContextHolder.class);
        users = mock(MiniappUserMapper.class);
        var registry = mock(InterceptorRegistry.class);
        var captured = new ArrayList<HandlerInterceptor>();
        when(registry.addInterceptor(any())).thenAnswer(i -> {
            HandlerInterceptor interceptor = i.getArgument(0);
            captured.add(interceptor);
            return new InterceptorRegistration(interceptor);
        });
        new MiniappWebConfiguration(users, new TenantExtensionProperties()).addInterceptors(registry);
        auth = captured.getFirst();
        MiniappUserContextHolder.clear();
    }

    @AfterEach
    void clear() {
        tenantContext.close();
        MiniappUserContextHolder.clear();
    }

    MockHttpServletRequest request(String method, String path) {
        var req = new MockHttpServletRequest(method, path);
        req.setServletPath(path);
        return req;
    }

    @Test
    void anonymousCanBrowseButCannotReadPrivateRoutes() throws Exception {
        try (var token = mockStatic(StpMiniappUtil.class)) {
            token.when(() -> StpMiniappUtil.getLoginId(null)).thenReturn(null);
            assertTrue(auth.preHandle(request("GET", "/miniapp/property/list"), new MockHttpServletResponse(), this));
            assertTrue(auth.preHandle(request("GET", "/miniapp/property/10"), new MockHttpServletResponse(), this));
            for (String path : new String[] {"/miniapp/property/mine", "/miniapp/property/favorites",
                "/miniapp/property/history", "/miniapp/property/bookings", "/miniapp/im/conversations",
                "/miniapp/auth/me"})
                assertThrows(MiniappBizException.class, () -> auth
                    .preHandle(request("GET", path), new MockHttpServletResponse(), this));
            assertThrows(MiniappBizException.class, () -> auth
                .preHandle(request("POST", "/miniapp/property/10"), new MockHttpServletResponse(), this));
            assertThrows(MiniappBizException.class, () -> auth
                .preHandle(request("PUT", "/miniapp/auth/me"), new MockHttpServletResponse(), this));
        }
    }

    @Test
    void identityIsClearedAfterRequest() throws Exception {
        try (var token = mockStatic(StpMiniappUtil.class)) {
            token.when(StpMiniappUtil::getTokenValue).thenReturn("mini-token");
            token.when(() -> StpMiniappUtil.getLoginId("mini-token")).thenReturn(1L);
            var user = new MiniappUserDO();
            user.setId(1L);
            user.setAccountStatus(1);
            when(users.selectById(1L)).thenReturn(user);
            var req = request("GET", "/miniapp/auth/me");
            auth.preHandle(req, new MockHttpServletResponse(), this);
            assertEquals(1L, MiniappUserContextHolder.getUserId());
            auth.afterCompletion(req, new MockHttpServletResponse(), this, null);
            assertNull(MiniappUserContextHolder.getUserId());
        }
    }

    @Test
    void lockedUserCannotAccessPrivateRoutes() {
        try (var token = mockStatic(StpMiniappUtil.class)) {
            token.when(StpMiniappUtil::getTokenValue).thenReturn("mini-token");
            token.when(() -> StpMiniappUtil.getLoginId("mini-token")).thenReturn(1L);
            var user = new MiniappUserDO();
            user.setAccountStatus(2);
            when(users.selectById(1L)).thenReturn(user);
            assertThrows(MiniappBizException.class, () -> auth
                .preHandle(request("GET", "/miniapp/auth/me"), new MockHttpServletResponse(), this));
            assertNull(MiniappUserContextHolder.getUserId());
        }
    }

    @Test
    void independentLoginRealm() {
        assertEquals("miniapp", StpMiniappUtil.LOGIN_TYPE);
    }

    @Test
    void acceptsRawAndBearerTokens() {
        assertNull(StpMiniappUtil.normalizeToken(null));
        assertEquals("mini-token", StpMiniappUtil.normalizeToken("mini-token"));
        assertEquals("mini-token", StpMiniappUtil.normalizeToken("  bEaReR mini-token  "));
    }

    @Test
    void rejectsNonPlatformTenantBeforeReadingIdentity() {
        {
            tenantContext.when(TenantContextHolder::isTenantEnabled).thenReturn(true);
            var tenant = mock(TenantExtensionProperties.class);
            when(tenant.isDefaultTenant()).thenReturn(false);
            var registry = mock(InterceptorRegistry.class);
            var interceptors = new ArrayList<HandlerInterceptor>();
            when(registry.addInterceptor(any())).thenAnswer(i -> {
                HandlerInterceptor interceptor = i.getArgument(0);
                interceptors.add(interceptor);
                return new InterceptorRegistration(interceptor);
            });
            new MiniappWebConfiguration(users, tenant).addInterceptors(registry);
            assertThrows(MiniappBizException.class, () -> interceptors.getFirst()
                .preHandle(request("GET", "/miniapp/property/list"), new MockHttpServletResponse(), this));
            verifyNoInteractions(users);
        }
    }
}
