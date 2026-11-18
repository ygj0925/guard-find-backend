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

package com.guardfind.backend.property.lease;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.miniapp.mapper.MiniappUserMapper;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import com.guardfind.backend.property.controller.PropertyAdminController;
import com.guardfind.backend.property.mapper.PropertyMapper;
import com.guardfind.backend.property.operations.OperationsStore;
import com.guardfind.backend.property.operations.PropertyOperationsService;
import com.guardfind.backend.property.service.PropertyHomeService;
import com.guardfind.backend.property.service.impl.PropertyInteractionServiceImpl;
import com.guardfind.backend.property.service.impl.PropertyServiceImpl;
import com.guardfind.backend.property.support.RegionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PropertyLeaseWiringTest {
    @Test
    void adminControllerStartsWithTheManagedIdentityService() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var beans = context.getBeanFactory();
            for (Class<?> dependency : new Class<?>[]{PropertyMapper.class, MiniappUserMapper.class,
                PropertyInteractionServiceImpl.class, PropertyHomeService.class, OperationsStore.class,
                PropertyOperationsService.class, MiniappNoticeService.class, PlatformTransactionManager.class}) {
                beans.registerSingleton(dependency.getSimpleName(), mock(dependency));
            }
            beans.registerSingleton("objectMapper", new ObjectMapper());
            context.register(PropertyAdminController.class, PropertyServiceImpl.class, RegionRepository.class,
                PropertyLeaseService.class, PropertySigningProfileService.class, PropertyIdentityVerificationService.class,
                UnavailableIdentityVerificationProvider.class);
            context.refresh();

            assertThat(context.getBean(PropertyAdminController.class)).isNotNull();
            assertThat(beans.getDependenciesForBean("propertyLeaseService")).contains("propertyIdentityVerificationService");
        }
    }
}
