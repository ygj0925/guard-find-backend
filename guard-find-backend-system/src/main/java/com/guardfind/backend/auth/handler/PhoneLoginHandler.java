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

package com.guardfind.backend.auth.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import com.guardfind.backend.auth.AbstractLoginHandler;
import com.guardfind.backend.auth.enums.AuthTypeEnum;
import com.guardfind.backend.auth.model.req.PhoneLoginReq;
import com.guardfind.backend.auth.model.resp.LoginResp;
import com.guardfind.backend.common.constant.CacheConstants;
import com.guardfind.backend.system.model.entity.user.UserDO;
import com.guardfind.backend.system.model.resp.ClientResp;
import top.continew.starter.cache.redisson.util.RedisUtils;
import top.continew.starter.core.util.validation.ValidationUtils;

/**
 * 手机号登录处理器
 *
 * @author KAI
 * @author Charles7c
 * @since 2024/12/22 14:59
 */
@Component
public class PhoneLoginHandler extends AbstractLoginHandler<PhoneLoginReq> {

    @Override
    public LoginResp login(PhoneLoginReq req, ClientResp client, HttpServletRequest request) {
        // 验证手机号
        UserDO user = userService.getByPhone(req.getPhone());
        ValidationUtils.throwIfNull(user, "此手机号未绑定本系统账号");
        // 检查用户状态
        super.checkUserStatus(user);
        // 执行认证
        return super.authenticate(user, client);
    }

    @Override
    public void preLogin(PhoneLoginReq req, ClientResp client, HttpServletRequest request) {
        String phone = req.getPhone();
        String captchaKey = CacheConstants.CAPTCHA_KEY_PREFIX + phone;
        String captcha = RedisUtils.get(captchaKey);
        ValidationUtils.throwIfBlank(captcha, CAPTCHA_EXPIRED);
        ValidationUtils.throwIfNotEqualIgnoreCase(req.getCaptcha(), captcha, CAPTCHA_ERROR);
        RedisUtils.delete(captchaKey);
    }

    @Override
    public AuthTypeEnum getAuthType() {
        return AuthTypeEnum.PHONE;
    }
}