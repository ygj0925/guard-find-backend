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

package com.guardfind.backend.im;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.guardfind.backend.im.controller.ImController;
import com.guardfind.backend.im.mapper.AdminMessageMapper;
import com.guardfind.backend.im.mapper.ImConversationMapper;
import com.guardfind.backend.im.mapper.ImMessageMapper;
import com.guardfind.backend.im.model.entity.AdminMessageDO;
import com.guardfind.backend.im.service.ImService;
import com.guardfind.backend.im.service.impl.ImServiceImpl;
import com.guardfind.backend.im.websocket.ImWebSocketHandler;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.exception.MiniappBizException;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportUnreadRegressionTest {

    @Mock
    private ImConversationMapper conversations;
    @Mock
    private ImMessageMapper messages;
    @Mock
    private MiniappUserMapper users;
    @Mock
    private MiniappNoticeService notices;
    @Mock
    private AdminMessageMapper support;
    @Mock
    private ObjectProvider<ImWebSocketHandler> wsHandlerProvider;
    @InjectMocks
    private ImServiceImpl service;
    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<AdminMessageDO>> queryCaptor;

    @BeforeEach
    void setup() {
        MiniappUserContextHolder.clear();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
            AdminMessageDO.class);
    }

    @AfterEach
    void cleanup() {
        MiniappUserContextHolder.clear();
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "29, 7", "9007199254740993, 2147483648"})
    void countsOnlyCurrentUsersUnreadAdminMessagesWithoutWrites(long userId, long count) {
        MiniappUserContextHolder.setUserId(userId);
        when(support.selectCount(any())).thenReturn(count);

        assertEquals(count, service.supportUnread());

        verify(support).selectCount(queryCaptor.capture());
        var query = queryCaptor.getValue();
        assertEquals("(user_id = #{ew.paramNameValuePairs.MPGENVAL1}"
            + " AND sender_type = #{ew.paramNameValuePairs.MPGENVAL2}"
            + " AND is_read = #{ew.paramNameValuePairs.MPGENVAL3})", query.getSqlSegment());
        assertEquals(Map.of("MPGENVAL1", userId, "MPGENVAL2", "ADMIN", "MPGENVAL3", 0),
            query.getParamNameValuePairs());
        // Only SELECT COUNT is permitted: no message-list reads, acknowledgements or other writes.
        verifyNoMoreInteractions(support);
        verifyNoInteractions(conversations, messages, users, notices, wsHandlerProvider);
    }

    @Test
    void requiresCurrentUserBeforeQuerying() {
        assertThrows(MiniappBizException.class, () -> service.supportUnread());

        verifyNoInteractions(support, conversations, messages, users, notices, wsHandlerProvider);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 7L, 2147483648L})
    void exposesParameterlessGetAndReturnsServiceCount(long count) throws NoSuchMethodException {
        ImService imService = mock(ImService.class);
        when(imService.supportUnread()).thenReturn(count);
        ImController controller = new ImController(imService);
        var method = ImController.class.getDeclaredMethod("supportUnread");

        assertArrayEquals(new String[] {"/miniapp/im"}, ImController.class
            .getAnnotation(RequestMapping.class).value());
        assertArrayEquals(new String[] {"/support/unread"}, method.getAnnotation(GetMapping.class).value());
        assertEquals(0, method.getParameterCount());
        assertEquals(long.class, method.getReturnType());
        assertEquals(count, controller.supportUnread());
        verify(imService).supportUnread();
        verifyNoMoreInteractions(imService);
    }
}
