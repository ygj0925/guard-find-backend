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

package com.guardfind.backend.oa.repository;

import com.guardfind.backend.oa.model.OaDeptOptionResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetLedgerResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetResp;
import com.guardfind.backend.oa.model.OaPurchaseItemResp;
import com.guardfind.backend.oa.model.OaPurchaseReceiptLineResp;
import com.guardfind.backend.oa.model.OaPurchaseReceiptResp;
import com.guardfind.backend.oa.model.OaPurchaseResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购预算、采购单与验收的显式租户 SQL；预算额度只经条件更新变动，任何一步影响行数不为 1 都由调用方回滚。
 */
@Repository
public class OaPurchaseRepository {

    private static final String BUDGET_SELECT = """
        SELECT b.id, b.dept_id, d.name AS dept_name, b.budget_year, b.amount, b.used_amount, b.occupied_amount,
               b.remark, b.version, COALESCE(b.update_time, b.create_time) AS update_time
          FROM oa_purchase_budget b
          LEFT JOIN sys_dept d ON d.tenant_id = b.tenant_id AND d.id = b.dept_id AND d.deleted = 0
         WHERE b.tenant_id = ?
        """;

    private static final RowMapper<OaPurchaseBudgetResp> BUDGET_MAPPER = (rs, rowNum) -> {
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal used = rs.getBigDecimal("used_amount");
        BigDecimal occupied = rs.getBigDecimal("occupied_amount");
        return new OaPurchaseBudgetResp(rs.getLong("id"), rs.getLong("dept_id"), rs.getString("dept_name"), rs
            .getInt("budget_year"), amount, used, occupied, amount.subtract(used).subtract(occupied), rs
                .getString("remark"), rs.getInt("version"), timestamp(rs, "update_time"));
    };

    private static final RowMapper<BudgetRow> BUDGET_ROW_MAPPER = (rs, rowNum) -> new BudgetRow(rs.getLong("id"), rs
        .getLong("dept_id"), rs.getInt("budget_year"), rs.getBigDecimal("amount"), rs.getBigDecimal("used_amount"), rs
            .getBigDecimal("occupied_amount"), rs.getInt("version"));

    private static final String PURCHASE_FROM = """
          FROM oa_purchase p
          JOIN oa_application a ON a.tenant_id = p.tenant_id AND a.id = p.application_id
          LEFT JOIN sys_user u ON u.tenant_id = p.tenant_id AND u.id = p.applicant_id
          LEFT JOIN sys_user o ON o.tenant_id = p.tenant_id AND o.id = p.orderer_id
          LEFT JOIN sys_dept d ON d.tenant_id = p.tenant_id AND d.id = p.dept_id AND d.deleted = 0
         WHERE p.tenant_id = ?
        """;

    private static final String PURCHASE_SELECT = """
        SELECT p.id, p.application_id, a.title, a.status AS application_status, p.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, p.dept_id, d.name AS dept_name,
               p.purchase_no, p.total_amount, p.item_count, p.expected_date, p.status, p.supplier, p.order_no,
               p.order_date, p.orderer_id, COALESCE(NULLIF(o.nickname, ''), o.username) AS orderer_name,
               p.ordered_time, p.received_time, p.create_time
        """ + PURCHASE_FROM;

    private static final RowMapper<OaPurchaseResp> PURCHASE_MAPPER = (rs, rowNum) -> new OaPurchaseResp(rs
        .getLong("id"), rs.getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getLong("dept_id"), rs
                .getString("dept_name"), rs.getString("purchase_no"), rs.getBigDecimal("total_amount"), rs
                    .getInt("item_count"), date(rs, "expected_date"), rs.getString("status"), rs
                        .getString("supplier"), rs
                            .getString("order_no"), date(rs, "order_date"), nullableLong(rs, "orderer_id"), rs
                                .getString("orderer_name"), timestamp(rs, "ordered_time"), timestamp(rs, "received_time"), timestamp(rs, "create_time"));

    private static final RowMapper<PurchaseRow> PURCHASE_ROW_MAPPER = (rs, rowNum) -> new PurchaseRow(rs
        .getLong("id"), rs.getLong("application_id"), rs.getLong("applicant_id"), rs.getLong("budget_id"), rs
            .getString("purchase_no"), rs.getBigDecimal("total_amount"), rs.getString("status"));

