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

package com.guardfind.backend.miniapp.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;
import top.continew.starter.web.model.R;

/**
 * C 端业务异常处理
 *
 * <p>
 * 只负责 {@link MiniappBizException}：把业务码原样写入响应信封的 {@code code} 字段，
 * 使客户端可以对"疑似重复""规则拒绝""限流""账号锁定"等分支做专门交互。
 * 其余异常仍由项目既有的 {@code GlobalExceptionHandler} / {@code GlobalSaTokenExceptionHandler} 处理，
 * 本通知器不参与，也不改变它们的语义。
 * </p>
 *
 * @author guardfind
 * @since 2026/9/25
 */
@Slf4j
@Order(98)
@RestControllerAdvice
public class MiniappExceptionHandler {

    @ExceptionHandler(MiniappBizException.class)
    public R handleMiniappBizException(MiniappBizException e, HttpServletRequest request) {
        log.warn("[{}] {} 业务拒绝 code={} msg={}", request.getMethod(), request.getRequestURI(), e.getCode(), e
            .getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }
}
