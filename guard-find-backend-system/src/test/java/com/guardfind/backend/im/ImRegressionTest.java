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
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.guardfind.backend.im.mapper.*;
import com.guardfind.backend.im.model.entity.*;
import com.guardfind.backend.im.service.impl.*;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.exception.MiniappBizException;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImRegressionTest {
    ImConversationMapper conversations;
    ImMessageMapper messages;
    AdminMessageMapper support;
    MiniappUserMapper users;
    ImServiceImpl service;

    @BeforeEach
    void setup() {
        for (Class<?> type : List.of(ImConversationDO.class, ImMessageDO.class, AdminMessageDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        conversations = mock(ImConversationMapper.class);
        messages = mock(ImMessageMapper.class);
        support = mock(AdminMessageMapper.class);
        users = mock(MiniappUserMapper.class);
        service = new ImServiceImpl(conversations, messages, users, mock(MiniappNoticeService.class), support, mock(ObjectProvider.class));
        MiniappUserContextHolder.setUserId(1L);
    }

    @AfterEach
    void cleanup() {
        MiniappUserContextHolder.clear();
    }

    ImConversationDO conversation() {
        var c = new ImConversationDO();
        c.setId(10L);
        c.setUserAId(1L);
        c.setUserBId(2L);
        c.setUnreadA(1);
        c.setUnreadB(2);
        return c;
    }

    @Test
    void strangerCannotMarkRead() {
        var c = conversation();
        c.setUserAId(3L);
        when(conversations.lockById(10L)).thenReturn(c);
        assertThrows(MiniappBizException.class, () -> service.markRead(10L));
        verifyNoInteractions(messages);
        verify(conversations, never()).update(isNull(), any());
    }

    @Test
    void strangerCannotReadOrDeleteConversation() {
        var c = conversation();
        c.setUserAId(3L);
        when(conversations.lockById(10L)).thenReturn(c);
        assertThrows(MiniappBizException.class, () -> service.messages(10L, null));
        assertThrows(MiniappBizException.class, () -> service.delete(10L));
        verifyNoInteractions(messages);
    }

    @Test
    void deletingConversationDoesNotClearSharedSummary() {
        when(conversations.lockById(10L)).thenReturn(conversation());
        service.delete(10L);
        var captor = org.mockito.ArgumentCaptor
            .forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(conversations).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("hidden_a"));
        assertFalse(captor.getValue().getSqlSet().contains("last_message"));
    }

    @Test
    void emptyReadDoesNotAcknowledgeUnseenMessages() {
        when(conversations.lockById(10L)).thenReturn(conversation());
        when(messages.selectList(any())).thenReturn(List.of());
        assertTrue(service.messages(10L, 50L).isEmpty());
        verify(messages, never()).update(isNull(), any());
        verify(conversations, never()).update(isNull(), any());
    }

    @Test
    void messagesAreBoundedAndCursorFiltered() {
        when(conversations.lockById(10L)).thenReturn(conversation());
        when(messages.selectList(any())).thenReturn(List.of());
        service.messages(10L, 9007199254740993L);
        var cap = org.mockito.ArgumentCaptor
            .forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(messages).selectList(cap.capture());
        assertTrue(cap.getValue().getSqlSegment().contains("LIMIT 100"));
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(9007199254740993L));
    }

    @Test
    void supportReadAcknowledgesOnlyReturnedIds() {
        var msg = new AdminMessageDO();
        msg.setId(30L);
        msg.setUserId(1L);
        msg.setSenderType("ADMIN");
        when(support.selectList(any())).thenReturn(List.of(msg));
        service.supportMessages(null);
        var cap = org.mockito.ArgumentCaptor
            .forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(support).update(isNull(), cap.capture());
        assertTrue(cap.getValue().getSqlSegment().contains("id IN"));
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(30L));
    }

    @Test
    void cannotCreateConversationWithMissingUser() {
        assertThrows(top.continew.starter.core.exception.BusinessException.class, () -> service.getOrCreate(2L));
        verifyNoInteractions(conversations);
    }

    @Test
    void adminConversationPaginationDoesNotScanMessages() {
        when(support.conversationPage(0, 10, null, null, false, null)).thenReturn(List.of());
        when(support.countConversations(null, null, false)).thenReturn(6000L);
        var admin = new AdminImServiceImpl(support, users, mock(ObjectProvider.class), mock(ObjectProvider.class));
        var page = admin.conversations(null, null);
        assertEquals(6000, page.getTotal());
        verify(support, never()).selectList(any());
    }

    @Test
    void adminSearchNormalizesKeywordAndPassesCursorWithoutOffset() {
        var query = new com.guardfind.backend.im.model.query.AdminConversationQuery();
        query.setKeyword("  9007199254740993  ");
        query.setOnlyUnread(true);
        query.setBeforeId(123L);
        var pagination = new top.continew.starter.extension.crud.model.query.PageQuery();
        pagination.setPage(5);
        pagination.setSize(30);
        when(support.conversationPage(0, 30, "9007199254740993", 9007199254740993L, true, 123L)).thenReturn(List.of());
        var admin = new AdminImServiceImpl(support, users, mock(ObjectProvider.class), mock(ObjectProvider.class));
        admin.conversations(pagination, query);
        verify(support).conversationPage(0, 30, "9007199254740993", 9007199254740993L, true, 123L);
        verify(support).countConversations("9007199254740993", 9007199254740993L, true);
    }

    @Test
    void adminSearchTreatsLikeWildcardsLiterally() {
        var query = new com.guardfind.backend.im.model.query.AdminConversationQuery();
        query.setKeyword("  张%_!  ");
        when(support.conversationPage(0, 10, "张!%!_!!", null, false, null)).thenReturn(List.of());
        var admin = new AdminImServiceImpl(support, users, mock(ObjectProvider.class), mock(ObjectProvider.class));
        admin.conversations(null, query);
        verify(support).countConversations("张!%!_!!", null, false);
    }
}
