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

import com.guardfind.backend.oa.model.OaExpenseCategoryResp;
import com.guardfind.backend.oa.model.OaExpenseItemResp;
import com.guardfind.backend.oa.model.OaExpenseResp;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 报销试点的显式租户 SQL。
 *
 * <p>
 * 报销单状态迁移与支付登记都使用带状态条件的 UPDATE，重复终态回调或并发登记只有一个成功；
 * 发票号按（租户, 发票号）唯一占用，驳回/撤回/终止时删除占用行以便重新报销。
 * </p>
 */
@Repository
public class OaExpenseRepository {

    private static final String CATEGORY_COLUMNS = """
        id, code, name, per_item_limit, invoice_required, status, sort, remark, version, create_time, update_time
        """;

    private static final RowMapper<OaExpenseCategoryResp> CATEGORY_MAPPER = (rs, rowNum) -> new OaExpenseCategoryResp(rs
        .getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBigDecimal("per_item_limit"), rs
            .getBoolean("invoice_required"), rs.getString("status"), rs.getInt("sort"), rs.getString("remark"), rs
                .getInt("version"), timestamp(rs, "create_time"), timestamp(rs, "update_time"));

    private static final String RECORD_FROM = """
          FROM oa_expense e
          JOIN oa_application a ON a.tenant_id = e.tenant_id AND a.id = e.application_id
          LEFT JOIN sys_user u ON u.tenant_id = e.tenant_id AND u.id = e.applicant_id
          LEFT JOIN sys_user p ON p.tenant_id = e.tenant_id AND p.id = e.payer_id
         WHERE e.tenant_id = ?
        """;

    private static final String RECORD_SELECT = """
        SELECT e.id, e.application_id, a.title, a.status AS application_status, e.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, e.total_amount, e.item_count,
               e.status, e.paid_amount, e.pay_date, e.pay_method, e.pay_reference, e.pay_remark, e.payer_id,
               COALESCE(NULLIF(p.nickname, ''), p.username) AS payer_name, e.paid_time, e.create_time
        """ + RECORD_FROM;

    private static final RowMapper<OaExpenseResp> RECORD_MAPPER = (rs, rowNum) -> new OaExpenseResp(rs.getLong("id"), rs
        .getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getBigDecimal("total_amount"), rs
                .getInt("item_count"), rs.getString("status"), rs.getBigDecimal("paid_amount"), date(rs, "pay_date"), rs
                    .getString("pay_method"), rs.getString("pay_reference"), rs
                        .getString("pay_remark"), nullableLong(rs, "payer_id"), rs
                            .getString("payer_name"), timestamp(rs, "paid_time"), timestamp(rs, "create_time"));

