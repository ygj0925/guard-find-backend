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
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;
import java.time.*;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;

@Service
@RequiredArgsConstructor
public class PropertyBookingOperations {
    private final OperationsStore db;
    private final PropertyOperationsService operations;
    private final MiniappNoticeService notices;
    private final PropertyNotificationService notifications;

    public record Action(String action, String reason, String bookingTime) {}

    public static final Map<Integer, String> LABELS = Map
        .of(1, "待确认", 2, "已确认", 3, "已拒绝", 4, "已取消", 5, "已到访", 6, "已完成", 7, "未到访", 8, "已过期");

    private Map<String, Object> booking(long id, boolean lock) {
        return db.one("SELECT * FROM gf_property_booking WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private boolean manager(Map<String, Object> b, long user) {
        if (Objects.equals(b.get("ownerId"), Long.toString(user)))
            return true;
        return db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_operation o JOIN gf_miniapp_user u ON u.id=o.manager_id WHERE o.property_id=? AND o.manager_id=? AND u.deleted=0 AND u.account_status=1 AND u.is_property_manager=1", Long.class, id(b, "propertyId"), user) > 0;
    }

    public Map<String, Object> detail(long bookingId, Long user, boolean admin) {
        var b = booking(bookingId, false);
        if (!admin && (user == null || (!Objects.equals(b.get("userId"), user.toString()) && !manager(b, user))))
            throw new BusinessException("预约不存在或无权查看");
        b.put("statusLabel", LABELS.get(Integer.parseInt(text(b, "status"))));
        b.put("property", db
            .rows("SELECT id,title,images,price,price_type,address FROM gf_property WHERE id=? AND deleted=0", id(b, "propertyId")));
        b.put("canManage", admin || (user != null && manager(b, user)));
        b.put("isGuest", user != null && Objects.equals(b.get("userId"), user.toString()));
        b.put("reception", ((List<?>)b.get("property")).isEmpty()
            ? Map.of("availability", "PAUSED", "reason", "房源已删除")
            : operations.property(id(b, "propertyId"), null, true));
        var logs = db
            .rows("SELECT id,actor_type,action,from_status,to_status,reason,create_time FROM gf_property_booking_log WHERE booking_id=? ORDER BY create_time,id", bookingId);
        b.put("logs", logs);
        return b;
    }

    public Map<String, Object> page(String keyword,
                                    Integer status,
                                    String dateFrom,
                                    String dateTo,
                                    int page,
                                    int size) {
        operations.platform();
        String from = "FROM gf_property_booking b LEFT JOIN gf_property p ON p.id=b.property_id LEFT JOIN gf_property_operation o ON o.property_id=b.property_id LEFT JOIN gf_miniapp_user m ON m.id=o.manager_id WHERE 1=1";
        var args = new ArrayList<Object>();
        if (status != null) {
            from += " AND b.status=?";
            args.add(status);
        }
        if (!PropertyOperationsService.blank(keyword)) {
            from += " AND (p.title LIKE ? ESCAPE '!' OR b.contact_name LIKE ? ESCAPE '!' OR CAST(b.id AS CHAR)=?)";
            args.add(like(keyword));
            args.add(like(keyword));
            args.add(keyword.trim());
        }
        if (!PropertyOperationsService.blank(dateFrom)) {
            from += " AND b.booking_time>=?";
            args.add(parseDate(dateFrom).atStartOfDay());
        }
        if (!PropertyOperationsService.blank(dateTo)) {
            from += " AND b.booking_time<?";
            args.add(parseDate(dateTo).plusDays(1).atStartOfDay());
        }
        return db
            .page(from, args, "b.*,p.title property_title,m.nickname manager_name", "b.booking_time DESC,b.id DESC", page, size);
    }

    public Map<String, Object> mine(long user, String role, Integer status, int page, int size) {
        if (!Set.of("guest", "owner").contains(role))
            throw new BusinessException("预约角色无效");
        String from = "FROM gf_property_booking b LEFT JOIN gf_property p ON p.id=b.property_id LEFT JOIN gf_property_operation o ON o.property_id=b.property_id WHERE ";
        var args = new ArrayList<Object>();
        if ("guest".equals(role)) {
            from += "b.user_id=?";
            args.add(user);
        } else {
            from += "(b.owner_id=? OR (o.manager_id=? AND EXISTS(SELECT 1 FROM gf_miniapp_user mu WHERE mu.id=o.manager_id AND mu.deleted=0 AND mu.is_property_manager=1 AND mu.account_status=1)))";
            args.add(user);
            args.add(user);
        }
        if (status != null) {
            from += " AND b.status=?";
            args.add(status);
        }
        return db.page(from, args, "b.*,p.title property_title", "b.booking_time DESC,b.id DESC", page, size);
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            throw new BusinessException("日期格式无效");
        }
    }

