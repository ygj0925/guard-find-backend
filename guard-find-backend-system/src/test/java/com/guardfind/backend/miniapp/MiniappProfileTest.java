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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.controller.MiniappAuthController;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappUserDO;
import com.guardfind.backend.miniapp.service.WechatCodeExchange;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MiniappProfileTest {
    private MiniappUserMapper users;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        TableInfoHelper
            .initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "profile-test"), MiniappUserDO.class);
        users = mock(MiniappUserMapper.class);
        var user = new MiniappUserDO();
        user.setId(42L);
        user.setNickname("公寓住户");
        user.setAvatar("");
        user.setIsPropertyManager(0);
        when(users.selectById(42L)).thenReturn(user);
        MiniappUserContextHolder.setUserId(42L);
        mvc = MockMvcBuilders.standaloneSetup(new MiniappAuthController(users, mock(WechatCodeExchange.class))).build();
    }

    @AfterEach
    void clear() {
        MiniappUserContextHolder.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateUsesAuthenticatedIdentityAndOnlyNicknameAvatar() throws Exception {
        mvc.perform(put("/miniapp/auth/me").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"  公寓住户  \",\"avatar\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value("42"))
            .andExpect(jsonPath("$.isPropertyManager").value(false));
        var captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(users).update(isNull(), captor.capture());
        var wrapper = captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("nickname="));
        assertTrue(wrapper.getSqlSet().contains("avatar="));
        assertFalse(wrapper.getSqlSet().contains("openid"));
        assertFalse(wrapper.getSqlSet().contains("account_status"));
        assertFalse(wrapper.getSqlSet().contains("is_property_manager"));
        assertTrue(wrapper.getSqlSegment().contains("id ="));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(42L));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("公寓住户"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(""));
    }

    @Test
    void rejectsInvalidProfileBeforeWriting() throws Exception {
        for (String body : new String[] {"{\"nickname\":\"   \",\"avatar\":\"\"}", "{\"nickname\":\"" + "a"
            .repeat(65) + "\",\"avatar\":\"\"}", "{\"nickname\":\"住户\",\"avatar\":null}",
            "{\"nickname\":\"住户\",\"avatar\":\"javascript:alert(1)\"}",
            "{\"nickname\":\"住户\",\"avatar\":\"/tmp/avatar.png\"}",
            "{\"nickname\":\"住户\",\"avatar\":\"https://example.com/a b.png\"}",
            "{\"nickname\":\"住户\",\"avatar\":\"https://example.com/" + "a".repeat(1024) + "\"}"}) {
            mvc.perform(put("/miniapp/auth/me").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verify(users, never()).update(any(MiniappUserDO.class), any());
        verify(users, never()).update(isNull(), any());
    }

    @Test
    void acceptsHttpAvatarAndStripsOuterWhitespace() throws Exception {
        mvc.perform(put("/miniapp/auth/me").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"住户\",\"avatar\":\" https://example.com/avatar.png \"}"))
            .andExpect(status().isOk());
        verify(users).update(isNull(), argThat(wrapper -> ((LambdaUpdateWrapper<MiniappUserDO>)wrapper)
            .getParamNameValuePairs()
            .containsValue("https://example.com/avatar.png")));
    }
}
