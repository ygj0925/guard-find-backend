package com.guardfind.backend.property.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.common.config.TenantExtensionProperties;
import com.guardfind.backend.property.mapper.PropertyHomeMapper;
import com.guardfind.backend.property.mapper.PropertyMapper;
import com.guardfind.backend.property.model.entity.PropertyDO;
import com.guardfind.backend.property.model.entity.PropertyHomeDO;
import com.guardfind.backend.property.model.resp.PropertyHomeResp;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PropertyHomeService {
    private final PropertyHomeMapper homeMapper;
    private final PropertyMapper propertyMapper;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TenantExtensionProperties tenant;

    public PropertyHomeResp getForAdmin() {
        assertPlatformTenant();
        var row = homeMapper.selectOne(homeQuery());
        if (row == null) {
            return new PropertyHomeResp();
        }
        try {
            return objectMapper.readValue(row.getContentJson(), PropertyHomeResp.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("首页配置数据无效");
        }
    }

    public PropertyHomeResp getPublic() {
        var home = getForAdmin();
        var now = LocalDateTime.now();
        var activities = home.getActivities().stream().filter(a -> a.activeAt(now)).toList();
        Set<Long> referenced = activities.stream().flatMap(a -> a.getPropertyIds().stream()).collect(Collectors.toSet());
        home.getBanners().stream().filter(b -> b.isEnabled() && "property".equals(b.getTargetType()) && b.isTargetValid())
            .map(b -> Long.valueOf(b.getTargetId())).forEach(referenced::add);
        Set<Long> published = referenced.isEmpty() ? Set.of() : propertyMapper.selectList(Wrappers.<PropertyDO>lambdaQuery()
            .select(PropertyDO::getId).in(PropertyDO::getId, referenced).eq(PropertyDO::getStatus, 2)
            .eq(PropertyDO::getDeleted, 0L)).stream().map(PropertyDO::getId).collect(Collectors.toSet());
        activities.forEach(a -> a.setPropertyIds(a.getPropertyIds().stream().filter(published::contains).distinct().toList()));
        Set<String> activeIds = activities.stream().map(PropertyHomeResp.Activity::getId).collect(Collectors.toSet());
        home.setActivities(activities);
        home.setBanners(home.getBanners().stream().filter(b -> b.isEnabled() && b.isTargetValid())
            .filter(b -> switch (b.getTargetType()) {
                case "all", "recommended" -> true;
                case "activity" -> activeIds.contains(b.getTargetId());
                case "property" -> published.contains(Long.valueOf(b.getTargetId()));
                default -> false;
            }).toList());
        return home;
    }

    public List<Long> requireActivePropertyIds(String activityId) {
        if (activityId == null || !activityId.matches("^[a-z0-9-]{1,40}$")) {
            throw new BusinessException("活动 ID 格式不合法");
        }
        var now = LocalDateTime.now();
        return getForAdmin().getActivities().stream().filter(a -> activityId.equals(a.getId()) && a.activeAt(now))
            .findFirst().orElseThrow(() -> new BusinessException("活动不存在或已失效")).getPropertyIds();
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(PropertyHomeResp home) {
        assertPlatformTenant();
        if (home == null) {
            throw new BusinessException("首页配置不能为空");
        }
        var violations = validator.validate(home);
        if (!violations.isEmpty()) {
            throw new BusinessException(violations.iterator().next().getMessage());
        }
        validateReferences(home);
        var row = new PropertyHomeDO();
        row.setId(1L);
        row.setTenantId(tenant.getDefaultTenantId());
        try {
            row.setContentJson(objectMapper.writeValueAsString(home));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("首页配置无法保存");
        }
        if (homeMapper.update(row, homeQuery()) > 0) {
            return;
        }
        if (homeMapper.selectOne(homeQuery()) != null) {
            homeMapper.update(row, homeQuery());
            return;
        }
        try {
            homeMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            if (homeMapper.selectOne(homeQuery()) == null) {
                throw new BusinessException("首页配置租户不匹配");
            }
            homeMapper.update(row, homeQuery());
        }
    }

    private void validateReferences(PropertyHomeResp home) {
        Set<Long> ids = home.getActivities().stream().flatMap(a -> a.getPropertyIds().stream()).collect(Collectors.toSet());
        home.getBanners().stream().filter(b -> "property".equals(b.getTargetType()))
            .map(b -> Long.valueOf(b.getTargetId())).forEach(ids::add);
        Set<Long> existing = ids.isEmpty() ? Set.of() : propertyMapper.selectList(Wrappers.<PropertyDO>lambdaQuery()
            .select(PropertyDO::getId).in(PropertyDO::getId, ids).eq(PropertyDO::getDeleted, 0L))
            .stream().map(PropertyDO::getId).collect(Collectors.toSet());
        if (!existing.containsAll(ids)) {
            throw new BusinessException("活动或 Banner 引用的房源不存在");
        }
        Set<String> activityIds = home.getActivities().stream().map(PropertyHomeResp.Activity::getId).collect(Collectors.toSet());
        if (home.getBanners().stream().anyMatch(b -> "activity".equals(b.getTargetType()) && !activityIds.contains(b.getTargetId()))) {
            throw new BusinessException("Banner 引用的活动不存在");
        }
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PropertyHomeDO> homeQuery() {
        return Wrappers.<PropertyHomeDO>lambdaQuery().eq(PropertyHomeDO::getId, 1L)
            .eq(PropertyHomeDO::getTenantId, tenant.getDefaultTenantId());
    }

    private void assertPlatformTenant() {
        if (TenantContextHolder.isTenantEnabled() && !tenant.isDefaultTenant()) {
            throw new BusinessException("房源业务仅允许平台管理");
        }
    }
}
