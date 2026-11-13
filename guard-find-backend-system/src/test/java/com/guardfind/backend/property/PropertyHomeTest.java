package com.guardfind.backend.property;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.common.config.TenantExtensionProperties;
import com.guardfind.backend.property.controller.PropertyAdminController;
import com.guardfind.backend.property.controller.PropertyHomeController;
import com.guardfind.backend.property.mapper.PropertyHomeMapper;
import com.guardfind.backend.property.mapper.PropertyMapper;
import com.guardfind.backend.property.model.entity.PropertyDO;
import com.guardfind.backend.property.model.entity.PropertyHomeDO;
import com.guardfind.backend.property.model.resp.PropertyHomeResp;
import com.guardfind.backend.property.service.PropertyHomeService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PropertyHomeTest {
    private PropertyHomeMapper homes;
    private PropertyMapper properties;
    private ObjectMapper json;
    private ValidatorFactory validation;
    private PropertyHomeService service;
    private MockedStatic<TenantContextHolder> tenantContext;

    @BeforeEach
    void setup() {
        tenantContext = mockStatic(TenantContextHolder.class);
        for (var type : List.of(PropertyDO.class, PropertyHomeDO.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), type);
        }
        homes = mock(PropertyHomeMapper.class);
        properties = mock(PropertyMapper.class);
        json = new ObjectMapper();
        validation = Validation.buildDefaultValidatorFactory();
        service = new PropertyHomeService(homes, properties, json, validation.getValidator(), new TenantExtensionProperties());
    }

    @AfterEach
    void cleanup() {
        validation.close();
        tenantContext.close();
    }

    private PropertyHomeResp.Activity activity(String id) {
        var a = new PropertyHomeResp.Activity();
        a.setId(id);
        a.setTitle("活动");
        a.setEnabled(true);
        a.setPropertyIds(List.of(10L, 11L, 12L));
        return a;
    }

    private PropertyHomeResp.Banner banner(String id, String type, String target) {
        var b = new PropertyHomeResp.Banner();
        b.setId(id);
        b.setTitle("Banner");
        b.setTargetType(type);
        b.setTargetId(target);
        b.setEnabled(true);
        return b;
    }

    private String time(LocalDateTime value) {
        return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void stored(PropertyHomeResp home) throws Exception {
        var row = new PropertyHomeDO();
        row.setId(1L);
        row.setTenantId(0L);
        row.setContentJson(json.writeValueAsString(home));
        when(homes.selectOne(any())).thenReturn(row);
    }

    private PropertyDO property(long id, int status) {
        var p = new PropertyDO();
        p.setId(id);
        p.setStatus(status);
        p.setDeleted(0L);
        return p;
    }

    @Test
    void emptyConfigHasOnlyContractFieldsAndNoInventedData() throws Exception {
        var empty = service.getPublic();
        assertNull(empty.getSupportPhone());
        assertNull(empty.getServiceHours());
        assertTrue(empty.getBanners().isEmpty());
        assertTrue(empty.getActivities().isEmpty());
        assertEquals(4, json.readTree(json.writeValueAsString(empty)).size());
        assertTrue(validation.getValidator().validate(empty).isEmpty());
        verifyNoInteractions(properties);
        service.save(empty);
        var row = ArgumentCaptor.forClass(PropertyHomeDO.class);
        verify(homes).insert(row.capture());
        assertEquals(1L, row.getValue().getId());
        assertEquals(0L, row.getValue().getTenantId());
        assertTrue(json.readTree(row.getValue().getContentJson()).get("supportPhone").isNull());
    }

    @Test
    void saveUpdatesRowThatAppearsAfterAnInitialUpdateMiss() throws Exception {
        var home = new PropertyHomeResp();
        home.setServiceHours("10:00-17:00");
        stored(new PropertyHomeResp());
        when(homes.update(any(), any())).thenReturn(0, 1);
        service.save(home);
        var saved = ArgumentCaptor.forClass(PropertyHomeDO.class);
        verify(homes, times(2)).update(saved.capture(), any());
        assertEquals("10:00-17:00", json.readValue(saved.getValue().getContentJson(), PropertyHomeResp.class).getServiceHours());
        verify(homes, never()).insert(any(PropertyHomeDO.class));
    }

    @Test
    void publicHomeFiltersDatesDisabledTargetsAndNonPublishedIdsInOnePropertyQuery() throws Exception {
        var home = new PropertyHomeResp();
        var active = activity("active");
        active.setStartsAt(time(LocalDateTime.now().minusDays(1)));
        active.setEndsAt(time(LocalDateTime.now().plusDays(1)));
        var expired = activity("expired");
        expired.setEndsAt(time(LocalDateTime.now().minusDays(1)));
        var future = activity("future");
        future.setStartsAt(time(LocalDateTime.now().plusDays(1)));
        var disabled = activity("disabled");
        disabled.setEnabled(false);
        home.setActivities(List.of(active, expired, future, disabled));
        var disabledBanner = banner("disabled", "all", null);
        disabledBanner.setEnabled(false);
        home.setBanners(List.of(banner("all", "all", null), banner("rec", "recommended", null),
            banner("active", "activity", "active"), banner("expired", "activity", "expired"),
            banner("future", "activity", "future"), banner("disabled-act", "activity", "disabled"),
            banner("published", "property", "10"), banner("draft", "property", "11"),
            banner("deleted", "property", "12"), disabledBanner));
        stored(home);
        when(properties.selectList(any())).thenReturn(List.of(property(10, 2)));
        var result = service.getPublic();
        assertEquals(List.of("active"), result.getActivities().stream().map(PropertyHomeResp.Activity::getId).toList());
        assertEquals(List.of(10L), result.getActivities().getFirst().getPropertyIds());
        assertEquals(List.of("all", "rec", "active", "published"), result.getBanners().stream().map(PropertyHomeResp.Banner::getId).toList());
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(properties).selectList(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("status"));
        assertTrue(query.getValue().getSqlSegment().contains("deleted"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(2));
        assertEquals(4, service.getForAdmin().getActivities().size());
        assertEquals(List.of(10L, 11L, 12L), service.getForAdmin().getActivities().getFirst().getPropertyIds());
    }

    @Test
    void activityLookupRejectsMissingDisabledExpiredFutureAndMalformedInsteadOfFallingBack() throws Exception {
        var home = new PropertyHomeResp();
        var active = activity("active");
        var expired = activity("expired");
        expired.setEndsAt(time(LocalDateTime.now().minusDays(1)));
        var future = activity("future");
        future.setStartsAt(time(LocalDateTime.now().plusDays(1)));
        var disabled = activity("disabled");
        disabled.setEnabled(false);
        home.setActivities(List.of(active, expired, future, disabled));
        stored(home);
        assertEquals(List.of(10L, 11L, 12L), service.requireActivePropertyIds("active"));
        for (String id : List.of("missing", "expired", "future", "disabled", "", "../active")) {
            assertThrows(BusinessException.class, () -> service.requireActivePropertyIds(id));
        }
        verifyNoInteractions(properties);
    }

    @Test
    void savingTypedConfigAllowsDraftReferencesButRejectsMissingPropertyOrActivity() throws Exception {
        var home = new PropertyHomeResp();
        home.setSupportPhone("400-123-4567");
        home.setServiceHours("09:00-18:00");
        home.setActivities(List.of(activity("active")));
        home.setBanners(List.of(banner("event", "activity", "active"), banner("property", "property", "11")));
        when(properties.selectList(any())).thenReturn(List.of(property(10, 2), property(11, 1), property(12, 3)));
        when(homes.update(any(), any())).thenReturn(1);
        service.save(home);
        var saved = ArgumentCaptor.forClass(PropertyHomeDO.class);
        verify(homes).update(saved.capture(), any());
        var restored = json.readValue(saved.getValue().getContentJson(), PropertyHomeResp.class);
        assertEquals(List.of(10L, 11L, 12L), restored.getActivities().getFirst().getPropertyIds());
        assertEquals("active", restored.getBanners().getFirst().getTargetId());
        clearInvocations(homes);
        when(properties.selectList(any())).thenReturn(List.of(property(10, 2)));
        assertThrows(BusinessException.class, () -> service.save(home));
        when(properties.selectList(any())).thenReturn(List.of(property(10, 2), property(11, 1), property(12, 3)));
        home.getBanners().getFirst().setTargetId("missing");
        assertThrows(BusinessException.class, () -> service.save(home));
        verifyNoInteractions(homes);
    }

    @Test
    void validatesCountsIdsCopyPhoneUrlAndStrictDatesBeforeWriting() {
        var home = new PropertyHomeResp();
        home.setBanners(Collections.nCopies(6, banner("one", "all", null)));
        home.setActivities(Collections.nCopies(21, activity("one")));
        home.setSupportPhone("javascript:evil");
        home.setServiceHours("时".repeat(101));
        assertFalse(validation.getValidator().validate(home).isEmpty());
        assertThrows(BusinessException.class, () -> service.save(home));
        var b = banner("INVALID_ID", "property", "9223372036854775808");
        b.setTitle(" ");
        b.setSubtitle("字".repeat(201));
        assertFalse(validation.getValidator().validate(b).isEmpty());
        var nullLists = new PropertyHomeResp();
        nullLists.setBanners(null);
        nullLists.setActivities(null);
        assertFalse(validation.getValidator().validate(nullLists).isEmpty());
        assertThrows(BusinessException.class, () -> service.save(nullLists));
        assertThrows(BusinessException.class, () -> service.save(null));
        for (String url : List.of("javascript:alert(1)", "https://example.com/a b", "https://", "http://?foo", "https://example.com/" + "a".repeat(1000))) {
            var imageBanner = banner("image", "all", null);
            imageBanner.setImageUrl(url);
            assertFalse(validation.getValidator().validate(imageBanner).isEmpty());
        }
        var a = activity("event");
        a.setPropertyIds(Collections.nCopies(101, 10L));
        a.setDescription("字".repeat(2001));
        assertFalse(validation.getValidator().validate(a).isEmpty());
        a.setPropertyIds(List.of(0L));
        assertFalse(validation.getValidator().validate(a).isEmpty());
        a.setPropertyIds(List.of(10L));
        a.setDescription(null);
        for (String start : List.of("2026-02-30 10:00:00", "2026-01-01T10:00:00", "2026-1-1 10:00:00", "")) {
            a.setStartsAt(start);
            assertFalse(validation.getValidator().validate(a).isEmpty());
        }
        a.setStartsAt("2026-06-01 10:00:00");
        a.setEndsAt("2026-06-01 09:59:59");
        assertFalse(validation.getValidator().validate(a).isEmpty());
        a.setEndsAt(a.getStartsAt());
        assertFalse(validation.getValidator().validate(a).isEmpty());
        a.setEndsAt("2026-06-01 10:00:01");
        assertTrue(validation.getValidator().validate(a).isEmpty());
        assertTrue(a.activeAt(LocalDateTime.of(2026, 6, 1, 10, 0)));
        assertFalse(a.activeAt(LocalDateTime.of(2026, 6, 1, 10, 0, 1)));
        home.setBanners(List.of(banner("same", "all", null), banner("same", "all", null)));
        home.setActivities(List.of(activity("same"), activity("same")));
        assertFalse(home.isIdsUnique());
        verifyNoInteractions(homes, properties);
    }

    @Test
    void bannerTargetsAndValidInputsFollowTheFixedContract() throws Exception {
        for (var b : List.of(banner("all", "all", null), banner("rec", "recommended", null),
            banner("event", "activity", "summer-2026"), banner("room", "property", "123"))) {
            b.setImageUrl("https://example.com/image.png");
            assertTrue(validation.getValidator().validate(b).isEmpty());
        }
        for (var b : List.of(banner("all", "all", "unexpected"), banner("rec", "recommended", "1"),
            banner("event", "activity", null), banner("room", "property", "0"),
            banner("room", "property", "1.1"), banner("bad", "other", null))) {
            assertFalse(validation.getValidator().validate(b).isEmpty());
        }
        var a = json.readValue("{\"id\":\"ids\",\"title\":\"活动\",\"propertyIds\":[\"10\",11]}", PropertyHomeResp.Activity.class);
        assertEquals(List.of(10L, 11L), a.getPropertyIds());
    }

    @Test
    void controllerUsesExistingPermissionsAndValidatesPutBeforeService() throws Exception {
        assertEquals("system:property:list", PropertyHomeController.class.getMethod("homeForAdmin")
            .getAnnotation(SaCheckPermission.class).value()[0]);
        assertEquals("system:property:update", PropertyHomeController.class.getMethod("save", PropertyHomeResp.class)
            .getAnnotation(SaCheckPermission.class).value()[0]);
        assertEquals("system:property:status", PropertyAdminController.class.getMethod("updateRecommended", Long.class, boolean.class)
            .getAnnotation(SaCheckPermission.class).value()[0]);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var target = mock(PropertyHomeService.class);
            var mvc = MockMvcBuilders.standaloneSetup(new PropertyHomeController(target)).setValidator(validator).build();
            mvc.perform(put("/system/property/home").contentType("application/json")
                .content("{\"supportPhone\":\"bad phone\",\"banners\":[],\"activities\":[]}")).andExpect(status().isBadRequest());
            verifyNoInteractions(target);
            mvc.perform(put("/system/property/home").contentType("application/json")
                .content("{\"supportPhone\":null,\"serviceHours\":null,\"banners\":[],\"activities\":[]}")).andExpect(status().isOk());
            verify(target).save(any());
        }
    }

    @Test
    void homeWhitelistAllowsAnonymousGetButPostStillRequiresMiniappLogin() throws Exception {
        var users = mock(com.guardfind.backend.miniapp.mapper.MiniappUserMapper.class);
        var registry = mock(org.springframework.web.servlet.config.annotation.InterceptorRegistry.class);
        when(registry.addInterceptor(any())).thenAnswer(invocation ->
            new org.springframework.web.servlet.config.annotation.InterceptorRegistration(invocation.getArgument(0)));
        new com.guardfind.backend.miniapp.config.MiniappWebConfiguration(users, new TenantExtensionProperties())
            .addInterceptors(registry);
        var captured = ArgumentCaptor.forClass(org.springframework.web.servlet.HandlerInterceptor.class);
        verify(registry, times(2)).addInterceptor(captured.capture());
        var interceptor = captured.getAllValues().getFirst();
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/miniapp/property/home");
        request.setServletPath("/miniapp/property/home");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        try (var auth = mockStatic(com.guardfind.backend.miniapp.util.StpMiniappUtil.class)) {
            auth.when(() -> com.guardfind.backend.miniapp.util.StpMiniappUtil.getLoginId(null)).thenReturn(null);
            com.guardfind.backend.miniapp.context.MiniappUserContextHolder.setUserId(99L);
            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertNull(com.guardfind.backend.miniapp.context.MiniappUserContextHolder.getUserId());
            request.setMethod("POST");
            var error = assertThrows(com.guardfind.backend.miniapp.exception.MiniappBizException.class,
                () -> interceptor.preHandle(request, response, new Object()));
            assertEquals("401", error.getCode());
            verifyNoInteractions(users);
        } finally {
            com.guardfind.backend.miniapp.context.MiniappUserContextHolder.clear();
        }
    }

    @Test
    void homeServiceRejectsOtherTenantsBeforeAnyGlobalPropertyOrHomeAccess() {
        tenantContext.when(TenantContextHolder::isTenantEnabled).thenReturn(true);
        tenantContext.when(TenantContextHolder::getTenantId).thenReturn(9L);
        assertThrows(BusinessException.class, service::getForAdmin);
        assertThrows(BusinessException.class, service::getPublic);
        assertThrows(BusinessException.class, () -> service.save(new PropertyHomeResp()));
        assertThrows(BusinessException.class, () -> service.requireActivePropertyIds("active"));
        verifyNoInteractions(homes, properties);
    }
}