    private static final RowMapper<OaPurchaseItemResp> ITEM_MAPPER = (rs, rowNum) -> new OaPurchaseItemResp(rs
        .getLong("id"), rs.getInt("line_no"), rs.getString("item_name"), rs.getString("category"), rs
            .getString("supply_code"), rs.getString("spec"), rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs
                .getBigDecimal("amount"), rs.getInt("received_qty"));

    private final JdbcTemplate jdbcTemplate;

    public OaPurchaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String deptName(long tenantId, long deptId) {
        List<String> rows = jdbcTemplate.query("""
            SELECT name FROM sys_dept WHERE tenant_id = ? AND id = ? AND deleted = 0
            """, (rs, rowNum) -> rs.getString("name"), tenantId, deptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 部门选项名称为从根到本部门的完整路径，避免同名部门（如多个“研发一组”）无法区分 */
    public List<OaDeptOptionResp> depts(long tenantId) {
        record DeptRow(long id, String name, long parentId) {}
        List<DeptRow> rows = jdbcTemplate.query("""
            SELECT id, name, parent_id FROM sys_dept WHERE tenant_id = ? AND deleted = 0 ORDER BY id
            """, (rs, rowNum) -> new DeptRow(rs.getLong("id"), rs.getString("name"), rs
            .getLong("parent_id")), tenantId);
        Map<Long, DeptRow> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        List<OaDeptOptionResp> options = new ArrayList<>(rows.size());
        for (DeptRow row : rows) {
            Deque<String> path = new ArrayDeque<>();
            DeptRow current = row;
            for (int depth = 0; current != null && depth < 20; depth++) {
                path.addFirst(current.name());
                current = byId.get(current.parentId());
            }
            options.add(new OaDeptOptionResp(row.id(), String.join(" / ", path)));
        }
        return options;
    }

    public BudgetRow lockBudget(long tenantId, long deptId, int year) {
        List<BudgetRow> rows = jdbcTemplate.query("""
            SELECT id, dept_id, budget_year, amount, used_amount, occupied_amount, version
              FROM oa_purchase_budget WHERE tenant_id = ? AND dept_id = ? AND budget_year = ?
             FOR UPDATE
            """, BUDGET_ROW_MAPPER, tenantId, deptId, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaPurchaseBudgetResp> budgets(long tenantId, Integer year) {
        StringBuilder sql = new StringBuilder(BUDGET_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (year != null) {
            sql.append(" AND b.budget_year = ?");
            args.add(year);
        }
        sql.append(" ORDER BY b.budget_year DESC, b.dept_id");
        return jdbcTemplate.query(sql.toString(), BUDGET_MAPPER, args.toArray());
    }

    public OaPurchaseBudgetResp budget(long tenantId, long id) {
        List<OaPurchaseBudgetResp> rows = jdbcTemplate
            .query(BUDGET_SELECT + " AND b.id = ?", BUDGET_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertBudget(long tenantId,
                             long deptId,
                             int year,
                             BigDecimal amount,
                             String remark,
                             long userId,
                             LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_purchase_budget
                  (tenant_id, dept_id, budget_year, amount, used_amount, occupied_amount, remark, version, create_user,
                   create_time)
                VALUES (?, ?, ?, ?, 0, 0, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, deptId);
            ps.setInt(3, year);
            ps.setBigDecimal(4, amount);
            ps.setString(5, remark);
            ps.setLong(6, userId);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    /**
     * 以版本号与“额度不低于已用+占用”为条件改写额度。
     */
    public int updateBudget(long tenantId,
                            long id,
                            int version,
                            BigDecimal amount,
                            String remark,
                            long userId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase_budget
               SET amount = ?, remark = ?, version = version + 1, update_user = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ? AND used_amount + occupied_amount <= ?
            """, amount, remark, userId, now, tenantId, id, version, amount);
    }

    public int occupy(long tenantId, long budgetId, BigDecimal amount, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase_budget SET occupied_amount = occupied_amount + ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND amount - used_amount - occupied_amount >= ?
            """, amount, now, tenantId, budgetId, amount);
    }

    public int consume(long tenantId, long budgetId, BigDecimal amount, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase_budget
               SET occupied_amount = occupied_amount - ?, used_amount = used_amount + ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND occupied_amount >= ?
            """, amount, amount, now, tenantId, budgetId, amount);
    }

    public int release(long tenantId, long budgetId, BigDecimal amount, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase_budget SET occupied_amount = occupied_amount - ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND occupied_amount >= ?
            """, amount, now, tenantId, budgetId, amount);
    }

    public void insertBudgetLedger(long tenantId,
                                   long budgetId,
                                   String action,
                                   BigDecimal amount,
                                   Long purchaseId,
                                   long operatorId,
                                   String remark,
                                   LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_purchase_budget_ledger
              (tenant_id, budget_id, action, amount, purchase_id, operator_id, remark, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, budgetId, action, amount, purchaseId, operatorId, remark, now);
    }

    public List<OaPurchaseBudgetLedgerResp> budgetLedger(long tenantId, long budgetId) {
        return jdbcTemplate.query("""
            SELECT g.id, g.budget_id, g.action, g.amount, g.purchase_id, p.purchase_no, g.operator_id,
                   COALESCE(NULLIF(o.nickname, ''), o.username) AS operator_name, g.remark, g.create_time
              FROM oa_purchase_budget_ledger g
              LEFT JOIN oa_purchase p ON p.tenant_id = g.tenant_id AND p.id = g.purchase_id
              LEFT JOIN sys_user o ON o.tenant_id = g.tenant_id AND o.id = g.operator_id
             WHERE g.tenant_id = ? AND g.budget_id = ?
             ORDER BY g.id DESC
            """, (rs, rowNum) -> new OaPurchaseBudgetLedgerResp(rs.getLong("id"), rs.getLong("budget_id"), rs
            .getString("action"), rs.getBigDecimal("amount"), nullableLong(rs, "purchase_id"), rs
                .getString("purchase_no"), rs.getLong("operator_id"), rs.getString("operator_name"), rs
                    .getString("remark"), timestamp(rs, "create_time")), tenantId, budgetId);
    }

    public long insertPurchase(long tenantId,
                               long applicationId,
                               long applicantId,
                               long deptId,
                               long budgetId,
                               BigDecimal total,
                               int itemCount,
                               LocalDate expectedDate,
                               String status,
                               LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_purchase
                  (tenant_id, application_id, applicant_id, dept_id, budget_id, total_amount, item_count, expected_date,
                   status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setLong(3, applicantId);
            ps.setLong(4, deptId);
            ps.setLong(5, budgetId);
            ps.setBigDecimal(6, total);
            ps.setInt(7, itemCount);
            ps.setDate(8, expectedDate == null ? null : Date.valueOf(expectedDate));
            ps.setString(9, status);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void assignPurchaseNo(long tenantId, long id, String purchaseNo) {
        jdbcTemplate
            .update("UPDATE oa_purchase SET purchase_no = ? WHERE tenant_id = ? AND id = ?", purchaseNo, tenantId, id);
    }

    public void insertItem(long tenantId, long purchaseId, OaPurchaseItemResp item) {
        jdbcTemplate.update("""
            INSERT INTO oa_purchase_item
              (tenant_id, purchase_id, line_no, item_name, category, supply_code, spec, quantity, unit_price, amount,
               received_qty)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """, tenantId, purchaseId, item.lineNo(), item.itemName(), item.category(), item.supplyCode(), item
            .spec(), item.quantity(), item.unitPrice(), item.amount());
    }

    public PurchaseRow lockPurchaseByApplication(long tenantId, long applicationId) {
        List<PurchaseRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, budget_id, purchase_no, total_amount, status
              FROM oa_purchase WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, PURCHASE_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PurchaseRow lockPurchase(long tenantId, long id) {
        List<PurchaseRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, budget_id, purchase_no, total_amount, status
              FROM oa_purchase WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, PURCHASE_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionPurchase(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int markOrdered(long tenantId,
                           long id,
                           String supplier,
                           String orderNo,
                           LocalDate orderDate,
                           long ordererId,
                           LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase
               SET status = 'ORDERED', supplier = ?, order_no = ?, order_date = ?, orderer_id = ?, ordered_time = ?,
                   update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_ORDER'
            """, supplier, orderNo, Date.valueOf(orderDate), ordererId, now, now, tenantId, id);
    }

    public int markReceived(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase SET status = ?, received_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, "RECEIVED".equals(to) ? now : null, now, tenantId, id, from);
    }

    public List<OaPurchaseResp> purchases(long tenantId,
                                          String status,
                                          String keyword,
                                          Long applicantId,
                                          int offset,
                                          int limit) {
        StringBuilder sql = new StringBuilder(PURCHASE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, status, keyword, applicantId);
        sql.append(" ORDER BY p.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), PURCHASE_MAPPER, args.toArray());
    }

    public long countPurchases(long tenantId, String status, String keyword, Long applicantId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + PURCHASE_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, status, keyword, applicantId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaPurchaseResp purchase(long tenantId, long id) {
        List<OaPurchaseResp> rows = jdbcTemplate
            .query(PURCHASE_SELECT + " AND p.id = ?", PURCHASE_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaPurchaseItemResp> items(long tenantId, long purchaseId, boolean lock) {
        return jdbcTemplate.query("""
            SELECT id, line_no, item_name, category, supply_code, spec, quantity, unit_price, amount, received_qty
              FROM oa_purchase_item WHERE tenant_id = ? AND purchase_id = ?
             ORDER BY id
            """ + (lock ? " FOR UPDATE" : ""), ITEM_MAPPER, tenantId, purchaseId);
    }

    public int receiveItem(long tenantId, long itemId, int quantity) {
        return jdbcTemplate.update("""
            UPDATE oa_purchase_item SET received_qty = received_qty + ?
             WHERE tenant_id = ? AND id = ? AND received_qty + ? <= quantity
            """, quantity, tenantId, itemId, quantity);
    }

    public Long purchaseIdByReceiptRequest(long tenantId, String requestId) {
        List<Long> rows = jdbcTemplate.query("""
            SELECT purchase_id FROM oa_purchase_receipt WHERE tenant_id = ? AND request_id = ?
            """, (rs, rowNum) -> rs.getLong("purchase_id"), tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertReceipt(long tenantId,
                              long purchaseId,
                              String requestId,
                              LocalDate receiveDate,
                              long receiverId,
                              String remark,
                              LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_purchase_receipt
                  (tenant_id, purchase_id, request_id, receive_date, receiver_id, remark, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, purchaseId);
            ps.setString(3, requestId);
            ps.setDate(4, Date.valueOf(receiveDate));
            ps.setLong(5, receiverId);
            ps.setString(6, remark);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void insertReceiptLine(long tenantId, long receiptId, long itemId, int quantity) {
        jdbcTemplate.update("""
            INSERT INTO oa_purchase_receipt_line (tenant_id, receipt_id, item_id, quantity) VALUES (?, ?, ?, ?)
            """, tenantId, receiptId, itemId, quantity);
    }

    public List<OaPurchaseReceiptResp> receipts(long tenantId, long purchaseId) {
        Map<Long, List<OaPurchaseReceiptLineResp>> lines = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT l.receipt_id, l.item_id, i.item_name, l.quantity
              FROM oa_purchase_receipt_line l
              JOIN oa_purchase_receipt r ON r.tenant_id = l.tenant_id AND r.id = l.receipt_id
              JOIN oa_purchase_item i ON i.tenant_id = l.tenant_id AND i.id = l.item_id
             WHERE l.tenant_id = ? AND r.purchase_id = ?
             ORDER BY l.id
            """, rs -> {
            OaPurchaseReceiptLineResp line = new OaPurchaseReceiptLineResp(rs.getLong("receipt_id"), rs
                .getLong("item_id"), rs.getString("item_name"), rs.getInt("quantity"));
            lines.computeIfAbsent(line.receiptId(), key -> new ArrayList<>()).add(line);
        }, tenantId, purchaseId);
        return jdbcTemplate.query("""
            SELECT r.id, r.request_id, r.receive_date, r.receiver_id,
                   COALESCE(NULLIF(u.nickname, ''), u.username) AS receiver_name, r.remark, r.create_time
              FROM oa_purchase_receipt r
              LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.receiver_id
             WHERE r.tenant_id = ? AND r.purchase_id = ?
             ORDER BY r.id
            """, (rs, rowNum) -> new OaPurchaseReceiptResp(rs.getLong("id"), rs
            .getString("request_id"), date(rs, "receive_date"), rs.getLong("receiver_id"), rs
                .getString("receiver_name"), rs.getString("remark"), timestamp(rs, "create_time"), List.copyOf(lines
                    .getOrDefault(rs.getLong("id"), List.of()))), tenantId, purchaseId);
    }

    private static void appendFilters(StringBuilder sql,
                                      List<Object> args,
                                      String status,
                                      String keyword,
                                      Long applicantId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND p.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND p.applicant_id = ?");
            args.add(applicantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ? OR p.purchase_no LIKE ? OR p.supplier LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record BudgetRow(long id, long deptId, int year, BigDecimal amount, BigDecimal used, BigDecimal occupied,
                            int version) {

        public BigDecimal available() {
            return amount.subtract(used).subtract(occupied);
        }
    }

    public record PurchaseRow(long id, long applicationId, long applicantId, long budgetId, String purchaseNo,
                              BigDecimal total, String status) {
    }
}
