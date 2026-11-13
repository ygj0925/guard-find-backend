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

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.property.controller.PropertyController;
import com.guardfind.backend.property.mapper.PropertyMapper;
import com.guardfind.backend.property.model.entity.PropertyDO;
import com.guardfind.backend.property.model.query.PropertyQuery;
import com.guardfind.backend.property.model.resp.PropertyResp;
import com.guardfind.backend.property.service.PropertyInteractionService;
import com.guardfind.backend.property.service.PropertyService;
import com.guardfind.backend.property.service.impl.PropertyServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开列表搜索:真实 MyBatis-Plus/H2 SQL 与 Spring 查询绑定,不启动完整应用。
 */
class PropertySearchTest {

    private static final LocalDateTime OLDER = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime NEWER = OLDER.plusDays(1);
    private Connection connection;
    private SqlSession session;
    private PropertyServiceImpl service;
    private MiniappUserMapper users;
    private PropertyInteractionService interactions;
    private com.guardfind.backend.property.service.PropertyHomeService home;

    @BeforeEach
    void setup() throws Exception {
        MiniappUserContextHolder.clear();
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:property_search_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        connection = dataSource.getConnection();
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("property-search", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.setGlobalConfig(configuration, new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig()
            .setLogicDeleteField("deleted")));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(PropertyMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        // 从房源映射元数据建测试表,覆盖继承字段;不依赖迁移或其他业务表。
        var table = TableInfoHelper.getTableInfo(PropertyDO.class);
        String columns = table.getFieldList()
            .stream()
            .map(field -> field.getColumn() + " " + sqlType(field.getPropertyType()) + ("deleted".equals(field
                .getColumn()) ? " DEFAULT 0" : ""))
            .collect(Collectors.joining(", "));
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE gf_property (" + table
                .getKeyColumn() + " BIGINT PRIMARY KEY, " + columns + ")");
        }
        session = factory.openSession(true);
        users = mock(MiniappUserMapper.class);
        interactions = mock(PropertyInteractionService.class);
        home = mock(com.guardfind.backend.property.service.PropertyHomeService.class);
        service = new PropertyServiceImpl(session.getMapper(PropertyMapper.class), users, interactions, home);

        insert(1, "泊之湾南向房源", "普通小区", "普通地址", "100.00", OLDER, 2);
        insert(2, "普通房源", "泊之湾花园", "普通地址", "200.00", NEWER, 2);
        insert(3, "普通房源", "普通小区", "上海市泊之湾路", "200.00", NEWER, 2);
        insert(4, "普通房源", "普通小区", "普通地址", "200.00", OLDER, 2);
        insert(5, "普通房源", "普通小区", "普通地址", "300.00", NEWER.plusDays(1), 2);
        insert(6, "普通房源", "普通小区", "普通地址", "0.00", OLDER.minusDays(1), 2);
        insert(90, "泊之湾草稿", "泊之湾花园", "泊之湾路", "200.00", NEWER.plusDays(3), 1);
        insert(91, "泊之湾下架", "泊之湾花园", "泊之湾路", "200.00", NEWER.plusDays(3), 3);
        insert(92, "泊之湾删除", "泊之湾花园", "泊之湾路", "200.00", NEWER.plusDays(3), 2);
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE gf_property SET deleted = 1 WHERE id = 92");
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        MiniappUserContextHolder.clear();
        if (session != null) {
            session.close();
        }
        if (connection != null) {
            connection.close();
        }
    }

    private static String sqlType(Class<?> type) {
        if (type == BigDecimal.class) {
            return "DECIMAL(12, 2)";
        }
        if (type == LocalDateTime.class || type == java.util.Date.class) {
            return "TIMESTAMP";
        }
        if (type == LocalDate.class) {
            return "DATE";
        }
        if (type == Long.class || type == long.class) {
            return "BIGINT";
        }
        if (type == Integer.class || type == int.class || type == Boolean.class || type == boolean.class) {
            return "INT";
        }
        return "VARCHAR(4000)";
    }

