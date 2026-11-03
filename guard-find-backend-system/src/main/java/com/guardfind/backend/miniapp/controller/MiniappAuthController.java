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

package com.guardfind.backend.miniapp.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappUserDO;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.util.StpMiniappUtil;
import com.guardfind.backend.miniapp.service.WechatCodeExchange;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.ratelimiter.annotation.RateLimiter;
import top.continew.starter.ratelimiter.enums.LimitType;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/miniapp/auth")
public class MiniappAuthController {
    private final MiniappUserMapper users;
    private final WechatCodeExchange wechat;

    public record LoginReq(@NotBlank @Pattern(regexp = "WECHAT") String authType,
                           @NotBlank @Size(max = 256) String code) {}

    @PostMapping("/login")
    @RateLimiter(name = "MINIAPP:LOGIN", rate = 30, interval = 60, type = LimitType.IP)
    public Map<String, Object> login(@Valid @RequestBody LoginReq req) {
        String openid = wechat.exchange(req.code());
        var query = Wrappers.<MiniappUserDO>lambdaQuery().eq(MiniappUserDO::getOpenid, openid);
        MiniappUserDO user = users.selectOne(query);
        if (user == null) {
            user = new MiniappUserDO();
            user.setOpenid(openid);
            user.setNickname("微信用户");
            user.setAccountStatus(1);
            user.setIsPropertyManager(0);
            try {
                users.insert(user);
            } catch (DuplicateKeyException e) {
                user = users.selectOne(query);
            }
        }
        if (user == null || !Integer.valueOf(1).equals(user.getAccountStatus()))
            throw new BusinessException("账号不可用");
        StpMiniappUtil.login(user.getId(), new SaLoginParameter().setTimeout(604800));
        return Map.of("token", StpMiniappUtil.getTokenValue(), "expiresIn", 604800);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var user = users.selectById(MiniappUserContextHolder.getUserIdRequired());
        return Map.of("userId", user.getId().toString(), "username", "miniapp_" + user.getId(), "nickname", user
            .getNickname(), "avatar", user.getAvatar() == null ? "" : user.getAvatar(), "isPropertyManager", Integer
                .valueOf(1)
                .equals(user.getIsPropertyManager()));
    }

    @PostMapping("/logout")
    public void logout() {
        StpMiniappUtil.logout();
    }
}