    private static LocalDateTime parseTime(String text) {
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (Exception e) {
            throw new BusinessException("看房时间格式无效");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void action(long bookingId, Long user, boolean admin, Action input) {
        if (admin)
            operations.platform();
        // Use the same property -> booking lock order as appointment creation/deletion.
        var initial = booking(bookingId, false);
        db.one("SELECT id FROM gf_property WHERE id=? AND deleted=0 FOR UPDATE", id(initial, "propertyId"));
        var b = booking(bookingId, true);
        boolean guest = !admin && Objects.equals(b.get("userId"), user == null ? null : user.toString());
        if (!admin && !guest && (user == null || !manager(b, user)))
            throw new BusinessException("预约不存在或无权操作");
        int from = Integer.parseInt(text(b, "status"));
        if (input == null || input.action() == null)
            throw new BusinessException("请选择操作");
        if (input.reason() != null && input.reason().length() > 500)
            throw new BusinessException("处理原因最多 500 字");
        String action = input.action().toUpperCase(Locale.ROOT);
        if (guest && !Set.of("CANCEL", "RESCHEDULE").contains(action))
            throw new BusinessException("预约人只能取消或申请改期");
        int to = switch (action) {
            case "CONFIRM" -> 2;
            case "REJECT" -> 3;
            case "CANCEL" -> 4;
            case "VISIT" -> 5;
            case "COMPLETE" -> 6;
            case "NO_SHOW" -> 7;
            case "RESCHEDULE" -> 1;
            default -> throw new BusinessException("预约操作无效");
        };
        boolean legal = switch (action) {
            case "CONFIRM", "REJECT" -> from == 1;
            case "CANCEL", "RESCHEDULE" -> from == 1 || from == 2;
            case "VISIT", "NO_SHOW" -> from == 2;
            case "COMPLETE" -> from == 5;
            default -> false;
        };
        if (!legal)
            throw new BusinessException("当前预约状态不可执行此操作，请刷新");
        if (Set.of("REJECT", "CANCEL", "RESCHEDULE", "NO_SHOW").contains(action) && PropertyOperationsService
            .blank(input.reason()))
            throw new BusinessException("请填写处理原因");
        LocalDateTime time = parseTime(text(b, "bookingTime"));
        if ("NO_SHOW".equals(action) && time.isAfter(LocalDateTime.now()))
            throw new BusinessException("预约时间尚未到达，不能标记未到访");
        if ("CONFIRM".equals(action) && time.isBefore(LocalDateTime.now()))
            throw new BusinessException("预约时间已过，请改期或关闭预约");
        if ("RESCHEDULE".equals(action)) {
            var next = parseTime(input.bookingTime());
            if (!next.isAfter(LocalDateTime.now()))
                throw new BusinessException("改期时间必须晚于当前时间");
            operations.assertAvailable(id(b, "propertyId"));
            db.jdbc().update("UPDATE gf_property_booking SET booking_time=? WHERE id=?", next, bookingId);
        }
        int changed = db.jdbc()
            .update("UPDATE gf_property_booking SET status=?,update_time=? WHERE id=? AND status=?", to, LocalDateTime
                .now(), bookingId, from);
        if (changed != 1)
            throw new BusinessException("预约状态已变化，请刷新重试");
        long log = log(bookingId, admin ? "ADMIN" : guest ? "GUEST" : "MANAGER", user, action, from, to, input
            .reason());
        String title = "RESCHEDULE".equals(action) ? "看房预约已改期" : "看房预约" + LABELS.get(to);
        notifyParticipants(b, title, PropertyOperationsService.blank(input.reason())
            ? title
            : title + "：" + input.reason(), log);
        if (to == 5 || to == 6)
            db.jdbc()
                .update("INSERT INTO gf_property_ops_event(id,property_id,user_id,event_type,create_time) VALUES(?,?,?,?,?)", IdUtil
                    .getSnowflakeNextId(), id(b, "propertyId"), id(b, "userId"), to == 5
                        ? "VISIT"
                        : "COMPLETE", LocalDateTime.now());
    }

    public long log(long booking, String actor, Long user, String action, Integer from, Integer to, String reason) {
        long id = IdUtil.getSnowflakeNextId();
        db.jdbc()
            .update("INSERT INTO gf_property_booking_log(id,booking_id,actor_type,actor_id,action,from_status,to_status,reason,create_time) VALUES(?,?,?,?,?,?,?,?,?)", id, booking, actor, user, action, from, to, reason, LocalDateTime
                .now());
        return id;
    }

    public void created(long id) {
        var b = booking(id, false);
        long log = log(id, "GUEST", id(b, "userId"), "CREATE", null, 1, "预约已提交");
        var meta = operations.property(id(b, "propertyId"), null, true);
        if (meta.get("managerId") != null && !Objects.equals(meta.get("managerId"), b.get("ownerId")))
            notices.send(Long.valueOf(meta.get("managerId")
                .toString()), "PROPERTY_BOOKING", id, "收到待处理看房预约", "有新的看房预约，请及时处理");
        db.jdbc()
            .update("INSERT INTO gf_property_ops_event(id,property_id,user_id,event_type,create_time) VALUES(?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), id(b, "propertyId"), id(b, "userId"), "BOOKING", LocalDateTime.now());
    }

    private void notifyParticipants(Map<String, Object> b, String title, String content, long eventId) {
        var recipients = new LinkedHashSet<Long>();
        recipients.add(id(b, "userId"));
        if (b.get("ownerId") != null)
            recipients.add(id(b, "ownerId"));
        var meta = operations.property(id(b, "propertyId"), null, true);
        if (meta.get("managerId") != null)
            recipients.add(Long.valueOf(meta.get("managerId").toString()));
        for (Long recipient : recipients) {
            notices.send(recipient, "PROPERTY_BOOKING", id(b, "id"), title, content);
            notifications.enqueue(id(b, "id"), recipient, "booking-" + eventId, title);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int maintain() {
        var c = operations.settings();
        int count = 0;
        var expired = db
            .rows("SELECT b.id,b.property_id FROM gf_property_booking b JOIN gf_property p ON p.id=b.property_id AND p.deleted=0 WHERE b.status IN(1,2) AND b.booking_time<? ORDER BY b.id LIMIT 200", LocalDateTime
                .now()
                .minusHours(c.getExpiryHours()));
        for (var row : expired) {
            db.one("SELECT id FROM gf_property WHERE id=? AND deleted=0 FOR UPDATE", id(row, "propertyId"));
            var b = booking(id(row, "id"), true);
            int status = Integer.parseInt(text(b, "status"));
            if (!Set.of(1, 2).contains(status) || parseTime(text(b, "bookingTime")).isAfter(LocalDateTime.now()
                .minusHours(c.getExpiryHours())))
                continue;
            db.jdbc()
                .update("UPDATE gf_property_booking SET status=8,update_time=? WHERE id=?", LocalDateTime
                    .now(), id(b, "id"));
            long log = log(id(b, "id"), "SYSTEM", null, "EXPIRE", status, 8, "超过预约时间且未完成处理，系统自动关闭");
            notifyParticipants(b, "看房预约已过期", "可重新提交新的看房预约", log);
            count++;
        }
        var upcoming = db
            .rows("SELECT b.id FROM gf_property_booking b JOIN gf_property p ON p.id=b.property_id AND p.deleted=0 WHERE b.status=2 AND b.booking_time>? AND b.booking_time<=? AND NOT EXISTS(SELECT 1 FROM gf_property_booking_log l WHERE l.booking_id=b.id AND l.action='REMIND' AND l.create_time>=COALESCE(b.update_time,b.create_time)) ORDER BY b.id LIMIT 200", LocalDateTime
                .now(), LocalDateTime.now().plusHours(c.getReminderHours()));
        for (var row : upcoming) {
            var b = booking(id(row, "id"), true);
            if (Integer.parseInt(text(b, "status")) != 2)
                continue;
            var time = parseTime(text(b, "bookingTime"));
            if (!time.isAfter(LocalDateTime.now()) || time.isAfter(LocalDateTime.now().plusHours(c.getReminderHours())))
                continue;
            if (db.jdbc()
                .queryForObject("SELECT COUNT(*) FROM gf_property_booking_log WHERE booking_id=? AND action='REMIND' AND create_time>=?", Long.class, id(b, "id"), parseTime(b
                    .get("updateTime") == null ? text(b, "createTime") : text(b, "updateTime"))) > 0)
                continue;
            long log = log(id(b, "id"), "SYSTEM", null, "REMIND", 2, 2, "看房时间提醒");
            notifyParticipants(b, "看房时间提醒", "请按约定时间到访，详情内可查看集合地点和管家信息", log);
        }
        return count;
    }
}
