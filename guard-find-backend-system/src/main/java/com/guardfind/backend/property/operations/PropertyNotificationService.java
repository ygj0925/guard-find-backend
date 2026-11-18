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

package com.guardfind.backend.property.operations;

import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;
import top.continew.starter.core.exception.BusinessException;

@Service
@RequiredArgsConstructor
public class PropertyNotificationService {
    private final OperationsStore db;
    private final PropertyOperationsService operations;
    private final PlatformTransactionManager transactions;
    @Value("${miniapp.wechat.app-id:}")
    private String appId;
    @Value("${miniapp.wechat.app-secret:}")
    private String appSecret;
    private volatile String accessToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    @Transactional(rollbackFor = Exception.class)
    public void subscribe(long user, long bookingId, String templateId) {
        var config = operations.settings();
        if (!config.isSubscriptionEnabled() || !Objects.equals(config.getTemplateId(), templateId))
            throw new BusinessException("订阅提醒尚未启用或模板已变更");
        db.one("SELECT id FROM gf_property_booking WHERE id=? AND (user_id=? OR owner_id=? OR EXISTS(SELECT 1 FROM gf_property_operation o WHERE o.property_id=gf_property_booking.property_id AND o.manager_id=?))", bookingId, user, user, user);
        db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 FOR UPDATE", user);
        int changed = db.jdbc()
            .update("UPDATE gf_property_subscription SET credits=1,update_time=? WHERE user_id=? AND template_id=?", LocalDateTime
                .now(), user, templateId);
        if (changed == 0)
            db.jdbc()
                .update("INSERT INTO gf_property_subscription(user_id,template_id,credits,update_time) VALUES(?,?,1,?)", user, templateId, LocalDateTime
                    .now());
    }

    public void enqueue(long booking, long user, String event, String title) {
        var config = operations.settings();
        String state = config.isSubscriptionEnabled() ? "PENDING" : "DISABLED";
        try {
            db.jdbc()
                .update("INSERT INTO gf_property_notification(id,booking_id,user_id,event_key,template_id,payload,state,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)", IdUtil
                    .getSnowflakeNextId(), booking, user, event, config.getTemplateId(), title, state, LocalDateTime
                        .now(), LocalDateTime.now());
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
        }
    }

    public Map<String, Object> records(int page, int size) {
        operations.platform();
        return db.page("FROM gf_property_notification n", List
            .of(), "n.id,n.booking_id,n.user_id,n.state,n.result,n.create_time,n.update_time", "n.id DESC", page, size);
    }

    public void dispatch() {
        // A network timeout is ambiguous: record UNKNOWN rather than retrying and consuming another consent.
        db.jdbc()
            .update("UPDATE gf_property_notification SET state='UNKNOWN',result='发送进程中断，请人工核对微信结果',update_time=? WHERE state='SENDING' AND update_time<?", LocalDateTime
                .now(), LocalDateTime.now().minusMinutes(5));
        for (var candidate : db
            .rows("SELECT id FROM gf_property_notification WHERE state='PENDING' ORDER BY id LIMIT 30")) {
            var claimed = new TransactionTemplate(transactions).execute(tx -> {
                var row = db.one("SELECT * FROM gf_property_notification WHERE id=? FOR UPDATE", id(candidate, "id"));
                if (!"PENDING".equals(row.get("state")))
                    return null;
                var config = operations.settings();
                String skip = null;
                if (!config.isSubscriptionEnabled() || !Objects.equals(config.getTemplateId(), text(row, "templateId")))
                    skip = "DISABLED";
                else if (db.jdbc()
                    .update("UPDATE gf_property_subscription SET credits=credits-1,update_time=? WHERE user_id=? AND template_id=? AND credits>0", LocalDateTime
                        .now(), id(row, "userId"), text(row, "templateId")) != 1)
                    skip = "NO_CONSENT";
                db.jdbc()
                    .update("UPDATE gf_property_notification SET state=?,update_time=? WHERE id=?", skip == null
                        ? "SENDING"
                        : skip, LocalDateTime.now(), id(row, "id"));
                return skip == null ? row : null;
            });
            if (claimed == null)
                continue;
            String state = "FAILED", result = "";
            try {
                var config = operations.settings();
                var booking = db
                    .one("SELECT b.booking_time,p.title,u.openid FROM gf_property_booking b JOIN gf_property p ON p.id=b.property_id JOIN gf_miniapp_user u ON u.id=? AND u.deleted=0 AND u.account_status=1 WHERE b.id=?", id(claimed, "userId"), id(claimed, "bookingId"));
                var data = new LinkedHashMap<String, Object>();
                data.put(config.getTitleField(), Map.of("value", truncate(text(booking, "title"), 20)));
                data.put(config.getTimeField(), Map.of("value", text(booking, "bookingTime").substring(0, 16)));
                String title = text(claimed, "payload");
                String phrase = title.contains("提醒")
                    ? "待看房"
                    : title.contains("改期")
                        ? "已改期"
                        : title.contains("过期")
                            ? "已过期"
                            : title.contains("已确认")
                                ? "已确认"
                                : title.contains("已拒绝")
                                    ? "已拒绝"
                                    : title.contains("已完成")
                                        ? "已完成"
                                        : title.contains("已到访") ? "已到访" : title.contains("未到访") ? "未到访" : "已取消";
                data.put(config.getStatusField(), Map.of("value", phrase));
                String body = JSONUtil.toJsonStr(Map
                    .of("touser", text(booking, "openid"), "template_id", text(claimed, "templateId"), "page", "pages/property/booking-detail?id=" + text(claimed, "bookingId"), "data", data));
                String raw = HttpRequest
                    .post("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + token())
                    .body(body)
                    .timeout(10000)
                    .execute()
                    .body();
                var response = JSONUtil.parseObj(raw);
                int code = response.getInt("errcode", -1);
                state = code == 0 ? "SENT" : "FAILED";
                result = "errcode=" + code + " " + response.getStr("errmsg", "");
                if (code == 40001 || code == 42001) {
                    accessToken = null;
                    expiresAt = Instant.EPOCH;
                }
            } catch (Exception e) {
                state = "UNKNOWN";
                result = "发送结果未确认，请人工核对；不自动重发";
            }
            db.jdbc()
                .update("UPDATE gf_property_notification SET state=?,result=?,update_time=? WHERE id=? AND state='SENDING'", state, truncate(result, 500), LocalDateTime
                    .now(), id(claimed, "id"));
        }
    }

    private synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(expiresAt))
            return accessToken;
        if (PropertyOperationsService.blank(appId) || PropertyOperationsService.blank(appSecret))
            throw new BusinessException("微信订阅消息未配置");
        var response = JSONUtil.parseObj(HttpUtil.get("https://api.weixin.qq.com/cgi-bin/token", Map
            .of("grant_type", "client_credential", "appid", appId, "secret", appSecret), 10000));
        String token = response.getStr("access_token");
        if (token == null)
            throw new BusinessException("微信访问凭证获取失败");
        accessToken = token;
        expiresAt = Instant.now().plusSeconds(Math.max(60, response.getLong("expires_in", 7200L) - 120));
        return token;
    }

    private static String truncate(String text, int length) {
        return text.length() > length ? text.substring(0, length) : text;
    }
}
