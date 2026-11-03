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

package com.guardfind.backend.property.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappUserDO;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import com.guardfind.backend.property.mapper.PropertyBookingMapper;
import com.guardfind.backend.property.mapper.PropertyFavoriteMapper;
import com.guardfind.backend.property.mapper.PropertyMapper;
import com.guardfind.backend.property.mapper.PropertyViewHistoryMapper;
import com.guardfind.backend.property.model.entity.PropertyBookingDO;
import com.guardfind.backend.property.model.entity.PropertyDO;
import com.guardfind.backend.property.model.entity.PropertyFavoriteDO;
import com.guardfind.backend.property.model.entity.PropertyViewHistoryDO;
import com.guardfind.backend.property.model.req.PropertyBookingReq;
import com.guardfind.backend.property.model.resp.PropertyBookingResp;
import com.guardfind.backend.property.model.resp.PropertyResp;
import com.guardfind.backend.property.service.PropertyInteractionService;
import com.guardfind.backend.property.support.PropertyRespAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 房源互动实现。收藏/历史每用户每房源一行;预约走 guest(预约人)/owner(房东)双视角状态机。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyInteractionServiceImpl implements PropertyInteractionService {

    /** 预约状态 */
    private static final int BOOKING_PENDING = 1;
    private static final int BOOKING_CONFIRMED = 2;
    private static final int BOOKING_REJECTED = 3;
    private static final int BOOKING_CANCELED = 4;

    private static final String ROLE_OWNER = "owner";

    private final PropertyMapper propertyMapper;
    private final PropertyFavoriteMapper favoriteMapper;
    private final PropertyViewHistoryMapper viewHistoryMapper;
    private final PropertyBookingMapper bookingMapper;
    private final MiniappUserMapper appUserMapper;
    private final MiniappNoticeService messageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(Long userId, Long propertyId) {
        PropertyDO property = propertyMapper.lockById(propertyId);
        if (property == null || !Integer.valueOf(2).equals(property.getStatus()))
            throw new BusinessException("房源不存在或未上架");
        PropertyFavoriteDO existing = favoriteMapper.selectOne(Wrappers.<PropertyFavoriteDO>lambdaQuery()
            .eq(PropertyFavoriteDO::getUserId, userId)
            .eq(PropertyFavoriteDO::getPropertyId, propertyId)
            .last("LIMIT 1"));
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return false;
        }
        PropertyFavoriteDO favorite = new PropertyFavoriteDO();
        favorite.setUserId(userId);
        favorite.setPropertyId(propertyId);
        favorite.setCreateTime(LocalDateTime.now());
        favoriteMapper.insert(favorite);
        return true;
    }

    @Override
    public boolean isFavorited(Long userId, Long propertyId) {
        return favoriteMapper.selectCount(Wrappers.<PropertyFavoriteDO>lambdaQuery()
            .eq(PropertyFavoriteDO::getUserId, userId)
            .eq(PropertyFavoriteDO::getPropertyId, propertyId)) > 0;
    }

    @Override
    public PageResp<PropertyResp> favorites(Long userId, PageQuery pageQuery) {
        Page<PropertyDO> page = propertyMapper
            .favoritePage(new Page<>(pageNum(pageQuery), pageSize(pageQuery)), userId);
        PageResp<PropertyResp> result = propertyPage(page);
        result.getList().forEach(p -> p.setIsFavorited(true));
        return result;
    }

    @Override
    public PageResp<PropertyResp> history(Long userId, PageQuery pageQuery) {
        return propertyPage(propertyMapper.historyPage(new Page<>(pageNum(pageQuery), pageSize(pageQuery)), userId));
    }

    @Override
    public void recordView(Long userId, Long propertyId) {
        try {
            PropertyViewHistoryDO existing = viewHistoryMapper.selectOne(Wrappers.<PropertyViewHistoryDO>lambdaQuery()
                .eq(PropertyViewHistoryDO::getUserId, userId)
                .eq(PropertyViewHistoryDO::getPropertyId, propertyId)
                .last("LIMIT 1"));
            if (existing != null) {
                viewHistoryMapper.update(null, Wrappers.<PropertyViewHistoryDO>update()
                    .eq("id", existing.getId())
                    .set("view_time", LocalDateTime.now()));
            } else {
                PropertyViewHistoryDO record = new PropertyViewHistoryDO();
                record.setUserId(userId);
                record.setPropertyId(propertyId);
                record.setViewTime(LocalDateTime.now());
                viewHistoryMapper.insert(record);
            }
        } catch (Exception e) {
            // 浏览历史失败不影响详情展示
            log.debug("[property] 记录浏览历史失败 userId={} propertyId={}: {}", userId, propertyId, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createBooking(Long userId, Long propertyId, PropertyBookingReq req) {
        PropertyDO property = propertyMapper.lockById(propertyId);
        if (property == null)
            throw new BusinessException("房源不存在");
        if (Objects.equals(property.getUserId(), userId)) {
            throw new BusinessException("不能预约自己的房源");
        }
        if (property.getStatus() != 2) {
            throw new BusinessException("该房源当前不可预约");
        }
        if (req.getBookingTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("预约时间必须晚于当前时间");
        }
        if (bookingMapper.selectCount(Wrappers.<PropertyBookingDO>lambdaQuery()
            .eq(PropertyBookingDO::getUserId, userId)
            .eq(PropertyBookingDO::getPropertyId, propertyId)
            .in(PropertyBookingDO::getStatus, 1, 2)) > 0) {
            throw new BusinessException("已有待确认或已确认的预约，请勿重复提交");
        }
        PropertyBookingDO booking = new PropertyBookingDO();
        booking.setPropertyId(propertyId);
        booking.setUserId(userId);
        booking.setOwnerId(property.getUserId());
        booking.setBookingTime(req.getBookingTime());
        booking.setContactName(StrUtil.trim(req.getContactName()));
        booking.setContactPhone(StrUtil.trim(req.getContactPhone()));
        booking.setRemark(StrUtil.trimToNull(req.getRemark()));
        booking.setStatus(BOOKING_PENDING);
        booking.setCreateTime(LocalDateTime.now());
        bookingMapper.insert(booking);

        // 站内信通知房东
        if (property.getUserId() != null) {
            messageService.send(property.getUserId(), "PROPERTY_BOOKING", booking.getId(), "收到看房预约", "你的房源「" + property
                .getTitle() + "」有新的看房预约,点击查看");
        }
        return booking.getId();
    }

    @Override
    public PageResp<PropertyBookingResp> bookings(Long userId, String role, PageQuery pageQuery) {
        if (!"guest".equalsIgnoreCase(role) && !ROLE_OWNER.equalsIgnoreCase(role))
            throw new BusinessException("预约角色取值不合法");
        boolean asOwner = ROLE_OWNER.equalsIgnoreCase(role);
        Page<PropertyBookingDO> page = bookingMapper
            .selectPage(new Page<>(pageNum(pageQuery), pageSize(pageQuery)), Wrappers.<PropertyBookingDO>lambdaQuery()
                .eq(asOwner, PropertyBookingDO::getOwnerId, userId)
                .eq(!asOwner, PropertyBookingDO::getUserId, userId)
                .orderByDesc(PropertyBookingDO::getCreateTime));
        return toBookingPage(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBookingStatus(Long userId, Long bookingId, Integer status) {
        PropertyBookingDO booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BusinessException("预约不存在");
        }
        boolean asOwner = Objects.equals(booking.getOwnerId(), userId);
        boolean asGuest = Objects.equals(booking.getUserId(), userId);
        if (userId == null || (!asOwner && !asGuest)) {
            throw new BusinessException("预约不存在");
        }
        changeBookingStatus(booking, asGuest, status);
    }

    private void changeBookingStatus(PropertyBookingDO booking, boolean asGuest, Integer status) {
        if (status == null) {
            throw new BusinessException("状态取值不合法");
        }
        if (asGuest) {
            if (status != BOOKING_CANCELED) {
                throw new BusinessException("预约人只能取消预约");
            }
            if (booking.getStatus() != BOOKING_PENDING && booking.getStatus() != BOOKING_CONFIRMED) {
                throw new BusinessException("当前状态不可取消");
            }
        } else {
            if (status != BOOKING_CONFIRMED && status != BOOKING_REJECTED) {
                throw new BusinessException("房东只能确认或拒绝预约");
            }
            if (booking.getStatus() != BOOKING_PENDING) {
                throw new BusinessException("该预约已处理");
            }
        }
        int changed = bookingMapper.update(null, Wrappers.<PropertyBookingDO>lambdaUpdate()
            .eq(PropertyBookingDO::getId, booking.getId())
            .eq(PropertyBookingDO::getStatus, booking.getStatus())
            .set(PropertyBookingDO::getStatus, status)
            .set(PropertyBookingDO::getUpdateTime, LocalDateTime.now()));
        if (changed != 1)
            throw new BusinessException("预约状态已变化，请刷新后重试");

        // 站内信通知对方
        Long notifyUserId = asGuest ? booking.getOwnerId() : booking.getUserId();
        String action = status == BOOKING_CONFIRMED ? "已确认" : status == BOOKING_REJECTED ? "已拒绝" : "已取消";
        if (notifyUserId != null) {
            messageService.send(notifyUserId, "PROPERTY_BOOKING", booking
                .getId(), "看房预约" + action, "你的看房预约" + action + ",点击查看详情");
        }
    }

    public PageResp<PropertyBookingResp> bookingsForAdmin(Integer status, Long propertyId, PageQuery query) {
        var page = bookingMapper.selectPage(new Page<>(pageNum(query), pageSize(query)), Wrappers
            .<PropertyBookingDO>lambdaQuery()
            .eq(status != null, PropertyBookingDO::getStatus, status)
            .eq(propertyId != null, PropertyBookingDO::getPropertyId, propertyId)
            .orderByDesc(PropertyBookingDO::getId));
        return toBookingPage(page.getRecords(), page.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateBookingStatusForAdmin(Long id, Integer status) {
        var booking = bookingMapper.selectById(id);
        if (booking == null)
            throw new BusinessException("预约不存在");
        changeBookingStatus(booking, Integer.valueOf(4).equals(status), status);
    }

    @Override
    public void cleanupForDelete(Long propertyId) {
        if (bookingMapper.selectCount(Wrappers.<PropertyBookingDO>lambdaQuery()
            .eq(PropertyBookingDO::getPropertyId, propertyId)
            .in(PropertyBookingDO::getStatus, 1, 2)) > 0)
            throw new BusinessException("房源仍有待确认或已确认预约，请先处理预约");
        favoriteMapper.delete(Wrappers.<PropertyFavoriteDO>lambdaQuery()
            .eq(PropertyFavoriteDO::getPropertyId, propertyId));
        viewHistoryMapper.delete(Wrappers.<PropertyViewHistoryDO>lambdaQuery()
            .eq(PropertyViewHistoryDO::getPropertyId, propertyId));
    }

    private PropertyDO requireProperty(Long propertyId) {
        PropertyDO property = propertyMapper.selectById(propertyId);
        if (property == null) {
            throw new BusinessException("房源不存在");
        }
        return property;
    }

    private PageResp<PropertyResp> propertyPage(Page<PropertyDO> page) {
        List<Long> ownerIds = page.getRecords()
            .stream()
            .map(PropertyDO::getUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, MiniappUserDO> owners = ownerIds.isEmpty()
            ? Map.of()
            : appUserMapper.selectByIds(ownerIds)
                .stream()
                .collect(Collectors.toMap(MiniappUserDO::getId, Function.identity()));
        var result = new PageResp<PropertyResp>();
        result.setTotal(page.getTotal());
        result.setList(page.getRecords().stream().map(p -> {
            var resp = PropertyRespAssembler.toResp(p);
            var owner = p.getUserId() == null ? null : owners.get(p.getUserId());
            if (owner != null) {
                resp.setOwnerNickname(owner.getNickname());
                resp.setOwnerAvatar(owner.getAvatar());
                resp.setOwnerIsManager(Integer.valueOf(1).equals(owner.getIsPropertyManager()));
            }
            return resp;
        }).toList());
        return result;
    }

    private PageResp<PropertyBookingResp> toBookingPage(List<PropertyBookingDO> records, long total) {
        List<Long> propertyIds = records.stream().map(PropertyBookingDO::getPropertyId).distinct().toList();
        List<Long> userIds = records.stream().map(PropertyBookingDO::getUserId).distinct().toList();
        Map<Long, PropertyDO> properties = propertyIds.isEmpty()
            ? Map.of()
            : propertyMapper.selectByIds(propertyIds)
                .stream()
                .collect(Collectors.toMap(PropertyDO::getId, Function.identity()));
        Map<Long, MiniappUserDO> users = userIds.isEmpty()
            ? Map.of()
            : appUserMapper.selectByIds(userIds)
                .stream()
                .collect(Collectors.toMap(MiniappUserDO::getId, Function.identity()));
        List<PropertyBookingResp> list = records.stream().map(booking -> {
            PropertyBookingResp resp = new PropertyBookingResp();
            resp.setId(booking.getId());
            resp.setPropertyId(booking.getPropertyId());
            resp.setUserId(booking.getUserId());
            resp.setOwnerId(booking.getOwnerId());
            resp.setBookingTime(booking.getBookingTime());
            resp.setContactName(booking.getContactName());
            resp.setContactPhone(booking.getContactPhone());
            resp.setRemark(booking.getRemark());
            resp.setStatus(booking.getStatus());
            resp.setCreateTime(booking.getCreateTime());
            PropertyDO property = properties.get(booking.getPropertyId());
            if (property != null) {
                resp.setPropertyTitle(property.getTitle());
                resp.setPropertyPrice(property.getPrice());
                resp.setPropertyPriceType(property.getPriceType());
                if (StrUtil.isNotBlank(property.getImages())) {
                    List<String> images = JSONUtil.toList(property.getImages(), String.class);
                    if (!images.isEmpty()) {
                        resp.setPropertyImage(images.get(0));
                    }
                }
            }
            MiniappUserDO user = users.get(booking.getUserId());
            if (user != null) {
                resp.setUserNickname(user.getNickname());
                resp.setUserAvatar(user.getAvatar());
            }
            return resp;
        }).toList();
        PageResp<PropertyBookingResp> pageResp = new PageResp<>();
        pageResp.setList(list);
        pageResp.setTotal(total);
        return pageResp;
    }

    private int pageNum(PageQuery pageQuery) {
        return pageQuery == null || pageQuery.getPage() == null ? 1 : Math.max(1, pageQuery.getPage());
    }

    private int pageSize(PageQuery pageQuery) {
        return pageQuery == null || pageQuery.getSize() == null ? 10 : Math.max(1, Math.min(100, pageQuery.getSize()));
    }
}
