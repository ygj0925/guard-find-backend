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

/**
 * C 端可枚举业务异常
 *
 * <p>
 * ContiNew 的响应信封 {@code R.code} 是字符串，且框架自带的异常只会产出
 * 0/400/401/403/404/405/500 这一组"状态码式"取值，客户端无法据此区分"疑似重复档案"
 * "今日发布次数已满"这类需要专门交互的业务分支。本异常用于携带这些细分业务码，
 * 由 {@code MiniappExceptionHandler} 原样写入响应信封的 {@code code} 字段。
 * </p>
 *
 * <p>
 * 取值必须与 {@code doc/天下无拐接口契约v1.md} 的错误码表一致，且不得与框架已占用的
 * 状态码式取值（"0"、"400"、"401"、"403"、"404"、"405"、"500"）冲突。
 * </p>
 *
 * @author guardfind
 * @since 2026/9/25
 */
public class MiniappBizException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务码（字符串，随响应信封返回）
     */
    private final String code;

    public MiniappBizException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 疑似重复档案（响应 msg 中列出疑似命中的档案编号，客户端凭此弹确认框）
     */
    public static final String DUPLICATE_CASE = "4005";

    /**
     * 业务规则拒绝（缺体貌信息、未勾选合规告知、图片超限、非本人等）
     */
    public static final String RULE_REJECTED = "4003";

    /**
     * 状态冲突（非法状态跃迁、目标档案状态已变）
     */
    public static final String STATE_CONFLICT = "4002";

    /**
     * 账号锁定（连续密码错误达阈值）
     */
    public static final String ACCOUNT_LOCKED = "4006";

    /**
     * 触发限流（msg 中含建议等待秒数）
     */
    public static final String RATE_LIMITED = "4290";

    /**
     * 目标资源不存在或不属于当前用户（用于个人数据越权防护，与不存在同一话术）
     */
    public static final String NOT_FOUND = "4004";

    /**
     * 未登录或登录已过期（与框架 Sa-Token 异常处理器的取值保持一致）
     */
    public static final String UNAUTHORIZED = "401";

    public String getCode() {
        return code;
    }
}
