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

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.property.operations.OperationsStore;
import com.guardfind.backend.property.operations.PropertyOperationsService;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class PropertyLeaseService {
    private final OperationsStore db;
    private final PropertyOperationsService operations;
    private final MiniappNoticeService notices;
    private final ObjectMapper json;
    private final org.springframework.transaction.PlatformTransactionManager transactions;

    public record Draft(Long propertyId, Long tenantId, Long landlordId, String title, String landlordName,
                        String tenantName, String startDate, String endDate, BigDecimal monthlyRent, BigDecimal deposit,
                        String terms, Long previousLeaseId, Long version) {}

    public record Action(String action, long version, String reason, String signedName, Boolean accepted,
                         String contentHash, String requestedEndDate, String renewalStartDate, String renewalEndDate,
                         BigDecimal monthlyRent, BigDecimal deposit) {}

    @Data
    public static class Settings {
        private boolean remindersEnabled = true;
        private List<Integer> reminderDays = List.of(30, 7, 1, 0);
        private int renewalWindowDays = 90;
    }

    public Settings settings() {
        try {
            return json.readValue(db.jdbc
                .queryForObject("SELECT content_json FROM gf_property_lease_config WHERE id=1", String.class), Settings.class);
        } catch (Exception e) {
            throw fail("合同提醒配置读取失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveSettings(Settings input) {
        operations.platform();
        if (input == null || input.getReminderDays() == null || input.getReminderDays().isEmpty() || input
            .getReminderDays()
            .size() > 10 || input.getReminderDays().stream().anyMatch(x -> x == null || x < 0 || x > 365) || input
                .getRenewalWindowDays() < 1 || input.getRenewalWindowDays() > 365)
            throw fail("提醒日期应为0至365天，续租窗口应为1至365天");
        input.setReminderDays(input.getReminderDays().stream().distinct().sorted(Comparator.reverseOrder()).toList());
        try {
            db.jdbc.update("UPDATE gf_property_lease_config SET content_json=? WHERE id=1", json
                .writeValueAsString(input));
        } catch (java.io.IOException e) {
            throw fail("配置保存失败");
        }
    }

    public Map<String, Object> propertyOptions(String keyword, int page, int size) {
        operations.platform();
        String from = "FROM gf_property WHERE deleted=0 AND price_type='rent'";
        var args = new ArrayList<Object>();
        if (keyword != null && !keyword.isBlank()) {
            from += " AND (title LIKE ? ESCAPE '!' OR address LIKE ? ESCAPE '!')";
            args.add(like(keyword));
            args.add(like(keyword));
        }
        return db.page(from, args, "id,title,address,user_id", "id DESC", page, size);
    }

    public Map<String, Object> customerOptions(String keyword, int page, int size) {
        operations.platform();
        String from = "FROM gf_miniapp_user WHERE deleted=0 AND account_status=1";
        var args = new ArrayList<Object>();
        if (keyword != null && !keyword.isBlank()) {
            from += " AND (nickname LIKE ? ESCAPE '!' OR CAST(id AS CHAR(20))=?)";
            args.add(like(keyword));
            args.add(keyword.trim());
        }
        return db.page(from, args, "id,nickname,account_status", "id DESC", page, size);
    }

    public Map<String, Object> page(Long user,
                                    boolean admin,
                                    String status,
                                    String keyword,
                                    Integer expiringDays,
                                    int page,
                                    int size) {
        return page(user, admin, status, keyword, expiringDays, null, null, page, size);
    }

    public Map<String, Object> page(Long user,
                                    boolean admin,
                                    String status,
                                    String keyword,
                                    Integer expiringDays,
                                    String renewalStatus,
                                    String moveOutStatus,
                                    int page,
                                    int size) {
        if (admin)
            operations.platform();
        else
            activeUser(user);
        String from = "FROM gf_property_lease l WHERE 1=1";
        var args = new ArrayList<Object>();
        if (!admin) {
            from += " AND (l.tenant_id=? OR l.landlord_id=?) AND l.published_at IS NOT NULL";
            args.add(user);
            args.add(user);
        }
        if (status != null && !status.isBlank()) {
            if (status.equals("EXPIRED")) {
                from += " AND l.status='SIGNED' AND l.end_date<?";
                args.add(LocalDate.now());
            } else {
                if (!Set.of("DRAFT", "PENDING_SIGNATURE", "SIGNED", "TERMINATED", "VOID").contains(status))
                    throw fail("合同状态无效");
                from += " AND l.status=?";
                args.add(status);
                if (status.equals("SIGNED")) {
                    from += " AND l.end_date>=?";
                    args.add(LocalDate.now());
                }
            }
        }
        if (keyword != null && !keyword.isBlank()) {
            from += " AND (l.title LIKE ? ESCAPE '!' OR l.property_title LIKE ? ESCAPE '!' OR l.tenant_name LIKE ? ESCAPE '!' OR CAST(l.id AS CHAR(20))=?)";
            for (int i = 0; i < 3; i++)
                args.add(like(keyword));
            args.add(keyword.trim());
        }
        if (renewalStatus != null && !renewalStatus.isBlank()) {
            if (!Set.of("NONE", "PENDING", "APPROVED", "REJECTED", "CANCELED").contains(renewalStatus))
                throw fail("续租状态无效");
            from += " AND l.renewal_status=?";
            args.add(renewalStatus);
        }
        if (moveOutStatus != null && !moveOutStatus.isBlank()) {
            if (!Set.of("NONE", "PENDING", "RESOLVED").contains(moveOutStatus))
                throw fail("退租状态无效");
            from += " AND l.move_out_status=?";
            args.add(moveOutStatus);
        }
        if (expiringDays != null) {
            if (expiringDays < 0 || expiringDays > 365)
                throw fail("临期范围应为0至365天");
            from += " AND l.status='SIGNED' AND l.end_date>=? AND l.end_date<=?";
            args.add(LocalDate.now());
            args.add(LocalDate.now().plusDays(expiringDays));
        }
        var result = db.page(from, args, "l.*", "l.id DESC", page, size);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>)result.get("list");
        int window = settings().getRenewalWindowDays();
        for (var row : rows) {
            decorate(row, user, window);
            row.remove("terms");
        }
        return result;
    }

    public Map<String, Object> detail(long leaseId, Long user, boolean admin) {
        if (admin)
            operations.platform();
        else
            activeUser(user);
        var row = db.one("SELECT * FROM gf_property_lease WHERE id=?", leaseId);
        authorize(row, user, admin);
        decorate(row, user, settings().getRenewalWindowDays());
        if (row.get("renewalLeaseId") != null)
            row.put("renewalLeaseStatus", db
                .one("SELECT status FROM gf_property_lease WHERE id=?", id(row, "renewalLeaseId"))
                .get("status"));
        row.put("logs", db
            .rows("SELECT id,action,actor_type,reason,content_hash,create_time FROM gf_property_lease_log WHERE lease_id=? ORDER BY id DESC", leaseId));
        return row;
    }

    private void decorate(Map<String, Object> row, Long user, int window) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), date(text(row, "endDate")));
        if ("SIGNED".equals(row.get("status")) && days < 0)
            row.put("status", "EXPIRED");
        boolean isTenant = user != null && id(row, "tenantId") == user;
        row.put("isTenant", isTenant);
        row.put("isLandlord", user != null && id(row, "landlordId") == user);
        row.put("daysRemaining", days);
        row.put("renewalWindowDays", window);
        row.put("canRenew", isTenant && !"PENDING".equals(row.get("moveOutStatus")) && "SIGNED".equals(row
            .get("status")) && days <= window && !Set.of("PENDING", "APPROVED").contains(text(row, "renewalStatus")));
    }

    @Transactional(rollbackFor = Exception.class)
    public String create(Draft input, long admin) {
        operations.platform();
        if (input.previousLeaseId() != null)
            throw fail("续租合同须从原合同的续租审核生成");
        return Long.toString(insertDraft(input, admin, null));
    }

    private long insertDraft(Draft input, long admin, Long previousId) {
        var property = validateDraft(input);
        long leaseId = IdUtil.getSnowflakeNextId();
        var now = LocalDateTime.now();
        db.jdbc
            .update("INSERT INTO gf_property_lease(id,property_id,property_title,property_address,tenant_id,landlord_id,title,landlord_name,tenant_name,start_date,end_date,monthly_rent,deposit,terms,previous_lease_id,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", leaseId, input
                .propertyId(), text(property, "title"), text(property, "address"), input.tenantId(), input
                    .landlordId(), input.title().trim(), input.landlordName().trim(), input.tenantName()
                        .trim(), date(input.startDate()), date(input.endDate()), input.monthlyRent(), input
                            .deposit(), input.terms().trim(), previousId, now, now);
        log(leaseId, "ADMIN", admin, "CREATE", previousId == null ? "创建合同草稿" : "从原合同创建续租草稿", null);
        return leaseId;
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(long leaseId, Draft input, long admin) {
        operations.platform();
        var row = lock(leaseId);
        checkVersion(row, input.version());
        expect(row, "DRAFT");
        if (id(row, "propertyId") != Objects.requireNonNullElse(input.propertyId(), 0L))
            throw fail("草稿不可变更房源，请作废后重新创建");
        if (row.get("previousLeaseId") != null) {
            var previous = db.one("SELECT * FROM gf_property_lease WHERE id=?", id(row, "previousLeaseId"));
            if (id(row, "tenantId") != Objects.requireNonNullElse(input
                .tenantId(), 0L) || id(row, "landlordId") != Objects.requireNonNullElse(input
                    .landlordId(), 0L) || !date(input.startDate()).isAfter(date(text(previous, "endDate"))))
                throw fail("续租草稿不可变更双方，起租日期必须晚于原合同到期日");
        }
        var property = validateDraft(input);
        db.jdbc
            .update("UPDATE gf_property_lease SET property_title=?,property_address=?,tenant_id=?,landlord_id=?,title=?,landlord_name=?,tenant_name=?,start_date=?,end_date=?,monthly_rent=?,deposit=?,terms=?,version=version+1,update_time=? WHERE id=?", text(property, "title"), text(property, "address"), input
                .tenantId(), input.landlordId(), input.title().trim(), input.landlordName().trim(), input.tenantName()
                    .trim(), date(input.startDate()), date(input.endDate()), input.monthlyRent(), input.deposit(), input
                        .terms()
                        .trim(), LocalDateTime.now(), leaseId);
        log(leaseId, "ADMIN", admin, "EDIT", "更新草稿", null);
    }

    private Map<String, Object> validateDraft(Draft input) {
        if (input == null || input.propertyId() == null || input.tenantId() == null || input.landlordId() == null)
            throw fail("请选择房源、承租人和出租人");
        if (input.tenantId().equals(input.landlordId()))
            throw fail("承租人与出租人不能为同一人");
        var property = db.one("SELECT * FROM gf_property WHERE id=? AND deleted=0 FOR UPDATE", input.propertyId());
        if (!"rent".equals(text(property, "priceType")))
            throw fail("仅出租房源可创建租赁合同");
        if (property.get("userId") != null && id(property, "userId") != input.landlordId())
            throw fail("出租人必须为房源绑定的房东；请先核实房源归属");
        activeUser(input.tenantId());
        activeUser(input.landlordId());
        requireText(input.title(), 200, "合同标题");
        requireText(input.landlordName(), 100, "出租人姓名");
        requireText(input.tenantName(), 100, "承租人姓名");
        requireText(input.terms(), 50000, "合同条款");
        LocalDate start = date(input.startDate()), end = date(input.endDate());
        if (end.isBefore(start) || end.isBefore(LocalDate.now()) || end.isAfter(start.plusYears(20)))
            throw fail("租期结束日应不早于开始日和今天，租期不能超过20年");
        money(input.monthlyRent(), false);
        money(input.deposit(), true);
        return property;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> action(long leaseId, long actor, boolean admin, Action input) {
        if (admin)
            operations.platform();
        else
            activeUser(actor);
        if (input == null || input.action() == null)
            throw fail("请选择合同操作");
        var row = lock(leaseId);
        authorize(row, actor, admin);
        checkVersion(row, input.version());
        String action = input.action();
        String actorType = admin ? "ADMIN" : id(row, "tenantId") == actor ? "TENANT" : "LANDLORD";
        if (input.reason() != null && input.reason().length() > 1000)
            throw fail("原因不能超过1000字");
        Long renewalId = null;
        if (admin) {
            switch (action) {
                case "PUBLISH" -> {
                    expect(row, "DRAFT");
                    activeUser(id(row, "tenantId"));
                    activeUser(id(row, "landlordId"));
                    var property = db
                        .one("SELECT user_id,price_type FROM gf_property WHERE id=? AND deleted=0", id(row, "propertyId"));
                    if (!"rent".equals(property.get("priceType")))
                        throw fail("当前房源已不是出租房源，请先核实");
                    if (property.get("userId") != null && id(property, "userId") != id(row, "landlordId"))
                        throw fail("房源归属已变更，请重新核实合同");
                    if (date(text(row, "endDate")).isBefore(LocalDate.now()))
                        throw fail("合同租期已经结束");
                    overlap(row);
                    String hash = hash(row);
                    db.jdbc
                        .update("UPDATE gf_property_lease SET status='PENDING_SIGNATURE',content_hash=?,published_at=? WHERE id=?", hash, LocalDateTime
                            .now(), leaseId);
                    row.put("contentHash", hash);
                }
                case "VOID" -> {
                    expect(row, "DRAFT", "PENDING_SIGNATURE");
                    reason(input);
                    db.jdbc.update("UPDATE gf_property_lease SET status='VOID' WHERE id=?", leaseId);
                    releaseRenewal(row);
                }
                case "TERMINATE" -> {
                    expect(row, "SIGNED");
                    if (row.get("renewalLeaseId") != null && !db
                        .rows("SELECT id FROM gf_property_lease WHERE id=? AND status IN('DRAFT','PENDING_SIGNATURE','SIGNED')", id(row, "renewalLeaseId"))
                        .isEmpty())
                        throw fail("请先处理关联续租合同，再终止原合同");
                    reason(input);
                    db.jdbc
                        .update("UPDATE gf_property_lease SET status='TERMINATED',renewal_status=CASE WHEN renewal_status='PENDING' THEN 'CANCELED' ELSE renewal_status END WHERE id=?", leaseId);
                }
                case "RENEW_APPROVE" -> {
                    expect(row, "SIGNED");
                    if (!"PENDING".equals(row.get("renewalStatus")))
                        throw fail("没有待审核的续租申请");
                    LocalDate start = date(input.renewalStartDate()), end = date(input.renewalEndDate());
                    if (!start.isAfter(date(text(row, "endDate"))))
                        throw fail("续租开始日必须晚于原合同到期日");
                    Draft draft = new Draft(id(row, "propertyId"), id(row, "tenantId"), id(row, "landlordId"), renewalTitle(text(row, "title")), text(row, "landlordName"), text(row, "tenantName"), start
                        .toString(), end.toString(), input.monthlyRent() == null
                            ? (BigDecimal)row.get("monthlyRent")
                            : input.monthlyRent(), input.deposit() == null
                                ? (BigDecimal)row.get("deposit")
                                : input.deposit(), text(row, "terms"), null, null);
                    renewalId = insertDraft(draft, actor, leaseId);
                    db.jdbc
                        .update("UPDATE gf_property_lease SET renewal_status='APPROVED',renewal_lease_id=? WHERE id=?", renewalId, leaseId);
                }
                case "RENEW_REJECT" -> {
                    if (!"PENDING".equals(row.get("renewalStatus")))
                        throw fail("没有待审核的续租申请");
                    reason(input);
                    db.jdbc
                        .update("UPDATE gf_property_lease SET renewal_status='REJECTED',renewal_reason=? WHERE id=?", input
                            .reason(), leaseId);
                }
                case "MOVE_OUT_RESOLVE" -> {
                    if (!"PENDING".equals(row.get("moveOutStatus")))
                        throw fail("没有待处理的退租申请");
                    reason(input);
                    db.jdbc.update("UPDATE gf_property_lease SET move_out_status='RESOLVED' WHERE id=?", leaseId);
                }
                default -> throw fail("后台不支持该操作，合同须由双方本人确认");
            }
        } else {
            switch (action) {
                case "SIGN" -> {
                    expect(row, "PENDING_SIGNATURE");
                    if (date(text(row, "endDate")).isBefore(LocalDate.now()))
                        throw fail("合同租期已经结束，请联系管理员");
                    String prefix = actorType.equals("TENANT") ? "tenant" : "landlord";
                    if (row.get(prefix + "SignedAt") != null)
                        throw fail("您已确认该合同，无需重复签订");
                    if (!Boolean.TRUE.equals(input.accepted()) || !Objects.equals(text(row, "contentHash"), input
                        .contentHash()))
                        throw fail("请阅读并确认最新合同全文");
                    if (!hash(row).equals(text(row, "contentHash")))
                        throw fail("合同正文校验失败，请联系管理员");
                    if (input.signedName() == null || !input.signedName().trim().equals(text(row, prefix + "Name")))
                        throw fail("签署姓名须与合同当事人姓名一致");
                    overlap(row);
                    String column = prefix.equals("tenant") ? "tenant" : "landlord";
                    db.jdbc
                        .update("UPDATE gf_property_lease SET " + column + "_signed_at=?," + column + "_signed_name=? WHERE id=?", LocalDateTime
                            .now(), input.signedName().trim(), leaseId);
                    String other = prefix.equals("tenant") ? "landlordSignedAt" : "tenantSignedAt";
                    if (row.get(other) != null)
                        db.jdbc.update("UPDATE gf_property_lease SET status='SIGNED' WHERE id=?", leaseId);
                }
                case "DECLINE" -> {
                    expect(row, "PENDING_SIGNATURE");
                    reason(input);
                    db.jdbc.update("UPDATE gf_property_lease SET status='VOID' WHERE id=?", leaseId);
                    releaseRenewal(row);
                }
                case "RENEW_REQUEST" -> {
                    tenant(row, actor);
                    expect(row, "SIGNED");
                    long days = ChronoUnit.DAYS.between(LocalDate.now(), date(text(row, "endDate")));
                    if (days < 0 || days > settings().getRenewalWindowDays())
                        throw fail("尚未进入续租窗口或合同已过期，请联系客服");
                    if (Set.of("PENDING", "APPROVED").contains(text(row, "renewalStatus")))
                        throw fail("已有进行中的续租申请或合同");
                    LocalDate requested = date(input.requestedEndDate());
                    if (!requested.isAfter(date(text(row, "endDate"))) || requested.isAfter(date(text(row, "endDate"))
                        .plusYears(20)))
                        throw fail("期望续租结束日应晚于当前到期日且不超过20年");
                    if ("PENDING".equals(row.get("moveOutStatus")))
                        throw fail("退租申请处理中，请先联系客服处理");
                    db.jdbc
                        .update("UPDATE gf_property_lease SET renewal_status='PENDING',requested_end_date=?,renewal_reason=? WHERE id=?", requested, input
                            .reason(), leaseId);
                }
                case "RENEW_CANCEL" -> {
                    tenant(row, actor);
                    if (!"PENDING".equals(row.get("renewalStatus")))
                        throw fail("仅待审核续租申请可撤回");
                    db.jdbc.update("UPDATE gf_property_lease SET renewal_status='CANCELED' WHERE id=?", leaseId);
                }
                case "MOVE_OUT_REQUEST" -> {
                    tenant(row, actor);
                    expect(row, "SIGNED");
                    reason(input);
                    if ("PENDING".equals(row.get("moveOutStatus")))
                        throw fail("退租申请已提交");
                    if (Set.of("PENDING", "APPROVED").contains(text(row, "renewalStatus")))
                        throw fail("请先处理进行中的续租申请或续租合同");
                    db.jdbc
                        .update("UPDATE gf_property_lease SET move_out_status='PENDING',move_out_reason=? WHERE id=?", input
                            .reason(), leaseId);
                }
                default -> throw fail("不支持的合同操作");
            }
        }
        db.jdbc.update("UPDATE gf_property_lease SET version=version+1,update_time=? WHERE id=?", LocalDateTime
            .now(), leaseId);
        log(leaseId, actorType, actor, action, input.reason(), text(row, "contentHash"));
        if (!action.equals("VOID") || row.get("publishedAt") != null)
            notifyParties(row, "租赁合同进度更新", noticeMessage(action, row));
        return renewalId == null ? Map.of() : Map.of("renewalLeaseId", renewalId.toString());
    }

    private String noticeMessage(String action, Map<String, Object> row) {
        return text(row, "title") + "：" + switch (action) {
            case "PUBLISH" -> "请双方阅读合同全文并确认签订";
            case "SIGN" -> "一方已确认签订，请查看双方签订进度";
            case "RENEW_REQUEST" -> "承租人提交了续租申请";
            case "RENEW_APPROVE" -> "续租申请已审核，待发布续租合同并重新签订";
            case "RENEW_REJECT" -> "续租申请未通过，请查看处理记录";
            case "RENEW_CANCEL" -> "续租申请已撤回";
            case "MOVE_OUT_REQUEST" -> "承租人申请退租，请联系办理交接";
            case "MOVE_OUT_RESOLVE" -> "退租申请已处理，请查看处理记录";
            case "TERMINATE" -> "合同已终止，请查看处理原因";
            default -> "合同已作废，请查看处理记录";
        };
    }

    private void releaseRenewal(Map<String, Object> row) {
        if (row.get("previousLeaseId") != null)
            db.jdbc
                .update("UPDATE gf_property_lease SET renewal_status='CANCELED',renewal_lease_id=NULL,version=version+1,update_time=? WHERE id=? AND renewal_lease_id=?", LocalDateTime
                    .now(), id(row, "previousLeaseId"), id(row, "id"));
    }

    private Map<String, Object> lock(long leaseId) {
        var ref = db.one("SELECT property_id FROM gf_property_lease WHERE id=?", leaseId);
        // Same lock order for publishing, signing, renewal and property deletion.
        db.one("SELECT id FROM gf_property WHERE id=? FOR UPDATE", id(ref, "propertyId"));
        return db.one("SELECT * FROM gf_property_lease WHERE id=? FOR UPDATE", leaseId);
    }

    private void overlap(Map<String, Object> row) {
        Long count = db.jdbc
            .queryForObject("SELECT COUNT(*) FROM gf_property_lease WHERE property_id=? AND id<>? AND status IN('PENDING_SIGNATURE','SIGNED') AND start_date<=? AND end_date>=?", Long.class, id(row, "propertyId"), id(row, "id"), date(text(row, "endDate")), date(text(row, "startDate")));
        if (count != null && count > 0)
            throw fail("该房源此租期已有待签或已签合同，请先核实冲突");
    }

    public void guardForPropertyDelete(long propertyId) {
        Long count = db.jdbc
            .queryForObject("SELECT COUNT(*) FROM gf_property_lease WHERE property_id=? AND status IN('DRAFT','PENDING_SIGNATURE','SIGNED') AND end_date>=?", Long.class, propertyId, LocalDate
                .now());
        if (count != null && count > 0)
            throw fail("房源有关联草稿、待签或有效租赁合同，请先处理合同再删除");
    }

    public Map<String, Object> reminders(int page, int size) {
        operations.platform();
        return db.page("FROM gf_property_lease_reminder r JOIN gf_property_lease l ON l.id=r.lease_id", List
            .of(), "r.*,l.title", "r.id DESC", page, size);
    }

    public int maintain() {
        var config = settings();
        if (!config.isRemindersEnabled())
            return 0;
        int max = config.getReminderDays().stream().mapToInt(Integer::intValue).max().orElse(30), count = 0;
        // Completed leases are removed from each batch to prevent starvation beyond the first 500 rows.
        StringBuilder bucket = new StringBuilder("CASE ");
        var args = new ArrayList<Object>();
        for (Integer day : config.getReminderDays().stream().sorted().toList()) {
            bucket.append("WHEN l.end_date<=? THEN ? ");
            args.add(LocalDate.now().plusDays(day));
            args.add(day);
        }
        bucket.append("ELSE NULL END");
        args.add(LocalDate.now().minusDays(30));
        args.add(LocalDate.now().plusDays(max));
        var candidates = db
            .rows("SELECT l.id FROM gf_property_lease l WHERE NOT EXISTS(SELECT 1 FROM gf_property_lease_reminder r WHERE r.lease_id=l.id AND r.end_date=l.end_date AND r.threshold_days=" + bucket + ") AND l.status='SIGNED' AND l.end_date>=? AND l.end_date<=? ORDER BY l.end_date,l.id LIMIT 500", args
                .toArray());
        for (var candidate : candidates) {
            try {
                Integer sent = new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(tx -> {
                        var row = lock(id(candidate, "id"));
                        if (!"SIGNED".equals(row.get("status")))
                            return 0;
                        long days = ChronoUnit.DAYS.between(LocalDate.now(), date(text(row, "endDate")));
                        Integer threshold = config.getReminderDays()
                            .stream()
                            .filter(x -> days <= x)
                            .min(Integer::compareTo)
                            .orElse(null);
                        if (threshold == null || !db
                            .rows("SELECT id FROM gf_property_lease_reminder WHERE lease_id=? AND threshold_days=? AND end_date=?", id(row, "id"), threshold, date(text(row, "endDate")))
                            .isEmpty())
                            return 0;
                        db.jdbc
                            .update("INSERT INTO gf_property_lease_reminder(id,lease_id,threshold_days,end_date,state,create_time) VALUES(?,?,?,?,'SENT',?)", IdUtil
                                .getSnowflakeNextId(), id(row, "id"), threshold, date(text(row, "endDate")), LocalDateTime
                                    .now());
                        notifyParties(row, days <= 0
                            ? "租赁合同到期提醒"
                            : "租赁合同临期提醒", text(row, "title") + "到期日为" + text(row, "endDate") + "，请及时确认续租或退租交接");
                        return 1;
                    });
                count += sent == null ? 0 : sent;
            } catch (Exception e) {
                log.warn("租赁合同提醒处理失败 leaseId={}", candidate.get("id"), e);
            }
        }
        return count;
    }

    private void notifyParties(Map<String, Object> row, String title, String content) {
        notices.send(id(row, "tenantId"), "PROPERTY_LEASE", id(row, "id"), title, content);
        notices.send(id(row, "landlordId"), "PROPERTY_LEASE", id(row, "id"), title, content);
    }

    private void log(long lease, String actorType, Long actor, String action, String reason, String hash) {
        db.jdbc
            .update("INSERT INTO gf_property_lease_log(id,lease_id,actor_type,actor_id,action,reason,content_hash,create_time) VALUES(?,?,?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), lease, actorType, actor, action, reason, hash, LocalDateTime.now());
    }

    private void authorize(Map<String, Object> row, Long user, boolean admin) {
        if (!admin && (user == null || row
            .get("publishedAt") == null || (id(row, "tenantId") != user && id(row, "landlordId") != user)))
            throw fail("合同不存在或无权访问");
    }

    private void activeUser(Long user) {
        if (user == null)
            throw fail("请先登录");
        db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 AND account_status=1", user);
    }

    private void tenant(Map<String, Object> row, long actor) {
        if (id(row, "tenantId") != actor)
            throw fail("仅承租人可提交该申请");
    }

    private void checkVersion(Map<String, Object> row, Long version) {
        if (version == null || id(row, "version") != version)
            throw fail("合同已更新，请刷新后再操作");
    }

    private void expect(Map<String, Object> row, String... states) {
        if (!Arrays.asList(states).contains(text(row, "status")))
            throw fail("当前合同状态不支持此操作");
    }

    private void reason(Action input) {
        requireText(input.reason(), 1000, "处理原因");
    }

    private void requireText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max)
            throw fail(label + "不能为空且不超过" + max + "字");
    }

    private LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw fail("日期格式应为yyyy-MM-dd");
        }
    }

    private void money(BigDecimal value, boolean zero) {
        if (value == null || value.signum() < 0 || (!zero && value.signum() == 0) || value.scale() > 2 || value
            .compareTo(new BigDecimal("9999999999.99")) > 0)
            throw fail("租金须大于0，押金不能为负，金额最多两位小数");
    }

    private String hash(Map<String, Object> row) {
        try {
            var payload = new TreeMap<String, Object>();
            for (String key : List
                .of("id", "propertyId", "propertyTitle", "propertyAddress", "tenantId", "landlordId", "title", "landlordName", "tenantName", "startDate", "endDate", "monthlyRent", "deposit", "terms", "previousLeaseId"))
                payload.put(key, row.get(key));
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String renewalTitle(String title) {
        return title.substring(0, Math.min(title.length(), 196)) + "（续租）";
    }

    private BusinessException fail(String message) {
        return new BusinessException(message);
    }
}