    private void insert(long id,
                        String title,
                        String community,
                        String address,
                        String price,
                        LocalDateTime created,
                        int status) throws Exception {
        try (var statement = connection
            .prepareStatement("INSERT INTO gf_property " + "(id, title, community, address, city, property_type, price_type, rental_mode, price, create_time, status, deleted) " + "VALUES (?, ?, ?, ?, '上海市', 'house', 'rent', 'whole', ?, ?, ?, 0)")) {
            statement.setLong(1, id);
            statement.setString(2, title);
            statement.setString(3, community);
            statement.setString(4, address);
            statement.setBigDecimal(5, new BigDecimal(price));
            statement.setTimestamp(6, Timestamp.valueOf(created));
            statement.setInt(7, status);
            statement.executeUpdate();
        }
    }

    private List<Long> ids(PropertyQuery query) {
        return service.page(query, null).getList().stream().map(PropertyResp::getId).toList();
    }

    @Test
    void keywordMatchesTitleCommunityAndAddressButNeverDraftOfflineOrDeleted() {
        var query = new PropertyQuery();
        query.setKeyword("  泊之湾  ");
        assertEquals(List.of(3L, 2L, 1L), ids(query));
        assertEquals(3, service.page(query, null).getTotal());
        verifyNoInteractions(users, interactions);
    }