    private static final RowMapper<ExpenseRow> EXPENSE_ROW_MAPPER = (rs, rowNum) -> new ExpenseRow(rs.getLong("id"), rs
        .getLong("application_id"), rs.getLong("applicant_id"), rs.getBigDecimal("total_amount"), rs
            .getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public OaExpenseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaExpenseCategoryResp> categories(long tenantId, boolean enabledOnly) {
        String sql = "SELECT " + CATEGORY_COLUMNS + " FROM oa_expense_category WHERE tenant_id = ?" + (enabledOnly
            ? " AND status = 'ENABLED'"
            : "") + " ORDER BY sort, id";
        return jdbcTemplate.query(sql, CATEGORY_MAPPER, tenantId);
    }

    public OaExpenseCategoryResp category(long tenantId, long id) {
        List<OaExpenseCategoryResp> rows = jdbcTemplate
            .query("SELECT " + CATEGORY_COLUMNS + " FROM oa_expense_category WHERE tenant_id = ? AND id = ?", CATEGORY_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean categoryCodeExists(long tenantId, String code) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_expense_category WHERE tenant_id = ? AND code = ?
            """, Integer.class, tenantId, code);
        return count != null && count > 0;
    }

    public long insertCategory(long tenantId,
                               String code,
                               String name,
                               BigDecimal perItemLimit,
                               boolean invoiceRequired,
                               String status,
                               int sort,
                               String remark,
                               long userId,
                               LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_expense_category
                  (tenant_id, code, name, per_item_limit, invoice_required, status, sort, remark, version,
                   create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setBigDecimal(4, perItemLimit);
            ps.setBoolean(5, invoiceRequired);
            ps.setString(6, status);
            ps.setInt(7, sort);
            ps.setString(8, remark);
            ps.setLong(9, userId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateCategory(long tenantId,
                              long id,
                              String name,
                              BigDecimal perItemLimit,
                              boolean invoiceRequired,
                              String status,
                              int sort,
                              String remark,
                              long userId,
                              LocalDateTime now,
                              int version) {
        return jdbcTemplate.update("""
            UPDATE oa_expense_category
               SET name = ?, per_item_limit = ?, invoice_required = ?, status = ?, sort = ?, remark = ?,
                   update_user = ?, update_time = ?, version = version + 1
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, name, perItemLimit, invoiceRequired, status, sort, remark, userId, now, tenantId, id, version);
    }

    public List<String> usedInvoices(long tenantId, Collection<String> invoiceNos) {
        if (invoiceNos.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(invoiceNos);
        String placeholders = String.join(", ", Collections.nCopies(invoiceNos.size(), "?"));
        return jdbcTemplate
            .queryForList("SELECT invoice_no FROM oa_expense_invoice WHERE tenant_id = ? AND invoice_no IN (" + placeholders + ") ORDER BY invoice_no", String.class, args
                .toArray());
    }

    public long insertExpense(long tenantId,
                              long applicationId,
                              long applicantId,
                              BigDecimal totalAmount,
                              int itemCount,
                              String status,
                              LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_expense
                  (tenant_id, application_id, applicant_id, total_amount, item_count, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setLong(3, applicantId);
            ps.setBigDecimal(4, totalAmount);
            ps.setInt(5, itemCount);
            ps.setString(6, status);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void insertItem(long tenantId, long expenseId, long applicationId, OaExpenseItemResp item) {
        jdbcTemplate.update("""
            INSERT INTO oa_expense_item
              (tenant_id, expense_id, application_id, line_no, category_code, category_name, amount, expense_date,
               invoice_no, description)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, expenseId, applicationId, item.lineNo(), item.categoryCode(), item.categoryName(), item
            .amount(), item.expenseDate() == null ? null : Date.valueOf(item.expenseDate()), item.invoiceNo(), item
                .description());
    }

    public void insertInvoice(long tenantId, String invoiceNo, long applicationId, LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_expense_invoice (tenant_id, invoice_no, application_id, create_time) VALUES (?, ?, ?, ?)
            """, tenantId, invoiceNo, applicationId, now);
    }

    public int releaseInvoices(long tenantId, long applicationId) {
        return jdbcTemplate
            .update("DELETE FROM oa_expense_invoice WHERE tenant_id = ? AND application_id = ?", tenantId, applicationId);
    }

    public ExpenseRow lockByApplication(long tenantId, long applicationId) {
        List<ExpenseRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, total_amount, status
              FROM oa_expense WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, EXPENSE_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ExpenseRow lock(long tenantId, long id) {
        List<ExpenseRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, total_amount, status
              FROM oa_expense WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, EXPENSE_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transition(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_expense SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int markPaid(long tenantId,
                        long id,
                        BigDecimal paidAmount,
                        LocalDate payDate,
                        String payMethod,
                        String payReference,
                        String remark,
                        long payerId,
                        LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_expense
               SET status = 'PAID', paid_amount = ?, pay_date = ?, pay_method = ?, pay_reference = ?, pay_remark = ?,
                   payer_id = ?, paid_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_PAYMENT'
            """, paidAmount, Date.valueOf(payDate), payMethod, payReference, remark, payerId, now, now, tenantId, id);
    }

    public List<OaExpenseResp> records(long tenantId,
                                       String status,
                                       String keyword,
                                       Long applicantId,
                                       int offset,
                                       int limit) {
        StringBuilder sql = new StringBuilder(RECORD_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, status, keyword, applicantId);
        sql.append(" ORDER BY e.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), RECORD_MAPPER, args.toArray());
    }

    public long countRecords(long tenantId, String status, String keyword, Long applicantId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + RECORD_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, status, keyword, applicantId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaExpenseResp record(long tenantId, long id) {
        List<OaExpenseResp> rows = jdbcTemplate.query(RECORD_SELECT + " AND e.id = ?", RECORD_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaExpenseItemResp> items(long tenantId, long expenseId) {
        return jdbcTemplate.query("""
            SELECT line_no, category_code, category_name, amount, expense_date, invoice_no, description
              FROM oa_expense_item WHERE tenant_id = ? AND expense_id = ?
             ORDER BY line_no
            """, (rs, rowNum) -> new OaExpenseItemResp(rs.getInt("line_no"), rs.getString("category_code"), rs
            .getString("category_name"), rs.getBigDecimal("amount"), date(rs, "expense_date"), rs
                .getString("invoice_no"), rs.getString("description")), tenantId, expenseId);
    }

    private static void appendFilters(StringBuilder sql,
                                      List<Object> args,
                                      String status,
                                      String keyword,
                                      Long applicantId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND e.applicant_id = ?");
            args.add(applicantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record ExpenseRow(long id, long applicationId, long applicantId, BigDecimal totalAmount, String status) {
    }
}
