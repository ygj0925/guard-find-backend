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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.common.config.TenantExtensionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.tenant.context.TenantContextHolder;
import java.time.*;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;

@Service
@RequiredArgsConstructor
public class PropertyOperationsService {
    private final OperationsStore db;
    private final ObjectMapper json;
    private final TenantExtensionProperties tenant;
    private final com.guardfind.backend.system.service.RoleService roles;
    @Value("${miniapp.wechat.app-id:}")
    private String appId;
    @Value("${miniapp.wechat.app-secret:}")
    private String appSecret;

    public void platform() {
        if (TenantContextHolder.isTenantEnabled() && !tenant.isDefaultTenant())
            throw new BusinessException("仅平台租户可管理房源运营");
    }

    public OperationsSettings settings() {
        try {
            return json.readValue(db.jdbc()
                .queryForObject("SELECT content_json FROM gf_property_ops_config WHERE id=1", String.class), OperationsSettings.class);
        } catch (Exception e) {
            throw new BusinessException("运营配置读取失败，请联系管理员");
        }
    }

    public Map<String, Object> publicSettings() {
        var c = settings();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operator", c.getOperator());
        result.put("contact", c.getContact());
        result.put("policyVersion", c.getPolicyVersion());
        result.put("effectiveDate", c.getEffectiveDate());
        result.put("policiesPublished", c.isPoliciesPublished());
        result.put("userAgreement", c.isPoliciesPublished() ? c.getUserAgreement() : "");
        result.put("privacyPolicy", c.isPoliciesPublished() ? c.getPrivacyPolicy() : "");
        result.put("subscriptionEnabled", c.isSubscriptionEnabled());
        result.put("templateId", c.isSubscriptionEnabled() ? c.getTemplateId() : "");
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveSettings(OperationsSettings c) {
        platform();
        c.setTemplateId(c.getTemplateId() == null ? "" : c.getTemplateId().trim());
        c.setTitleField(c.getTitleField() == null ? "thing1" : c.getTitleField().trim());
        c.setTimeField(c.getTimeField() == null ? "time2" : c.getTimeField().trim());
        c.setStatusField(c.getStatusField() == null ? "phrase3" : c.getStatusField().trim());
        if (c.getReminderHours() < 1 || c.getReminderHours() > 168 || c.getExpiryHours() < 1 || c
            .getExpiryHours() > 168)
            throw new BusinessException("提醒和过期宽限应为 1–168 小时");
        if (c.isPoliciesPublished() && (blank(c.getOperator()) || blank(c.getContact()) || blank(c
            .getPolicyVersion()) || blank(c.getEffectiveDate()) || blank(c.getUserAgreement()) || blank(c
                .getPrivacyPolicy())))
            throw new BusinessException("发布协议前请完整填写运营主体、联系信息、版本、生效日期和正文");
        if (!blank(c.getEffectiveDate())) {
            try {
                LocalDate.parse(c.getEffectiveDate());
            } catch (Exception e) {
                throw new BusinessException("生效日期格式应为 YYYY-MM-DD");
            }
        }
        if (c.isSubscriptionEnabled()) {
            if (blank(appId) || blank(appSecret) || blank(c.getTemplateId()))
                throw new BusinessException("微信 App ID、密钥和模板 ID 配置完整后才能启用");
            if (blank(c.getTitleField()) || blank(c.getTimeField()) || blank(c.getStatusField()) || !c.getTitleField()
                .matches("thing[0-9]+") || !c.getTimeField().matches("time[0-9]+") || !c.getStatusField()
                    .matches("phrase[0-9]+"))
                throw new BusinessException("请按微信模板配置 thing、time、phrase 字段名称");
        }
        db.one("SELECT id FROM gf_property_ops_config WHERE id=1 FOR UPDATE");
        try {
            db.jdbc().update("UPDATE gf_property_ops_config SET content_json=? WHERE id=1", json.writeValueAsString(c));
        } catch (Exception e) {
            throw new BusinessException("配置保存失败");
        }
    }

    public static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    public void acceptPolicy(long user, String version) {
        var c = settings();
        if (!c.isPoliciesPublished() || !Objects.equals(c.getPolicyVersion(), version))
            throw new BusinessException("协议版本已变更，请重新阅读");
        try {
            db.jdbc()
                .update("INSERT INTO gf_property_policy_accept(user_id,policy_version,create_time) VALUES(?,?,?)", user, version, LocalDateTime
                    .now());
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
        }
    }

    public Map<String, Object> property(long id, Long viewer, boolean admin) {
        var property = db.one("SELECT id,user_id,status FROM gf_property WHERE id=? AND deleted=0", id);
        if (!admin && Integer.parseInt(text(property, "status")) != 2 && !Objects.equals(property
            .get("userId"), viewer == null ? null : viewer.toString()))
            throw new BusinessException("房源不存在或已下架");
        var list = db
            .rows("SELECT o.*,u.nickname manager_name,u.avatar manager_avatar FROM gf_property_operation o LEFT JOIN gf_miniapp_user u ON u.id=o.manager_id AND u.deleted=0 AND u.account_status=1 AND u.is_property_manager=1 WHERE o.property_id=?", id);
        if (list.isEmpty()) {
            var empty = new LinkedHashMap<String, Object>();
            empty.put("propertyId", Long.toString(id));
            empty.put("availability", "ACTIVE");
            empty.put("version", 0);
            if (admin)
                empty.put("activeBookings", db.jdbc()
                    .queryForObject("SELECT COUNT(*) FROM gf_property_booking WHERE property_id=? AND status IN(1,2,5)", Long.class, id));
            return empty;
        }
        var result = list.get(0);
        if (!admin && result.get("managerName") == null)
            result.put("managerId", null);
        if (admin)
            result.put("activeBookings", db.jdbc()
                .queryForObject("SELECT COUNT(*) FROM gf_property_booking WHERE property_id=? AND status IN(1,2,5)", Long.class, id));
        return result;
    }

    public record PropertyInput(Long managerId, String availability, String reason, String reopenDate, Double latitude,
                                Double longitude, String meetingPoint, String instructions, long version) {}

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void saveProperty(long id, PropertyInput input) {
        platform();
        db.one("SELECT id FROM gf_property WHERE id=? AND deleted=0 FOR UPDATE", id);
        var previous = property(id, null, true);
        if (!Objects.equals(previous.get("managerId"), input.managerId() == null ? null : input.managerId().toString()))
            cn.dev33.satoken.stp.StpUtil.checkPermission("system:property:assign");
        if (!Objects.equals(previous.get("availability"), input.availability()))
            cn.dev33.satoken.stp.StpUtil.checkPermission("system:property:status");
        if ((input.availability() == null || !Set.of("ACTIVE", "RENTED", "SOLD", "PAUSED")
            .contains(input.availability())))
            throw new BusinessException("房源业务状态无效");
        if (!"ACTIVE".equals(input.availability()) && blank(input.reason()))
            throw new BusinessException("请填写停止接待原因");
        if ((input.latitude() == null) != (input.longitude() == null))
            throw new BusinessException("经纬度必须同时填写");
        if (input.latitude() != null && (!Double.isFinite(input.latitude()) || !Double.isFinite(input
            .longitude()) || Math.abs(input.latitude()) > 90 || Math.abs(input.longitude()) > 180))
            throw new BusinessException("经纬度超出有效范围");
        if (length(input.reason()) > 500 || length(input.meetingPoint()) > 200 || length(input.instructions()) > 2000)
            throw new BusinessException("原因、集合地点或看房说明过长");
        if (input.managerId() != null)
            db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 AND account_status=1 AND is_property_manager=1", input
                .managerId());
        LocalDate reopen = null;
        if (!blank(input.reopenDate())) {
            try {
                reopen = LocalDate.parse(input.reopenDate());
            } catch (Exception e) {
                throw new BusinessException("重新开放日期无效");
            }
            if (reopen.isBefore(LocalDate.now()))
                throw new BusinessException("重新开放日期不能早于今天");
        }
        long version = Long.parseLong(previous.get("version").toString());
        if (version != input.version())
            throw new BusinessException("房源运营信息已更新，请刷新");
        if ("ACTIVE".equals(input.availability())) {
            if ("ACTIVE".equals(previous.get("availability"))) {
                assertAvailable(id);
            } else if (requireLeaseReadyForReopening(id).isPresent() && "RENTED".equals(previous.get("availability"))) {
                throw new BusinessException("请从合同详情确认重新开放房源");
            }
        }
        if (db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_operation WHERE property_id=?", Long.class, id) == 0)
            db.jdbc()
                .update("INSERT INTO gf_property_operation(property_id,availability,version,update_time) VALUES(?,'ACTIVE',0,?)", id, LocalDateTime
                    .now());
        int changed = db.jdbc()
            .update("UPDATE gf_property_operation SET manager_id=?,availability=?,reason=?,reopen_date=?,latitude=?,longitude=?,meeting_point=?,instructions=?,version=version+1,update_time=? WHERE property_id=? AND version=?", input
                .managerId(), input.availability(), input.reason(), reopen, input.latitude(), input.longitude(), input
                    .meetingPoint(), input.instructions(), LocalDateTime.now(), id, version);
        if (changed != 1)
            throw new BusinessException("房源信息已更新，请刷新");
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    /** Called while holding the property row lock in the signing transaction. */
    public void occupyForLease(long propertyId) {
        if (db.rows("SELECT property_id FROM gf_property_operation WHERE property_id=?", propertyId).isEmpty())
            db.jdbc()
                .update("INSERT INTO gf_property_operation(property_id,availability,version,update_time) VALUES(?,'ACTIVE',0,?)", propertyId, LocalDateTime
                    .now());
        db.jdbc()
            .update("UPDATE gf_property_operation SET availability='RENTED',reason='租赁合同已签订，请完成交接与结算后由管理员确认重新开放',reopen_date=NULL,version=version+1,update_time=? WHERE property_id=? AND availability='ACTIVE'", LocalDateTime
                .now(), propertyId);
    }

    private boolean hasCurrentOrFutureLease(long propertyId) {
        return !db
            .rows("SELECT id FROM gf_property_lease WHERE property_id=? AND status='SIGNED' AND end_date>=? LIMIT 1", propertyId, LocalDate
                .now())
            .isEmpty();
    }

    /** Both reopening entry points hold the property row lock until the state change commits. */
    public OptionalLong requireLeaseReadyForReopening(long propertyId) {
        if (hasCurrentOrFutureLease(propertyId))
            throw new BusinessException("房源存在有效或其他已签租约，不能重新开放");
        var latest = db
            .rows("SELECT id FROM gf_property_lease WHERE property_id=? AND status IN('SIGNED','TERMINATED') ORDER BY start_date DESC,id DESC LIMIT 1", propertyId);
        if (latest.isEmpty())
            return OptionalLong.empty();
        long leaseId = id(latest.get(0), "id");
        var completed = db
            .rows("SELECT DISTINCT kind FROM gf_property_lease_document WHERE lease_id=? AND ((kind='CHECK_OUT' AND status='CONFIRMED') OR (kind='SETTLEMENT' AND status='CLOSED'))", leaseId);
        if (completed.size() != 2)
            throw new BusinessException("请先完成最近一份租约的退租交接及押金结算到账确认");
        return OptionalLong.of(leaseId);
    }

    public void assertAvailable(long id) {
        if (hasCurrentOrFutureLease(id))
            throw new BusinessException("该房源已有已签租约，暂不接受新预约");
        var rows = db.rows("SELECT availability FROM gf_property_operation WHERE property_id=?", id);
        if (!rows.isEmpty() && !"ACTIVE".equals(rows.get(0).get("availability")))
            throw new BusinessException("该房源暂不接受新的预约，请查看房源接待说明");
    }

    public void event(long propertyId, Long user, String type) {
        if (!Set.of("VIEW", "CONSULT").contains(type))
            throw new BusinessException("行为类型无效");
        db.one("SELECT id FROM gf_property WHERE id=? AND status=2 AND deleted=0", propertyId);
        // Coalesce repeat views/consult clicks within a minute to limit accidental inflation.
        if (db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_ops_event WHERE property_id=? AND event_type=? AND ((user_id=?) OR (user_id IS NULL AND ? IS NULL)) AND create_time>?", Long.class, propertyId, type, user, user, LocalDateTime
                .now()
                .minusMinutes(1)) == 0)
            db.jdbc()
                .update("INSERT INTO gf_property_ops_event(id,property_id,user_id,event_type,create_time) VALUES(?,?,?,?,?)", IdUtil
                    .getSnowflakeNextId(), propertyId, user, type, LocalDateTime.now());
    }

    public Map<String, Object> users(String keyword, boolean managers, int page, int size) {
        String from = "FROM gf_miniapp_user u WHERE u.deleted=0";
        var args = new ArrayList<Object>();
        if (managers)
            from += " AND u.is_property_manager=1 AND u.account_status=1";
        if (!blank(keyword)) {
            from += " AND (u.nickname LIKE ? ESCAPE '!' OR CAST(u.id AS CHAR)=?)";
            args.add(like(keyword));
            args.add(keyword.trim());
        }
        return db
            .page(from, args, "u.id,u.nickname,u.avatar,u.account_status,u.is_property_manager,u.create_time", "u.create_time DESC,u.id DESC", page, size);
    }

    public void userStatus(long id, int status) {
        platform();
        if (status != 1 && status != 2)
            throw new BusinessException("用户状态无效");
        if (db.jdbc()
            .update("UPDATE gf_miniapp_user SET account_status=?,update_time=? WHERE id=? AND deleted=0", status, LocalDateTime
                .now(), id) != 1)
            throw new BusinessException("用户不存在");
    }

    public Map<String, Object> stats(int days) {
        platform();
        int range = Math.min(90, Math.max(1, days));
        var since = LocalDate.now().minusDays(range - 1).atStartOfDay();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", db
            .rows("SELECT event_type,COUNT(*) total FROM gf_property_ops_event WHERE create_time>=? GROUP BY event_type", since));
        result.put("bookings", db
            .rows("SELECT status,COUNT(*) total FROM gf_property_booking WHERE create_time>=? GROUP BY status", since));
        result.put("daily", db
            .rows("SELECT CAST(create_time AS DATE) day,event_type,COUNT(*) total FROM gf_property_ops_event WHERE create_time>=? GROUP BY CAST(create_time AS DATE),event_type ORDER BY day", since));
        result.put("pending", db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_booking WHERE status=1", Long.class));
        result.put("overdue", db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_booking WHERE status=1 AND booking_time<?", Long.class, LocalDateTime
                .now()));
        result.put("unresolved", db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_support_ticket WHERE state='OPEN'", Long.class));
        result.put("managers", db
            .rows("SELECT o.manager_id,u.nickname,COUNT(b.id) bookings,SUM(CASE WHEN b.status=6 THEN 1 ELSE 0 END) completed FROM gf_property_booking b JOIN gf_property_operation o ON o.property_id=b.property_id LEFT JOIN gf_miniapp_user u ON u.id=o.manager_id WHERE b.create_time>=? GROUP BY o.manager_id,u.nickname", since));
        result.put("properties", db
            .rows("SELECT p.id,p.title,COUNT(e.id) events,SUM(CASE WHEN e.event_type='CONSULT' THEN 1 ELSE 0 END) consultations FROM gf_property p LEFT JOIN gf_property_ops_event e ON e.property_id=p.id AND e.create_time>=? WHERE p.deleted=0 GROUP BY p.id,p.title ORDER BY events DESC LIMIT 20", since));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> support(long user, boolean admin) {
        var profile = db.one("SELECT id,nickname FROM gf_miniapp_user WHERE id=? AND deleted=0", user);
        var found = db.rows("SELECT * FROM gf_property_support_ticket WHERE user_id=?", user);
        Map<String, Object> ticket = new LinkedHashMap<>();
        if (!found.isEmpty())
            ticket.putAll(found.get(0));
        else {
            ticket.put("state", "OPEN");
            ticket.put("version", 0);
        }
        ticket.put("userId", Long.toString(user));
        ticket.put("nickname", profile.get("nickname"));
        if (ticket.get("propertyId") != null)
            ticket.put("property", db
                .rows("SELECT id,title,price,price_type,images FROM gf_property WHERE id=? AND deleted=0", Long
                    .valueOf(ticket.get("propertyId").toString())));
        if (admin)
            ticket.put("logs", db
                .rows("SELECT l.*,u.nickname admin_name FROM gf_property_support_log l LEFT JOIN sys_user u ON u.id=l.admin_id WHERE l.user_id=? ORDER BY l.create_time DESC,l.id DESC LIMIT 100", user));
        return ticket;
    }

    @Transactional(rollbackFor = Exception.class)
    public void consult(long user, long propertyId, boolean support) {
        event(propertyId, user, "CONSULT");
        if (!support)
            return;
        db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 FOR UPDATE", user);
        var ticket = db.rows("SELECT * FROM gf_property_support_ticket WHERE user_id=? FOR UPDATE", user);
        if (ticket.isEmpty())
            db.jdbc()
                .update("INSERT INTO gf_property_support_ticket(user_id,property_id,state,version,update_time) VALUES(?,?,'OPEN',0,?)", user, propertyId, LocalDateTime
                    .now());
        else
            db.jdbc()
                .update("UPDATE gf_property_support_ticket SET property_id=?,state='OPEN',version=version+1,update_time=? WHERE user_id=?", propertyId, LocalDateTime
                    .now(), user);
    }

    public record SupportInput(String action, String note, Long ownerAdminId, long version) {}

    @Transactional(rollbackFor = Exception.class)
    public void supportAction(long user, long admin, SupportInput input) {
        platform();
        db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 FOR UPDATE", user);
        var rows = db.rows("SELECT * FROM gf_property_support_ticket WHERE user_id=? FOR UPDATE", user);
        if (rows.isEmpty())
            db.jdbc()
                .update("INSERT INTO gf_property_support_ticket(user_id,state,version,update_time) VALUES(?,'OPEN',0,?)", user, LocalDateTime
                    .now());
        var ticket = support(user, true);
        if (Long.parseLong(ticket.get("version").toString()) != input.version())
            throw new BusinessException("会话处理状态已变更，请刷新");
        if (length(input.note()) > 2000)
            throw new BusinessException("内部备注最多 2000 字");
        Long owner = ticket.get("ownerAdminId") == null ? null : Long.valueOf(ticket.get("ownerAdminId").toString());
        String state = text(ticket, "state");
        if (Set.of("TRANSFER", "NOTE", "REOPEN")
            .contains(input.action()) && owner != null && owner != admin && !cn.dev33.satoken.stp.StpUtil
                .hasPermission("system:im:workflow:manage"))
            throw new BusinessException("仅接待客服或主管可处理");
        switch (input.action()) {
            case "CLAIM" -> {
                if (owner != null && owner != admin)
                    throw new BusinessException("会话已由其他客服接待，请使用转交");
                owner = admin;
                state = "OPEN";
            }
            case "TRANSFER" -> {
                if (input.ownerAdminId() == null)
                    throw new BusinessException("请选择转交客服");
                db.one("SELECT id FROM sys_user WHERE id=? AND deleted=0 AND status=1", input.ownerAdminId());
                if (!canReceiveSupport(input.ownerAdminId()))
                    throw new BusinessException("所选人员没有客服接待权限");
                owner = input.ownerAdminId();
            }
            case "RESOLVE" -> {
                if (owner == null || owner != admin)
                    throw new BusinessException("请先认领会话再标记解决");
                state = "RESOLVED";
            }
            case "REOPEN" -> state = "OPEN";
            case "NOTE" -> {
                if (blank(input.note()))
                    throw new BusinessException("请填写内部备注");
            }
            default -> throw new BusinessException("会话操作无效");
        }

        db.jdbc()
            .update("UPDATE gf_property_support_ticket SET owner_admin_id=?,state=?,version=version+1,update_time=? WHERE user_id=?", owner, state, LocalDateTime
                .now(), user);
        db.jdbc()
            .update("INSERT INTO gf_property_support_log(id,user_id,admin_id,action,note,create_time) VALUES(?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), user, admin, input.action(), input.note(), LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void incoming(long user) {
        db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 FOR UPDATE", user);
        var rows = db.rows("SELECT state FROM gf_property_support_ticket WHERE user_id=? FOR UPDATE", user);
        if (rows.isEmpty())
            db.jdbc()
                .update("INSERT INTO gf_property_support_ticket(user_id,state,version,update_time) VALUES(?,'OPEN',0,?)", user, LocalDateTime
                    .now());
        else
            db.jdbc()
                .update("UPDATE gf_property_support_ticket SET state='OPEN',version=version+1,update_time=? WHERE user_id=?", LocalDateTime
                    .now(), user);
    }

    private boolean canReceiveSupport(long admin) {
        Set<String> permissions = roles.listPermissionByUserId(admin);
        return permissions.contains("*:*:*") || permissions.containsAll(Set
            .of("system:im:list", "system:im:send", "system:im:workflow"));
    }

    public List<Map<String, Object>> supportAdmins() {
        platform();
        return db.rows("SELECT id,nickname FROM sys_user WHERE deleted=0 AND status=1 ORDER BY nickname,id LIMIT 200")
            .stream()
            .filter(row -> canReceiveSupport(id(row, "id")))
            .toList();
    }

    public void guardReply(long user, long admin) {
        var ticket = db
            .rows("SELECT owner_admin_id,state FROM gf_property_support_ticket WHERE user_id=? FOR UPDATE", user);
        if (ticket.isEmpty() || ticket.get(0).get("ownerAdminId") == null)
            throw new BusinessException("请先认领该会话再回复");
        if (!Objects.equals(ticket.get(0).get("ownerAdminId"), Long.toString(admin)))
            throw new BusinessException("该会话由其他客服接待，请先转交或认领");
        if ("RESOLVED".equals(ticket.get(0).get("state")))
            throw new BusinessException("请先重新打开会话");
    }

    public Map<String, Object> tickets(String state, Long owner, int page, int size) {
        String from = "FROM gf_property_support_ticket t JOIN gf_miniapp_user u ON u.id=t.user_id AND u.deleted=0 WHERE 1=1";
        var args = new ArrayList<Object>();
        if (!blank(state)) {
            from += " AND t.state=?";
            args.add(state);
        }
        if (owner != null) {
            from += " AND t.owner_admin_id=?";
            args.add(owner);
        }
        return db.page(from, args, "t.*,u.nickname", "t.update_time DESC,t.user_id DESC", page, size);
    }
}
