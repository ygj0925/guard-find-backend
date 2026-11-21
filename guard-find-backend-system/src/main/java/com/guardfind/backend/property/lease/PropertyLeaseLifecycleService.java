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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import com.guardfind.backend.property.operations.OperationsStore;
import com.guardfind.backend.property.operations.PropertyOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;

/** Contract-scoped offline collections, bilateral handover and deposit reconciliation. */
@Service
@RequiredArgsConstructor
public class PropertyLeaseLifecycleService {
    private final OperationsStore db;
    private final PropertyLeaseService leases;
    private final MiniappNoticeService notices;
    private final ObjectMapper json;
    private final PropertyOperationsService operations;

    public record BillInput(String kind, String title, String periodStart, String periodEnd, String dueDate,
                            BigDecimal amount) {}

    public record ScheduleInput(int cycleMonths, int dueDaysBefore, boolean includeDeposit) {}

    public record BillAction(String action, long version, String reference, String paidDate, String reason,
                             Long fileId) {}

    public record Deduction(String title, BigDecimal amount) {}

    public record DocumentInput(String kind, String date, String inventory, String condition, String water,
                                String electricity, String gas, Integer keys, List<Deduction> deductions, String note,
                                List<Long> photos) {}

    public record DocumentAction(String action, long version, String contentHash, String reason, String reference,
                                 String paidDate) {}

