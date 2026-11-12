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

package com.guardfind.backend.property;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappUserDO;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import com.guardfind.backend.property.mapper.*;
import com.guardfind.backend.property.model.entity.*;
import com.guardfind.backend.property.model.query.PropertyQuery;
import com.guardfind.backend.property.model.req.*;
import com.guardfind.backend.property.service.PropertyInteractionService;
import com.guardfind.backend.property.service.impl.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import cn.hutool.extra.spring.SpringUtil;
import org.springframework.context.ApplicationContext;
import top.continew.starter.core.exception.BusinessException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PropertyRegressionTest {
    PropertyMapper properties;
    MiniappUserMapper users;
    PropertyFavoriteMapper favorites;
    PropertyViewHistoryMapper history;
    PropertyBookingMapper bookings;
    MiniappNoticeService notices;
    PropertyInteractionService interactions;
    PropertyServiceImpl service;
    PropertyInteractionServiceImpl interaction;

    @BeforeEach
    void setup() {
        new SpringUtil().setApplicationContext(mock(ApplicationContext.class));
        for (Class<?> type : List
            .of(PropertyDO.class, PropertyFavoriteDO.class, PropertyViewHistoryDO.class, PropertyBookingDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        properties = mock(PropertyMapper.class);
        users = mock(MiniappUserMapper.class);
        favorites = mock(PropertyFavoriteMapper.class);
        history = mock(PropertyViewHistoryMapper.class);
        bookings = mock(PropertyBookingMapper.class);
        notices = mock(MiniappNoticeService.class);
        interactions = mock(PropertyInteractionService.class);
        service = new PropertyServiceImpl(properties, users, interactions);
        interaction = new PropertyInteractionServiceImpl(properties, favorites, history, bookings, users, notices);
        // 2L 默认是房源管家:发布/编辑/上下架在小程序侧都要求管家身份(房源仅运营方维护)
        when(users.selectById(2L)).thenReturn(publisher(2L, true));
        MiniappUserContextHolder.clear();
    }

    MiniappUserDO publisher(Long id, boolean manager) {
        var user = new MiniappUserDO();
        user.setId(id);
        user.setIsPropertyManager(manager ? 1 : 0);
        user.setAccountStatus(1);
        return user;
    }

    @AfterEach
    void cleanup() {
        MiniappUserContextHolder.clear();
    }

    PropertyDO property(int status) {
        var p = new PropertyDO();
        p.setId(10L);
        p.setUserId(2L);
        p.setStatus(status);
        p.setViewCount(0);
        return p;
    }

    PropertyAdminCreateReq req() {
        var r = new PropertyAdminCreateReq();
        r.setTitle("房源");
        r.setAddress("详细地址");
        r.setPropertyType("house");
        r.setPrice(BigDecimal.TEN);
        r.setPriceType("rent");
        r.setStatus(2);
        return r;
    }

    PropertyBookingDO booking(int status) {
        var b = new PropertyBookingDO();
        b.setId(20L);
        b.setPropertyId(10L);
        b.setUserId(1L);
        b.setOwnerId(2L);
        b.setStatus(status);
        return b;
    }

    PropertyBookingReq bookingReq() {
        var r = new PropertyBookingReq();
        r.setBookingTime(LocalDateTime.now().plusDays(1));
        r.setContactName("张");
        r.setContactPhone("13800000000");
        return r;
    }

    @Test
    void anonymousCannotReadDraft() {
        when(properties.selectById(10L)).thenReturn(property(1));
        assertThrows(BusinessException.class, () -> service.detail(10L));
        verify(properties, never()).update(isNull(), any());
    }

    @Test
    void otherUserCannotReadOffline() {
        MiniappUserContextHolder.setUserId(9L);
        when(properties.selectById(10L)).thenReturn(property(3));
        assertThrows(BusinessException.class, () -> service.detail(10L));
    }

    @Test
    void ownerCanReadDraftAndNullCounterDoesNotCrash() {
        MiniappUserContextHolder.setUserId(2L);
        var p = property(1);
        p.setViewCount(null);
        when(properties.selectById(10L)).thenReturn(p);
        assertEquals(1, service.detail(10L).getViewCount());
    }

    @Test
    void updateClearsImagesAndOptionalFieldsAndSavesStatus() {
        var p = property(2);
        p.setImages("[\"https://example.com/a.png\"]");
        p.setDescription("old");
        when(properties.lockById(10L)).thenReturn(p);
        var r = req();
        r.setStatus(1);
        r.setImages(List.of());
        service.update(10L, 2L, r);
        assertEquals("[]", p.getImages());
        assertNull(p.getDescription());
        assertNull(p.getCommunity());
        assertEquals(1, p.getStatus());
        verify(properties).updateById(p);
    }

    @Test
    void saleClearsRentalMode() {
        var r = req();
        r.setPriceType("sale");
        r.setRentalMode("whole");
        service.create(2L, r);
        var cap = ArgumentCaptor.forClass(PropertyDO.class);
        verify(properties).insert(cap.capture());
        assertNull(cap.getValue().getRentalMode());
    }

    @Test
    void invalidStatusRejectedBeforeWrite() {
        var r = req();
        r.setStatus(4);
        assertThrows(BusinessException.class, () -> service.create(2L, r));
        verify(properties, never()).insert(any(PropertyDO.class));
    }

    @Test
    void plainUserCannotPublishProperty() {
        when(users.selectById(7L)).thenReturn(publisher(7L, false));
        assertThrows(BusinessException.class, () -> service.create(7L, req()));
        verify(properties, never()).insert(any(PropertyDO.class));
    }

    @Test
    void plainUserCannotChangeListingStatus() {
        when(users.selectById(7L)).thenReturn(publisher(7L, false));
        assertThrows(BusinessException.class, () -> service.updateStatus(10L, 7L, 2));
        verify(properties, never()).updateById(any(PropertyDO.class));
    }

    @Test
    void adminCanCreateWithoutOwnerAndBypassesPublisherGate() {
        service.createForAdmin(null, req());
        var cap = ArgumentCaptor.forClass(PropertyDO.class);
        verify(properties).insert(cap.capture());
        assertNull(cap.getValue().getUserId());
        assertEquals(2, cap.getValue().getStatus());
    }

    @Test
    void draftDefaultsToStatusOneWhenStatusOmitted() {
        var r = req();
        r.setStatus(null);
        service.create(2L, r);
        var cap = ArgumentCaptor.forClass(PropertyDO.class);
        verify(properties).insert(cap.capture());
        assertEquals(1, cap.getValue().getStatus());
    }

    @Test
    void addressRequiredButCommunityOptional() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var r = req();
            assertTrue(validator.validate(r).isEmpty());
            r.setAddress(" ");
            assertTrue(validator.validate(r).stream().anyMatch(v -> v.getPropertyPath().toString().equals("address")));
        }
    }

    @Test
    void imagesAndRentalModeValidated() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var r = req();
            r.setImages(List.of("javascript:alert(1)"));
            r.setRentalMode("invalid");
            assertEquals(2, factory.getValidator().validate(r).size());
        }
    }

    @Test
    void rentalModeFilterIsIncluded() {
        when(properties.selectPage(any(), any())).thenReturn(new Page<PropertyDO>());
        var query = new PropertyQuery();
        query.setPriceType("rent");
        query.setRentalMode("shared");
        service.page(query, null);
        var cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(properties).selectPage(any(), cap.capture());
        assertTrue(cap.getValue().getSqlSegment().contains("rental_mode"));
    }

    @Test
    void editChecksOwnership() {
        when(properties.lockById(10L)).thenReturn(property(2));
        assertThrows(BusinessException.class, () -> service.update(10L, 1L, req()));
        verify(properties, never()).updateById(any(PropertyDO.class));
    }

    @Test
    void favoriteRequiresPublishedProperty() {
        when(properties.lockById(10L)).thenReturn(property(1));
        assertThrows(BusinessException.class, () -> interaction.toggleFavorite(1L, 10L));
        verifyNoInteractions(favorites);
    }

    @Test
    void duplicateActiveBookingIsRejected() {
        when(properties.lockById(10L)).thenReturn(property(2));
        when(bookings.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> interaction.createBooking(1L, 10L, bookingReq()));
        verify(bookings, never()).insert(any(PropertyBookingDO.class));
    }

    @Test
    void cannotBookOwnProperty() {
        when(properties.lockById(10L)).thenReturn(property(2));
        assertThrows(BusinessException.class, () -> interaction.createBooking(2L, 10L, bookingReq()));
    }

    @Test
    void bookingsRejectInvalidRole() {
        assertThrows(BusinessException.class, () -> interaction.bookings(1L, "admin", null));
    }

    @Test
    void concurrentBookingChangeDoesNotNotify() {
        when(bookings.selectById(20L)).thenReturn(booking(1));
        when(bookings.update(isNull(), any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> interaction.updateBookingStatus(2L, 20L, 2));
        verifyNoInteractions(notices);
    }

    @Test
    void ownerCanConfirmPendingBooking() {
        when(bookings.selectById(20L)).thenReturn(booking(1));
        when(bookings.update(isNull(), any())).thenReturn(1);
        interaction.updateBookingStatus(2L, 20L, 2);
        verify(notices).send(eq(1L), eq("PROPERTY_BOOKING"), eq(20L), anyString(), anyString());
    }

    @Test
    void guestCannotConfirmAndOwnerCannotCancel() {
        when(bookings.selectById(20L)).thenReturn(booking(1));
        assertThrows(BusinessException.class, () -> interaction.updateBookingStatus(1L, 20L, 2));
        assertThrows(BusinessException.class, () -> interaction.updateBookingStatus(2L, 20L, 4));
    }

    @Test
    void terminalBookingsCannotBeChanged() {
        when(bookings.selectById(20L)).thenReturn(booking(3));
        assertThrows(BusinessException.class, () -> interaction.updateBookingStatus(1L, 20L, 4));
    }

    @Test
    void deletionRejectsActiveAppointments() {
        when(bookings.selectCount(any())).thenReturn(1L);
        assertThrows(BusinessException.class, () -> interaction.cleanupForDelete(10L));
        verifyNoInteractions(favorites, history);
    }

    @Test
    void favoritesUseJoinedCountAndEnrichOwner() {
        var p = new Page<PropertyDO>();
        p.setRecords(List.of(property(2)));
        p.setTotal(1);
        when(properties.favoritePage(any(), eq(1L))).thenReturn(p);
        var owner = new MiniappUserDO();
        owner.setId(2L);
        owner.setNickname("房东");
        when(users.selectByIds(any())).thenReturn(List.of(owner));
        var result = interaction.favorites(1L, null);
        assertEquals(1, result.getTotal());
        assertEquals("房东", result.getList().getFirst().getOwnerNickname());
        assertTrue(result.getList().getFirst().getIsFavorited());
        verifyNoInteractions(favorites);
    }

    @Test
    void basicEditCannotBypassStatusPermission() {
        when(properties.lockById(10L)).thenReturn(property(1));
        try (var auth = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            auth.when(() -> cn.dev33.satoken.stp.StpUtil.checkPermission("system:property:status"))
                .thenThrow(new BusinessException("无上下架权限"));
            assertThrows(BusinessException.class, () -> service.updateForAdmin(10L, req()));
            verify(properties, never()).updateById(any(PropertyDO.class));
        }
    }

    @Test
    void adminOwnerIsOptionalButMustBePositiveWhenProvided() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = req();
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setOwnerId(0L);
            assertTrue(factory.getValidator()
                .validate(request)
                .stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("ownerId")));
        }
        service.createForAdmin(null, req());
        var inserted = ArgumentCaptor.forClass(PropertyDO.class);
        verify(properties).insert(inserted.capture());
        assertNull(inserted.getValue().getUserId());
        verifyNoInteractions(users);
    }

    @Test
    void adminCannotBindAnUnknownOrDisabledOwner() {
        assertThrows(BusinessException.class, () -> service.createForAdmin(9L, req()));
        var owner = new MiniappUserDO();
        owner.setAccountStatus(2);
        when(users.selectById(9L)).thenReturn(owner);
        assertThrows(BusinessException.class, () -> service.createForAdmin(9L, req()));
        verify(properties, never()).insert(any(PropertyDO.class));
    }

    @Test
    void unownedDraftIsNotVisibleToAnonymousAndCannotBeEditedByMiniappUsers() {
        var draft = property(1);
        draft.setUserId(null);
        when(properties.selectById(10L)).thenReturn(draft);
        when(properties.lockById(10L)).thenReturn(draft);
        assertThrows(BusinessException.class, () -> service.detail(10L));
        assertThrows(BusinessException.class, () -> service.update(10L, 2L, req()));
        assertThrows(BusinessException.class, () -> service.update(10L, null, req()));
        verify(properties, never()).updateById(any(PropertyDO.class));
    }

    @Test
    void publishedUnownedPropertyCanBeReadListedAndFavoritedWithoutOwnerLookup() {
        var published = property(2);
        published.setUserId(null);
        when(properties.selectById(10L)).thenReturn(published);
        assertNull(service.detail(10L).getUserId());
        assertNull(service.detailForAdmin(10L).getUserId());
        var page = new Page<PropertyDO>();
        page.setRecords(List.of(published));
        page.setTotal(1);
        when(properties.selectPage(any(), any())).thenReturn(page);
        when(properties.favoritePage(any(), eq(1L))).thenReturn(page);
        assertEquals(1, service.pageForAdmin(null, null, null, null).getTotal());
        assertNull(interaction.favorites(1L, null).getList().getFirst().getUserId());
        verifyNoInteractions(users);
    }

    @Test
    void unownedPropertyCanBeBookedWithoutSendingANotificationToANullUser() {
        var published = property(2);
        published.setUserId(null);
        when(properties.lockById(10L)).thenReturn(published);
        interaction.createBooking(1L, 10L, bookingReq());
        var inserted = ArgumentCaptor.forClass(PropertyBookingDO.class);
        verify(bookings).insert(inserted.capture());
        assertNull(inserted.getValue().getOwnerId());
        assertEquals(1L, inserted.getValue().getUserId());
        verifyNoInteractions(notices);
    }

    @Test
    void adminCanConfirmAnUnownedBookingAndGuestCanCancelWithoutANullNotification() {
        var pending = booking(1);
        pending.setOwnerId(null);
        when(bookings.selectById(20L)).thenReturn(pending);
        when(bookings.update(isNull(), any())).thenReturn(1);
        assertThrows(BusinessException.class, () -> interaction.updateBookingStatus(null, 20L, 2));
        assertThrows(BusinessException.class, () -> interaction.updateBookingStatus(9L, 20L, 2));
        interaction.updateBookingStatusForAdmin(20L, 2);
        verify(notices).send(eq(1L), eq("PROPERTY_BOOKING"), eq(20L), anyString(), anyString());
        clearInvocations(notices);
        interaction.updateBookingStatus(1L, 20L, 4);
        verifyNoInteractions(notices);
    }
}
