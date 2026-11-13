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

package com.guardfind.backend.miniapp.config;

import com.guardfind.backend.common.config.TenantExtensionProperties;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.exception.MiniappBizException;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.util.StpMiniappUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

@Configuration
@RequiredArgsConstructor
public class MiniappWebConfiguration implements WebMvcConfigurer {
    private final MiniappUserMapper users;
    private final TenantExtensionProperties tenant;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                MiniappUserContextHolder.clear();
                if (TenantContextHolder.isTenantEnabled() && !tenant.isDefaultTenant()) {
                    throw new MiniappBizException("403", "小程序仅使用平台租户");
                }
                String path = request.getServletPath();
                boolean publicRoute = "POST".equals(request.getMethod()) && path.equals("/miniapp/auth/login") || "GET"
                    .equals(request.getMethod()) && (path.equals("/miniapp/property/list") || path
                        .equals("/miniapp/property/home") || path.matches("/miniapp/property/[0-9]+") || path
                            .equals("/miniapp/region/tree"));
                Long id = StpMiniappUtil.getLoginId(StpMiniappUtil.getTokenValue());
                if (id != null) {
                    var user = users.selectById(id);
                    if (user == null || !Integer.valueOf(1).equals(user.getAccountStatus())) {
                        throw new MiniappBizException("401", "账号不可用，请重新登录");
                    }
                    MiniappUserContextHolder.setUserId(id);
                } else if (!publicRoute) {
                    throw new MiniappBizException("401", "请先登录");
                }
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Object handler,
                                        Exception ex) {
                MiniappUserContextHolder.clear();
            }
        }).addPathPatterns("/miniapp/**").excludePathPatterns("/miniapp/ws/**");

        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (TenantContextHolder.isTenantEnabled() && !tenant.isDefaultTenant()) {
                    throw new MiniappBizException("403", "房源业务仅允许平台管理");
                }
                return true;
            }
        }).addPathPatterns("/system/property/**", "/system/im/**");
    }
}
