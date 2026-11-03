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

package com.guardfind.backend.miniapp.util;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;

/**
 * 小程序认证工具（Sa-Token 独立登录域）
 *
 * <p>
 * 与后台管理端 {@code StpUtil}（loginType=login）完全隔离：C 端 token 无法通过后台鉴权，后台 token 也无法访问 C 端接口。
 * 本类不做 {@code @SaCheckLogin} 等注解校验（注解绑定默认 StpLogic），认证由小程序请求拦截器统一完成。
 * </p>
 *
 * @author guardfind
 * @since 2026/9/24
 */
public final class StpMiniappUtil {

    public static String normalizeToken(String token) {
        if (token == null)
            return null;
        String value = token.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : value;
    }

    /**
     * 登录类型
     */
    public static final String LOGIN_TYPE = "miniapp";

    private static final StpLogic STP_LOGIC = new StpLogic(LOGIN_TYPE);

    private StpMiniappUtil() {
    }

    public static StpLogic stpLogic() {
        return STP_LOGIC;
    }

    /**
     * 令牌名称（请求头字段名）
     *
     * @return 令牌名称
     */
    public static String getTokenName() {
        return STP_LOGIC.getTokenName();
    }

    /**
     * 登录并签发 token
     *
     * @param userId    App 用户 ID
     * @param parameter 登录参数（有效期、并发策略等）
     */
    public static void login(Long userId, SaLoginParameter parameter) {
        STP_LOGIC.login(userId, parameter);
    }

    /**
     * 获取当前请求上下文中的 token
     *
     * @return token，未携带时为 null
     */
    public static String getTokenValue() {
        return normalizeToken(STP_LOGIC.getTokenValue());
    }

    /**
     * 根据 token 查询登录用户 ID
     *
     * @param token token
     * @return 用户 ID，未识别时为 null
     */
    public static Long getLoginId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Object loginId = STP_LOGIC.getLoginIdByToken(normalizeToken(token));
        return loginId == null ? null : Long.valueOf(loginId.toString());
    }

    /**
     * 当前是否已登录
     *
     * @return true：已登录
     */
    public static boolean isLogin() {
        return getLoginId(getTokenValue()) != null;
    }

    /**
     * 获取当前登录用户 ID
     *
     * @return 用户 ID
     */
    public static long getUserId() {
        return STP_LOGIC.getLoginIdAsLong();
    }

    /**
     * 获取指定用户的 token 列表（用于定向推送与多端下线）
     *
     * @param userId App 用户 ID
     * @return token 列表
     */
    public java.util.List<String> getTokenValueListByLoginId(Long userId) {
        return STP_LOGIC.getTokenValueListByLoginId(userId);
    }

    /**
     * 退出当前登录
     */
    public static void logout() {
        STP_LOGIC.logoutByTokenValue(getTokenValue());
    }

    /**
     * 根据 token 强制下线
     *
     * @param token token
     */
    public static void logoutByToken(String token) {
        STP_LOGIC.logoutByTokenValue(token);
    }
}
