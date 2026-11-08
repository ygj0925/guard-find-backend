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

package com.guardfind.backend.config.satoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.exception.NotPermissionException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.guardfind.backend.common.context.UserContext;
import com.guardfind.backend.common.context.UserContextHolder;

class SaExtensionInterceptorTest {

    @AfterEach
    void tearDown() {
        UserContextHolder.clearContext();
    }

    @Test
    void staleContextFromPreviousRequestIsDroppedBeforeAuth() throws Exception {
        UserContextHolder.setContext(user(909L, 909993815497003053L), false);
        AtomicReference<Object> seenByAuth = new AtomicReference<>("unset");
        SaExtensionInterceptor interceptor = new SaExtensionInterceptor(handler -> {
            seenByAuth.set(heldContext());
            throw new NotPermissionException("system:oa:approval:action");
        });

        assertThatThrownBy(() -> interceptor
            .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(NotPermissionException.class);

        assertThat(seenByAuth.get()).isNull();
        assertThat(heldContext()).isNull();
    }

    @Test
    void contextLoadedDuringRejectedAuthDoesNotLeak() throws Exception {
        SaExtensionInterceptor interceptor = new SaExtensionInterceptor(handler -> {
            UserContextHolder.setContext(user(101L, 0L), false);
            throw new NotPermissionException("system:oa:approval:action");
        });

        assertThatThrownBy(() -> interceptor
            .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
            .isInstanceOf(NotPermissionException.class);

        assertThat(heldContext()).isNull();
    }

    private static UserContext user(long id, long tenantId) {
        UserContext context = new UserContext();
        context.setId(id);
        context.setTenantId(tenantId);
        return context;
    }

    private static Object heldContext() {
        try {
            Field field = UserContextHolder.class.getDeclaredField("CONTEXT_HOLDER");
            field.setAccessible(true);
            return ((ThreadLocal<?>)field.get(null)).get();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