    @Test
    void combinesTrimmedCityTypePriceTypeAndRentalModeWithGroupedKeyword() throws Exception {
        for (int id = 20; id <= 23; id++) {
            insert(id, "泊之湾", "泊之湾", "泊之湾", "200.00", NEWER.plusDays(4), 2);
        }
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE gf_property SET city = '北京市' WHERE id = 20");
            statement.executeUpdate("UPDATE gf_property SET property_type = 'apartment' WHERE id = 21");
            statement.executeUpdate("UPDATE gf_property SET price_type = 'sale' WHERE id = 22");
            statement.executeUpdate("UPDATE gf_property SET rental_mode = 'shared' WHERE id = 23");
        }
        var query = new PropertyQuery();
        query.setCity(" 上海市 ");
        query.setPropertyType(" house ");
        query.setPriceType(" rent ");
        query.setRentalMode(" whole ");
        query.setKeyword("泊之湾");
        assertEquals(List.of(3L, 2L, 1L), ids(query));
        query.setRentalMode(" shared ");
        assertEquals(List.of(23L), ids(query));
        query.setRentalMode("");
        query.setPriceType(" sale ");
        assertEquals(List.of(22L), ids(query));
    }

    @Test
    void priceBoundsAreInclusiveAllowZeroAndSupportSingleSidedRanges() throws Exception {
        var query = new PropertyQuery();
        query.setMinPrice(new BigDecimal("200.00"));
        query.setMaxPrice(new BigDecimal("200.00"));
        assertEquals(List.of(3L, 2L, 4L), ids(query));
        query.setMaxPrice(null);
        assertEquals(List.of(5L, 3L, 2L, 4L), ids(query));
        query.setMinPrice(null);
        query.setMaxPrice(new BigDecimal("200.00"));
        assertEquals(List.of(3L, 2L, 4L, 1L, 6L), ids(query));
        query.setMinPrice(BigDecimal.ZERO);
        query.setMaxPrice(BigDecimal.ZERO);
        assertEquals(List.of(6L), ids(query));
        insert(50, "最高价", "普通小区", "普通地址", "9999999999.99", NEWER, 2);
        query.setMinPrice(new BigDecimal("9999999999.99"));
        query.setMaxPrice(new BigDecimal("9999999999.99"));
        assertEquals(List.of(50L), ids(query));
    }

    @Test
    void newestAndPriceSortsUseDescendingTimeAndIdAsStableTieBreakers() throws Exception {
        // 最贵房源最旧,确保价格降序不能退化为 newest。
        insert(7, "高价旧房源", "普通小区", "普通地址", "400.00", OLDER.minusDays(2), 2);
        var query = new PropertyQuery();
        assertEquals("newest", query.getSort());
        assertEquals(List.of(5L, 3L, 2L, 4L, 1L, 6L, 7L), ids(query));
        query.setSort("priceAsc");
        assertEquals(List.of(6L, 1L, 3L, 2L, 4L, 5L, 7L), ids(query));
        query.setSort("priceDesc");
        assertEquals(List.of(7L, 5L, 3L, 2L, 4L, 1L, 6L), ids(query));
        query.setSort("priceAsc");
        var pagination = new PageQuery();
        pagination.setSize(1);
        for (int page = 3; page <= 5; page++) {
            pagination.setPage(page);
            var result = service.page(query, pagination);
            assertEquals(7, result.getTotal());
            assertEquals(List.of(List.of(3L, 2L, 4L).get(page - 3)), result.getList()
                .stream()
                .map(PropertyResp::getId)
                .toList());
        }
    }

    @Test
    void blankOptionalFiltersAndPaginationDefaultsAndClampsRemainUnchanged() throws Exception {
        var query = new PropertyQuery();
        query.setCity(" ");
        query.setPropertyType(" ");
        query.setPriceType(" ");
        query.setRentalMode(" ");
        query.setKeyword(" ");
        assertEquals(6, service.page(query, null).getTotal());
        var pagination = new PageQuery();
        pagination.setPage(0);
        pagination.setSize(0);
        assertEquals(List.of(5L), service.page(query, pagination).getList().stream().map(PropertyResp::getId).toList());
        for (int id = 100; id < 205; id++) {
            insert(id, "普通房源", "普通小区", "普通地址", "100.00", NEWER, 2);
        }
        session.clearCache();
        var defaultPage = service.page(query, null);
        assertEquals(111, defaultPage.getTotal());
        assertEquals(10, defaultPage.getList().size());
        pagination.setSize(1000);
        assertEquals(100, service.page(query, pagination).getList().size());
    }

    @Test
    void percentUnderscoreEscapeAndSqlLookingKeywordsAreLiteralParameters() throws Exception {
        insert(20, "literal%here", "普通小区", "普通地址", "200.00", NEWER, 2);
        insert(21, "普通房源", "literal_here", "普通地址", "200.00", NEWER, 2);
        insert(22, "普通房源", "普通小区", "literal!here", "200.00", NEWER, 2);
        insert(23, "literalXhere", "普通小区", "普通地址", "200.00", NEWER, 2);
        insert(24, "literal%_!here", "普通小区", "普通地址", "200.00", NEWER, 2);
        insert(25, "' OR 1=1 --", "普通小区", "普通地址", "200.00", NEWER, 2);
        var query = new PropertyQuery();
        query.setKeyword("%");
        assertEquals(List.of(24L, 20L), ids(query));
        query.setKeyword("_");
        assertEquals(List.of(24L, 21L), ids(query));
        query.setKeyword("!");
        assertEquals(List.of(24L, 22L), ids(query));
        query.setKeyword("%_!");
        assertEquals(List.of(24L), ids(query));
        query.setKeyword("' OR 1=1 --");
        assertEquals(List.of(25L), ids(query));
    }

    @Test
    void roomShortUnitRecommendationAndActivityFiltersComposeWithPaging() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE gf_property SET price_unit = 'month', recommended = 0");
            statement.executeUpdate("UPDATE gf_property SET rental_mode = 'short', price_unit = 'day', recommended = 1 WHERE id IN (2, 3, 90, 91, 92)");
            statement.executeUpdate("UPDATE gf_property SET rental_mode = 'room', recommended = 1 WHERE id = 4");
        }
        var query = new PropertyQuery();
        query.setRecommended(true);
        assertEquals(List.of(3L, 2L, 4L), ids(query));
        query.setPriceUnit("day");
        query.setRentalMode("short");
        assertEquals(List.of(3L, 2L), ids(query));
        when(home.requireActivePropertyIds("summer")).thenReturn(List.of(2L, 3L, 4L, 90L, 91L, 92L));
        query.setActivityId("summer");
        var paging = new PageQuery();
        paging.setPage(2);
        paging.setSize(1);
        var result = service.page(query, paging);
        assertEquals(2, result.getTotal());
        assertEquals(2L, result.getList().getFirst().getId());
        assertTrue(result.getList().getFirst().isRecommended());
        assertEquals("day", result.getList().getFirst().getPriceUnit());
        query.setRentalMode("room");
        query.setPriceUnit("month");
        assertEquals(List.of(4L), ids(query));
        query.setRecommended(false);
        query.setRentalMode(null);
        query.setPriceUnit(null);
        query.setActivityId(null);
        assertEquals(6, service.page(query, null).getTotal());
        when(home.requireActivePropertyIds("empty")).thenReturn(List.of());
        query.setActivityId("empty");
        assertEquals(0, service.page(query, null).getTotal());
        when(home.requireActivePropertyIds("expired")).thenThrow(new top.continew.starter.core.exception.BusinessException("活动失效"));
        query.setActivityId("expired");
        org.junit.jupiter.api.Assertions.assertThrows(top.continew.starter.core.exception.BusinessException.class,
            () -> service.page(query, null));
    }

    @Test
    void springBindsTrimmedQueryAndEmptyPricesToNull() throws Exception {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var target = mock(PropertyService.class);
            var response = new PageResp<PropertyResp>();
            response.setList(List.of());
            when(target.page(any(), any())).thenReturn(response);
            var mvc = MockMvcBuilders.standaloneSetup(new PropertyController(target)).setValidator(validator).build();
            mvc.perform(get("/miniapp/property/list").param("city", " 上海市 ")
                .param("propertyType", " house ")
                .param("priceType", " rent ")
                .param("rentalMode", " shared ")
                .param("priceUnit", "month")
                .param("recommended", "true")
                .param("activityId", "summer-2026")
                .param("keyword", " " + "湾".repeat(64) + " ")
                .param("minPrice", "")
                .param("maxPrice", "")).andExpect(status().isOk());
            var query = ArgumentCaptor.forClass(PropertyQuery.class);
            verify(target).page(query.capture(), any());
            assertEquals("上海市", query.getValue().getCity());
            assertEquals("house", query.getValue().getPropertyType());
            assertEquals("rent", query.getValue().getPriceType());
            assertEquals("shared", query.getValue().getRentalMode());
            assertEquals("month", query.getValue().getPriceUnit());
            assertTrue(query.getValue().getRecommended());
            assertEquals("summer-2026", query.getValue().getActivityId());
            assertEquals("湾".repeat(64), query.getValue().getKeyword());
            assertEquals("newest", query.getValue().getSort());
            assertNull(query.getValue().getMinPrice());
            assertNull(query.getValue().getMaxPrice());
            var zero = new PropertyQuery();
            zero.setMinPrice(BigDecimal.ZERO);
            zero.setMaxPrice(new BigDecimal("9999999999.99"));
            zero.setPropertyType("");
            zero.setPriceType("");
            zero.setRentalMode("");
            assertTrue(validator.validate(zero).isEmpty());
        }
    }

    @Test
    void invalidEnumsRangeSortKeywordLengthAndDecimalCapacityAreRejectedBeforeService() throws Exception {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var target = mock(PropertyService.class);
            var mvc = MockMvcBuilders.standaloneSetup(new PropertyController(target)).setValidator(validator).build();
            var invalid = List.of(Map.of("propertyType", "office"), Map.of("priceType", "lease"), Map
                .of("rentalMode", "other"), Map.of("priceUnit", "year"), Map.of("activityId", ""), Map.of("activityId", "Bad_ID"),
                Map.of("activityId", "a".repeat(41)), Map.of("recommended", "maybe"), Map.of("sort", "price"), Map.of("sort", ""), Map.of("sort", " "), Map
                    .of("sort", "price DESC; DROP TABLE gf_property"), Map.of("keyword", "湾".repeat(65)), Map
                        .of("minPrice", "-0.01"), Map.of("maxPrice", "-0.01"), Map.of("minPrice", "1.001"), Map
                            .of("maxPrice", "2.123"), Map.of("minPrice", "10000000000"), Map
                                .of("maxPrice", "9999999999.999"), Map.of("maxPrice", "10000000000"), Map
                                    .of("minPrice", "200", "maxPrice", "100"), Map.of("minPrice", "not-a-number"));
            for (var params : invalid) {
                var request = get("/miniapp/property/list");
                params.forEach((name, value) -> request.param(name, value));
                mvc.perform(request).andExpect(status().isBadRequest());
            }
            verifyNoInteractions(target);
            var query = new PropertyQuery();
            query.setMinPrice(BigDecimal.ONE);
            query.setMaxPrice(BigDecimal.ZERO);
            assertFalse(validator.validate(query).isEmpty());
        }
    }
}
