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

import com.guardfind.backend.property.model.query.PropertyMapQuery;
import com.guardfind.backend.property.model.req.PropertyReq;
import com.guardfind.backend.property.operations.OperationsStore;
import com.guardfind.backend.property.service.PropertyMapService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PropertyMapTest {
    JdbcTemplate jdbc;
    PropertyMapService service;

    @BeforeAll
    static void utilities() {
        PropertyOperationsTest.utilities();
    }

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:map" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE gf_property(id BIGINT PRIMARY KEY,title VARCHAR(100),city VARCHAR(50),district VARCHAR(50),community VARCHAR(100),price DECIMAL(12,2),price_type VARCHAR(10),price_unit VARCHAR(10),status INT DEFAULT 2,deleted BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE gf_property_operation(property_id BIGINT PRIMARY KEY,availability VARCHAR(30))");
        String migration = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_location_search.sql"));
        // H2 does not support MySQL's comma-separated ADD COLUMN clauses.
        migration = migration.replace(", ADD COLUMN", "; ALTER TABLE gf_property ADD COLUMN");
        for (String sql : migration.split(";"))
            if (!sql.isBlank())
                jdbc.execute(sql);
        var proxy = new ProxyFactory(new OperationsStore(jdbc));
        proxy.setProxyTargetClass(true);
        service = new PropertyMapService((OperationsStore)proxy.getProxy());
        for (long i = 1; i <= 4; i++)
            jdbc.update("INSERT INTO gf_property(id,title,city,district,community,price,price_type,price_unit,public_latitude,public_longitude,location_label) VALUES(?,'房源','上海市','浦东新区','花园',2000,'rent','month',31.2,121.5,'小区南门')", i);
        jdbc.update("UPDATE gf_property SET status=1 WHERE id=2");
        jdbc.update("UPDATE gf_property SET deleted=1 WHERE id=3");
        jdbc.update("INSERT INTO gf_property_operation VALUES(4,'RENTED')");
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void publicMapOnlyShowsAvailableAndLocatedHomes() {
        var result = service.markers(new PropertyMapQuery());
        var list = (List<?>)result.get("list");
        assertThat(list).hasSize(1);
        assertThat(((Map<?, ?>)list.get(0)).get("id")).isEqualTo("1");
        assertThat(list.get(0).toString()).doesNotContain("contact", "address", "userId");
        jdbc.update("UPDATE gf_property SET public_latitude=NULL WHERE id=1");
        assertThat((List<?>)service.markers(new PropertyMapQuery()).get("list")).isEmpty();
    }

    @Test
    void mapBoundsAndLiteralSearchAreEnforced() {
        var q = new PropertyMapQuery();
        q.setSouth(32.0);
        q.setNorth(33.0);
        q.setWest(121.0);
        q.setEast(122.0);
        assertThat((List<?>)service.markers(q).get("list")).isEmpty();
        q = new PropertyMapQuery();
        q.setKeyword("%");
        assertThat((List<?>)service.markers(q).get("list")).isEmpty();
        q.setKeyword("花园");
        q.setCity("上海市");
        assertThat(service.communities(q)).hasSize(1);
        assertThat(((Number)service.communities(q).get(0).get("propertyCount")).longValue()).isEqualTo(1);
        q.setSouth(Double.NaN);
        var invalid = q;
        assertThatThrownBy(() -> service.markers(invalid)).hasMessageContaining("地图范围");
    }

    @Test
    void truncationIsExplicitAndPriceFilterDoesNotLeakUnavailableListings() {
        for (long i = 10; i < 215; i++)
            jdbc.update("INSERT INTO gf_property(id,title,city,community,price,price_type,price_unit,public_latitude,public_longitude,location_label) VALUES(?,'房源','上海市','花园',3000,'rent','month',31.2,121.5,'入口')", i);
        var result = service.markers(new PropertyMapQuery());
        assertThat((List<?>)result.get("list")).hasSize(200);
        assertThat(result.get("truncated")).isEqualTo(true);
        var q = new PropertyMapQuery();
        q.setMaxPrice(2500L);
        assertThat((List<?>)service.markers(q).get("list")).hasSize(1);
    }

    @Test
    void partialAndNonFiniteCoordinatePairsAreRejected() {
        var req = new PropertyReq();
        req.setPublicLatitude(31.2);
        assertThat(req.isLocationValid()).isFalse();
        req.setPublicLongitude(121.5);
        req.setLocationLabel("小区入口");
        assertThat(req.isLocationValid()).isTrue();
        req.setPublicLongitude(Double.NaN);
        assertThat(req.isLocationValid()).isFalse();
        req.setPublicLatitude(0.0);
        req.setPublicLongitude(0.0);
        assertThat(req.isLocationValid()).isTrue();
    }

    @Test
    void sameNamedCommunitiesAreSeparatedByDistrictAndPriceUnit() {
        jdbc.update("UPDATE gf_property SET status=2,district='静安区',price_unit='day' WHERE id=2");
        var query = new PropertyMapQuery();
        query.setCity("上海市");
        query.setDistrict("浦东新区");
        query.setCommunity("花园");
        assertThat((List<?>)service.markers(query).get("list")).hasSize(1);
        query.setDistrict("静安区");
        query.setPriceUnit("month");
        assertThat((List<?>)service.markers(query).get("list")).isEmpty();
        query.setPriceUnit("day");
        assertThat((List<?>)service.markers(query).get("list")).hasSize(1);
    }
}
