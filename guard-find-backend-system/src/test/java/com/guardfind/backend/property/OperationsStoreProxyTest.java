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

import com.guardfind.backend.property.operations.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsStoreProxyTest {
    @Test
    void scheduledDispatchUsesTheTargetJdbcTemplateThroughACglibRepositoryProxy() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:proxy" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE gf_property_notification(id BIGINT PRIMARY KEY,state VARCHAR(30),result VARCHAR(1000),update_time TIMESTAMP)");
            jdbc.update("INSERT INTO gf_property_notification VALUES(1,'SENDING',NULL,DATEADD('MINUTE',-10,CURRENT_TIMESTAMP))");
            var factory = new ProxyFactory(new OperationsStore(jdbc));
            factory.setProxyTargetClass(true);
            factory.addAdvice((MethodInterceptor)invocation -> invocation.proceed());
            var store = (OperationsStore)factory.getProxy();
            assertThat(AopUtils.isCglibProxy(store)).isTrue();
            var notifications = new PropertyNotificationService(store, mock(PropertyOperationsService.class), new DataSourceTransactionManager(dataSource));
            assertThatCode(notifications::dispatch).doesNotThrowAnyException();
            assertThat(jdbc.queryForObject("SELECT state FROM gf_property_notification WHERE id=1", String.class))
                .isEqualTo("UNKNOWN");
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}