    public Map<String, Object> detail(long leaseId, Long actor, boolean admin) {
        leases.detail(leaseId, actor, admin);
        var bills = db.rows("SELECT * FROM gf_property_lease_bill WHERE lease_id=? ORDER BY due_date,id", leaseId);
        BigDecimal due = BigDecimal.ZERO, paid = BigDecimal.ZERO, pending = BigDecimal.ZERO;
        for (var bill : bills) {
            String status = text(bill, "status");
            BigDecimal amount = (BigDecimal)bill.get("amount");
            if ("PAID".equals(status))
                paid = paid.add(amount);
            else if (!"VOID".equals(status))
                due = due.add(amount);
            if ("PENDING_CONFIRMATION".equals(status))
                pending = pending.add(amount);
            if ("UNPAID".equals(status) && date(text(bill, "dueDate")).isBefore(LocalDate.now()))
                bill.put("status", "OVERDUE");
        }
        var documents = db.rows("SELECT * FROM gf_property_lease_document WHERE lease_id=? ORDER BY id DESC", leaseId);
        for (var document : documents) {
            document.put("content", content(document));
            document.remove("contentJson");
        }
        return Map
            .of("bills", bills, "documents", documents, "amountDue", due, "amountPaid", paid, "amountPending", pending);
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void createBill(long leaseId, long actor, BillInput input) {
        var lease = lock(leaseId, actor, true);
        billable(lease);
        unsettled(leaseId);
        insertBill(lease, input);
        audit(lease, actor, true, "BILL_CREATE", "新增账单：" + input.title());
    }

    private void insertBill(Map<String, Object> lease, BillInput input) {
        if (input == null || !Set.of("RENT", "DEPOSIT", "OTHER").contains(Objects.toString(input.kind(), "")))
            throw fail("请选择账单类型");
        required(input.title(), 200, "账单名称");
        money(input.amount(), false);
        var start = date(input.periodStart());
        var end = date(input.periodEnd());
        var due = date(input.dueDate());
        if (!db
            .rows("SELECT id FROM gf_property_lease_bill WHERE lease_id=? AND kind=? AND title=? AND period_start=? AND period_end=? AND due_date=? AND amount=? AND status<>'VOID'", id(lease, "id"), input
                .kind(), input.title().trim(), start, end, due, input.amount())
            .isEmpty())
            throw fail("已有相同账单，请勿重复提交");
        if (end.isBefore(start) || start.isBefore(date(text(lease, "startDate"))) || end
            .isAfter(date(text(lease, "endDate"))))
            throw fail("账单费用期间须在合同租期内");
        if ("RENT".equals(input.kind()) && !db
            .rows("SELECT id FROM gf_property_lease_bill WHERE lease_id=? AND kind='RENT' AND status<>'VOID' AND period_start<=? AND period_end>=?", id(lease, "id"), end, start)
            .isEmpty())
            throw fail("此期间已有租金账单，请先核对或作废原账单");
        if ("DEPOSIT".equals(input.kind())) {
            BigDecimal existing = db.jdbc()
                .queryForObject("SELECT COALESCE(SUM(amount),0) FROM gf_property_lease_bill WHERE lease_id=? AND kind='DEPOSIT' AND status<>'VOID'", BigDecimal.class, id(lease, "id"));
            if (existing.add(input.amount()).compareTo((BigDecimal)lease.get("deposit")) > 0)
                throw fail("押金账单总额不能超过合同约定押金");
        }
        var now = LocalDateTime.now();
        db.jdbc()
            .update("INSERT INTO gf_property_lease_bill(id,lease_id,kind,title,period_start,period_end,due_date,amount,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), id(lease, "id"), input.kind(), input.title().trim(), start, end, due, input
                    .amount(), now, now);
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void generate(long leaseId, long actor, ScheduleInput input) {
        var lease = lock(leaseId, actor, true);
        state(lease, "SIGNED");
        unsettled(leaseId);
        if (input == null || !Set.of(1, 3, 6, 12).contains(input.cycleMonths()) || input.dueDaysBefore() < 0 || input
            .dueDaysBefore() > 30)
            throw fail("付款周期须为1、3、6或12个月，提前缴费天数为0至30天");
        if (!db
            .rows("SELECT id FROM gf_property_lease_bill WHERE lease_id=? AND kind='RENT' AND status<>'VOID'", leaseId)
            .isEmpty())
            throw fail("已有租金账单，不能重复生成；请逐笔核对");
        var start = date(text(lease, "startDate"));
        var end = date(text(lease, "endDate"));
        BigDecimal monthly = (BigDecimal)lease.get("monthlyRent");
        for (int month = 0; start.plusMonths(month).isBefore(end.plusDays(1)); month += input.cycleMonths()) {
            LocalDate from = start.plusMonths(month), until = start.plusMonths(month + input.cycleMonths())
                .minusDays(1);
            if (until.isAfter(end))
                until = end;
            BigDecimal amount = BigDecimal.ZERO;
            for (int offset = 0; offset < input.cycleMonths(); offset++) {
                var monthStart = start.plusMonths(month + offset);
                if (monthStart.isAfter(until))
                    break;
                var nextMonth = start.plusMonths(month + offset + 1);
                var actualEnd = until.plusDays(1).isBefore(nextMonth) ? until.plusDays(1) : nextMonth;
                amount = amount.add(monthly.multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(monthStart, actualEnd)))
                    .divide(BigDecimal.valueOf(ChronoUnit.DAYS
                        .between(monthStart, nextMonth)), 8, RoundingMode.HALF_UP));
            }
            insertBill(lease, new BillInput("RENT", "租金 " + from + " 至 " + until, from.toString(), until
                .toString(), from.minusDays(input.dueDaysBefore()).toString(), amount
                    .setScale(2, RoundingMode.HALF_UP)));
        }
        if (input.includeDeposit() && ((BigDecimal)lease.get("deposit")).signum() > 0)
            insertBill(lease, new BillInput("DEPOSIT", "合同押金", start.toString(), start.toString(), start
                .toString(), (BigDecimal)lease.get("deposit")));
        audit(lease, actor, true, "BILL_GENERATE", "按每" + input.cycleMonths() + "个月生成租金账单，末期按实际租期比例计费");
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void billAction(long leaseId, long billId, long actor, boolean admin, BillAction input) {
        var lease = lock(leaseId, actor, admin);
        var bill = db.one("SELECT * FROM gf_property_lease_bill WHERE id=? AND lease_id=? FOR UPDATE", billId, leaseId);
        if (input == null)
            throw fail("请选择账单操作");
        version(bill, input.version());
        String action = Objects.toString(input.action(), "");
        switch (action) {
            case "SUBMIT" -> {
                tenant(lease, actor, admin);
                state(bill, "UNPAID");
                required(input.reference(), 1000, "付款流水号或说明");
                var paid = date(input.paidDate());
                if (paid.isAfter(LocalDate.now()))
                    throw fail("付款日期不能晚于今天");
                if (input.fileId() != null)
                    checkFile(leaseId, input.fileId());
                db.jdbc()
                    .update("UPDATE gf_property_lease_bill SET status='PENDING_CONFIRMATION',payment_reference=?,payment_file_id=?,paid_date=? WHERE id=?", input
                        .reference()
                        .trim(), input.fileId(), paid, billId);
            }
            case "WITHDRAW" -> {
                tenant(lease, actor, admin);
                state(bill, "PENDING_CONFIRMATION");
                db.jdbc()
                    .update("UPDATE gf_property_lease_bill SET status='UNPAID',payment_reference=NULL,payment_file_id=NULL,paid_date=NULL WHERE id=?", billId);
            }
            case "CONFIRM" -> {
                receiver(lease, actor, admin);
                state(bill, "PENDING_CONFIRMATION");
                db.jdbc()
                    .update("UPDATE gf_property_lease_bill SET status='PAID',confirmed_at=? WHERE id=?", LocalDateTime
                        .now(), billId);
            }
            case "REJECT" -> {
                receiver(lease, actor, admin);
                state(bill, "PENDING_CONFIRMATION");
                required(input.reason(), 1000, "退回原因");
                db.jdbc().update("UPDATE gf_property_lease_bill SET status='UNPAID' WHERE id=?", billId);
            }
            case "VOID" -> {
                if (!admin)
                    throw fail("仅管理员可作废账单");
                unsettled(leaseId);
                state(bill, "UNPAID");
                required(input.reason(), 1000, "作废原因");
                db.jdbc().update("UPDATE gf_property_lease_bill SET status='VOID' WHERE id=?", billId);
            }
            default -> throw fail("不支持的账单操作");
        }
        db.jdbc()
            .update("UPDATE gf_property_lease_bill SET version=version+1,update_time=? WHERE id=?", LocalDateTime
                .now(), billId);
        audit(lease, actor, admin, "BILL_" + action, text(bill, "title") + (input.reason() == null
            ? ""
            : "：" + input.reason()));
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void createDocument(long leaseId, long actor, DocumentInput input) {
        var lease = lock(leaseId, actor, true);
        billable(lease);
        if (input == null || !Set.of("CHECK_IN", "CHECK_OUT", "SETTLEMENT")
            .contains(Objects.toString(input.kind(), "")))
            throw fail("请选择交接或结算类型");
        if (!db
            .rows("SELECT id FROM gf_property_lease_document WHERE lease_id=? AND kind=? AND status<>'VOID'", leaseId, input
                .kind())
            .isEmpty())
            throw fail("已有此类型记录，请先处理或作废原记录");
        required(input.note(), 2000, "交接或结算说明");
        var date = date(input.date());
        if (date.isAfter(LocalDate.now()))
            throw fail("请在实际交接或结算完成后填写日期");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date.toString());
        body.put("note", input.note().trim());
        var photos = input.photos() == null ? List.<Long>of() : input.photos();
        if (photos.size() > 8)
            throw fail("每份记录最多8张照片");
        for (var photo : photos)
            checkFile(leaseId, photo);
        body.put("photos", photos.stream().map(Object::toString).toList());
        if ("SETTLEMENT".equals(input.kind())) {
            if (db
                .rows("SELECT id FROM gf_property_lease_document WHERE lease_id=? AND kind='CHECK_OUT' AND status='CONFIRMED'", leaseId)
                .isEmpty())
                throw fail("请先完成双方确认的退租交接单");
            if (!db
                .rows("SELECT id FROM gf_property_lease_bill WHERE lease_id=? AND status NOT IN('PAID','VOID')", leaseId)
                .isEmpty())
                throw fail("请先处理全部未结账单；未发生的费用请核实后作废");
            var held = db.jdbc()
                .queryForObject("SELECT COALESCE(SUM(amount),0) FROM gf_property_lease_bill WHERE lease_id=? AND kind='DEPOSIT' AND status='PAID'", BigDecimal.class, leaseId);
            var deductions = input.deductions() == null ? List.<Deduction>of() : input.deductions();
            if (deductions.size() > 30)
                throw fail("扣款明细最多30项");
            var total = BigDecimal.ZERO;
            for (var item : deductions) {
                if (item == null)
                    throw fail("扣款明细无效");
                required(item.title(), 200, "扣款项目与原因");
                money(item.amount(), false);
                total = total.add(item.amount());
            }
            if (total.compareTo(held) > 0)
                throw fail("扣款不能超过已确认实收押金；额外费用请先另建账单结清");
            body.put("depositHeld", held);
            body.put("deductions", deductions);
            body.put("deductionTotal", total);
            body.put("refundAmount", held.subtract(total));
        } else {
            unsettled(leaseId);
            required(input.inventory(), 5000, "家具家电及钥匙清单");
            required(input.condition(), 5000, "房屋现状与损坏说明");
            if (input.keys() == null || input.keys() < 0 || input.keys() > 100)
                throw fail("钥匙数量应为0至100");
            body.put("inventory", input.inventory().trim());
            body.put("condition", input.condition().trim());
            body.put("keys", input.keys());
            body.put("water", meter(input.water()));
            body.put("electricity", meter(input.electricity()));
            body.put("gas", meter(input.gas()));
        }
        String serialized;
        try {
            serialized = json.writeValueAsString(body);
        } catch (Exception e) {
            throw fail("记录保存失败");
        }
        var now = LocalDateTime.now();
        db.jdbc()
            .update("INSERT INTO gf_property_lease_document(id,lease_id,kind,content_json,content_hash,create_time,update_time) VALUES(?,?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), leaseId, input.kind(), serialized, hash(serialized), now, now);
        audit(lease, actor, true, input.kind() + "_CREATE", "已发送" + documentName(input.kind()) + "，请双方核对确认");
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void documentAction(long leaseId, long documentId, long actor, boolean admin, DocumentAction input) {
        var lease = lock(leaseId, actor, admin);
        var doc = db
            .one("SELECT * FROM gf_property_lease_document WHERE id=? AND lease_id=? FOR UPDATE", documentId, leaseId);
        if (input == null)
            throw fail("请选择记录操作");
        version(doc, input.version());
        String action = Objects.toString(input.action(), "");
        switch (action) {
            case "CONFIRM" -> {
                if (admin)
                    throw fail("须由租赁双方本人确认");
                state(doc, "PENDING");
                if (!Objects.equals(input.contentHash(), doc.get("contentHash")) || !hash(text(doc, "contentJson"))
                    .equals(doc.get("contentHash")))
                    throw fail("记录内容已变化，请刷新后重新核对");
                String own = id(lease, "tenantId") == actor ? "tenant" : "landlord";
                String other = own.equals("tenant") ? "landlordConfirmedAt" : "tenantConfirmedAt";
                if (doc.get(own + "ConfirmedAt") != null)
                    throw fail("您已确认，请等待另一方");
                db.jdbc()
                    .update("UPDATE gf_property_lease_document SET " + own + "_confirmed_at=? WHERE id=?", LocalDateTime
                        .now(), documentId);
                if (doc.get(other) != null) {
                    String next = "CONFIRMED";
                    if ("SETTLEMENT".equals(doc.get("kind")))
                        next = new BigDecimal(content(doc).get("refundAmount").toString()).signum() == 0
                            ? "CLOSED"
                            : "REFUND_PENDING";
                    db.jdbc().update("UPDATE gf_property_lease_document SET status=? WHERE id=?", next, documentId);
                }
            }
            case "DISPUTE" -> {
                if (admin)
                    throw fail("此操作由合同双方提出");
                state(doc, "PENDING");
                required(input.reason(), 1000, "异议说明");
                db.jdbc().update("UPDATE gf_property_lease_document SET status='DISPUTED' WHERE id=?", documentId);
            }
            case "VOID" -> {
                if (!admin)
                    throw fail("仅管理员可作废记录");
                state(doc, "PENDING", "DISPUTED");
                required(input.reason(), 1000, "作废原因");
                db.jdbc().update("UPDATE gf_property_lease_document SET status='VOID' WHERE id=?", documentId);
            }
            case "REFUND" -> {
                receiver(lease, actor, admin);
                state(doc, "REFUND_PENDING");
                required(input.reference(), 1000, "退款流水号或说明");
                var paid = date(input.paidDate());
                if (paid.isAfter(LocalDate.now()))
                    throw fail("退款日期不能晚于今天");
                db.jdbc()
                    .update("UPDATE gf_property_lease_document SET status='REFUNDED',refund_reference=?,refund_date=? WHERE id=?", input
                        .reference()
                        .trim(), paid, documentId);
            }
            case "RECEIVED" -> {
                tenant(lease, actor, admin);
                state(doc, "REFUNDED");
                db.jdbc()
                    .update("UPDATE gf_property_lease_document SET status='CLOSED',refund_received_at=? WHERE id=?", LocalDateTime
                        .now(), documentId);
            }
            default -> throw fail("不支持的记录操作");
        }
        db.jdbc()
            .update("UPDATE gf_property_lease_document SET version=version+1,update_time=? WHERE id=?", LocalDateTime
                .now(), documentId);
        audit(lease, actor, admin, text(doc, "kind") + "_" + action, documentName(text(doc, "kind")) + (input
            .reason() == null ? "" : "：" + input.reason()));
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void reopen(long leaseId, long actor) {
        var lease = lock(leaseId, actor, true);
        if (!"TERMINATED".equals(lease.get("status")) && !("SIGNED".equals(lease
            .get("status")) && date(text(lease, "endDate")).isBefore(LocalDate.now())))
            throw fail("请先终止合同或等待租期结束");
        var latest = operations.requireLeaseReadyForReopening(id(lease, "propertyId"));
        if (latest.isEmpty() || latest.getAsLong() != leaseId)
            throw fail("请从房源最近一份已签租约完成交接与结算后恢复接待");
        var property = db
            .one("SELECT availability FROM gf_property_operation WHERE property_id=?", id(lease, "propertyId"));
        if (!"RENTED".equals(property.get("availability")))
            throw fail("仅已出租房源可从此处恢复接待；暂停或已售房源请由运营核实");
        db.jdbc()
            .update("UPDATE gf_property_operation SET availability='ACTIVE',reason=NULL,reopen_date=NULL,version=version+1,update_time=? WHERE property_id=?", LocalDateTime
                .now(), id(lease, "propertyId"));
        audit(lease, actor, true, "PROPERTY_REOPEN", "交接与结算已完成，管理员确认房源重新开放接待");
    }

    private Map<String, Object> lock(long leaseId, long actor, boolean admin) {
        leases.detail(leaseId, actor, admin);
        var ref = db.one("SELECT property_id FROM gf_property_lease WHERE id=?", leaseId);
        db.one("SELECT id FROM gf_property WHERE id=? FOR UPDATE", id(ref, "propertyId"));
        var row = db.one("SELECT * FROM gf_property_lease WHERE id=? FOR UPDATE", leaseId);
        // Recheck participant access after taking the lock (a draft may have changed).
        leases.detail(leaseId, actor, admin);
        return row;
    }

    private void checkFile(long leaseId, Long fileId) {
        if (fileId == null || db
            .rows("SELECT id FROM gf_property_lease_file WHERE id=? AND lease_id=?", fileId, leaseId)
            .isEmpty())
            throw fail("附件不存在或不属于此合同");
    }

    private void billable(Map<String, Object> lease) {
        state(lease, "SIGNED", "TERMINATED");
    }

    private void unsettled(long leaseId) {
        if (!db
            .rows("SELECT id FROM gf_property_lease_document WHERE lease_id=? AND kind='SETTLEMENT' AND status<>'VOID'", leaseId)
            .isEmpty())
            throw fail("已有结算单，不能再变更费用或交接；待确认结算可先作废后重建");
    }

    private void audit(Map<String, Object> lease, long actor, boolean admin, String action, String reason) {
        if (reason.length() > 1000)
            throw fail("处理说明过长，请精简后重试");
        String role = admin ? "ADMIN" : id(lease, "tenantId") == actor ? "TENANT" : "LANDLORD";
        db.jdbc()
            .update("INSERT INTO gf_property_lease_log(id,lease_id,actor_type,actor_id,action,reason,create_time) VALUES(?,?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), id(lease, "id"), role, actor, action, reason, LocalDateTime.now());
        db.jdbc()
            .update("UPDATE gf_property_lease SET version=version+1,update_time=? WHERE id=?", LocalDateTime
                .now(), id(lease, "id"));
        notices.send(id(lease, "tenantId"), "PROPERTY_LEASE", id(lease, "id"), "租约履行进度更新", reason);
        notices.send(id(lease, "landlordId"), "PROPERTY_LEASE", id(lease, "id"), "租约履行进度更新", reason);
    }

    private void tenant(Map<String, Object> lease, long actor, boolean admin) {
        if (admin || id(lease, "tenantId") != actor)
            throw fail("仅承租人本人可操作");
    }

    private void receiver(Map<String, Object> lease, long actor, boolean admin) {
        if (!admin && id(lease, "landlordId") != actor)
            throw fail("仅出租人或管理员可确认收付款");
    }

    private void version(Map<String, Object> row, long version) {
        if (id(row, "version") != version)
            throw fail("记录已更新，请刷新后再操作");
    }

    private void state(Map<String, Object> row, String... allowed) {
        if (!Arrays.asList(allowed).contains(text(row, "status")))
            throw fail("当前状态不支持此操作");
    }

    private Map<String, Object> content(Map<String, Object> row) {
        try {
            return json.readValue(text(row, "contentJson"), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw fail("记录内容读取失败");
        }
    }

    private String hash(String content) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String meter(String value) {
        if (value == null || value.isBlank())
            return "未配备/未记录";
        try {
            var number = new BigDecimal(value);
            money(number, true);
            return number.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            throw fail("表读数须为非负数，最多两位小数");
        }
    }

    private void required(String value, int max, String label) {
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
            throw fail("金额或读数应为有效非负数，最多两位小数");
    }

    private String documentName(String kind) {
        return switch (kind) {
            case "CHECK_IN" -> "入住交接单";
            case "CHECK_OUT" -> "退租交接单";
            default -> "押金结算单";
        };
    }

    private BusinessException fail(String message) {
        return new BusinessException(message);
    }
}
