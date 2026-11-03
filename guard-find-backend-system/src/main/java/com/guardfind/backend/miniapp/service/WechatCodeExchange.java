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

package com.guardfind.backend.miniapp.service;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.continew.starter.core.exception.BusinessException;
import java.util.Map;

@Component
public class WechatCodeExchange {
    @Value("${miniapp.wechat.app-id:}")
    private String appId;
    @Value("${miniapp.wechat.app-secret:}")
    private String appSecret;

    public String exchange(String code) {
        if (appId.isBlank() || appSecret.isBlank())
            throw new BusinessException("小程序微信登录尚未配置");
        try {
            var response = JSONUtil.parseObj(HttpUtil.get("https://api.weixin.qq.com/sns/jscode2session", Map
                .of("appid", appId, "secret", appSecret, "js_code", code, "grant_type", "authorization_code"), 5000));
            String openid = response.getStr("openid");
            if (response.getInt("errcode", 0) != 0 || openid == null || openid.isBlank())
                throw new BusinessException("微信登录凭证无效或已过期，请重新登录");
            return openid;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("微信登录暂时不可用，请稍后重试");
        }
    }
}
