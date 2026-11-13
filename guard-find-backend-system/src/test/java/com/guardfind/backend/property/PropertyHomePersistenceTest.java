package com.guardfind.backend.property;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.common.config.TenantExtensionProperties;
import com.guardfind.backend.property.mapper.PropertyHomeMapper;
import com.guardfind.backend.property.mapper.PropertyMapper;
import com.guardfind.backend.property.model.entity.PropertyHomeDO;
import com.guardfind.backend.property.model.resp.PropertyHomeResp;
import com.guardfind.backend.property.service.PropertyHomeService;
import jakarta.validation.Validation;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PropertyHomePersistenceTest {
    @Test
    void singletonInsertUpdateAndTenantLineIsolationUseRealH2MapperWithoutSharedServices() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:property_home_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (var connection = source.getConnection(); var validation = Validation.buildDefaultValidatorFactory();
             var tenantContext = mockStatic(TenantContextHolder.class)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE gf_property_home (id BIGINT PRIMARY KEY CHECK(id = 1), tenant_id BIGINT NOT NULL, content_json VARCHAR(100000) NOT NULL)");
            }
            var currentTenant = new AtomicLong(0);
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("home-test", new JdbcTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            var interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
                @Override
                public Expression getTenantId() {
                    return new LongValue(currentTenant.get());
                }
            }));
            config.addInterceptor(interceptor);
            config.addMapper(PropertyHomeMapper.class);
            var factory = new MybatisSqlSessionFactoryBuilder().build(config);
            try (var session = factory.openSession(true)) {
                var mapper = session.getMapper(PropertyHomeMapper.class);
                var service = new PropertyHomeService(mapper, mock(PropertyMapper.class), new ObjectMapper(),
                    validation.getValidator(), new TenantExtensionProperties());
                assertTrue(service.getPublic().getActivities().isEmpty());
                var home = new PropertyHomeResp();
                home.setSupportPhone("13800000000");
                service.save(home);
                assertEquals("13800000000", service.getForAdmin().getSupportPhone());
                home.setSupportPhone(null);
                home.setServiceHours("全天");
                service.save(home);
                assertNull(service.getForAdmin().getSupportPhone());
                assertEquals("全天", service.getForAdmin().getServiceHours());
                assertEquals(1, mapper.selectCount(Wrappers.<PropertyHomeDO>lambdaQuery()));
                currentTenant.set(9);
                session.clearCache();
                assertNull(mapper.selectById(1L));
                assertEquals(0, mapper.update(null, Wrappers.<PropertyHomeDO>lambdaUpdate()
                    .eq(PropertyHomeDO::getId, 1L).set(PropertyHomeDO::getContentJson, "{}")));
                currentTenant.set(0);
                session.clearCache();
                assertEquals("全天", service.getForAdmin().getServiceHours());
            }
        }
    }
}
