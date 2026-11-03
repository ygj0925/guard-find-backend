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

package com.guardfind.backend.miniapp.context;

import com.guardfind.backend.miniapp.exception.MiniappBizException;

/** Request scoped identity. Always removed by the interceptor's finally block. */
public final class MiniappUserContextHolder {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    private MiniappUserContextHolder() {
    }

    public static void setUserId(Long id) {
        if (id == null)
            USER.remove();
        else
            USER.set(id);
    }

    public static Long getUserId() {
        return USER.get();
    }

    public static Long getUserIdRequired() {
        Long id = USER.get();
        if (id == null)
            throw new MiniappBizException("401", "请先登录");
        return id;
    }

    public static void clear() {
        USER.remove();
    }
}
