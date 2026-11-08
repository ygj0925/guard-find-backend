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

import com.guardfind.backend.oa.model.OaLoanLedgerResp;
import com.guardfind.backend.oa.model.OaLoanResp;
import com.guardfind.backend.oa.model.OaTravelResp;
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
import java.util.List;

/**
 * 出差与借款的显式租户 SQL。
 *
 * <p>
 * 预算与余额判断依赖的金额都用逐行 FOR UPDATE 读取后在 Java 中求和：既兼容 H2（不允许聚合加锁），
 * 也避免 MySQL 可重复读快照漏掉并发事务已提交的借款/报销。
 * </p>
 */
@Repository
public class OaTravelRepository {

    private static final String TRAVEL_FROM = """
          FROM oa_travel t
          JOIN oa_application a ON a.tenant_id = t.tenant_id AND a.id = t.application_id
          LEFT JOIN sys_user u ON u.tenant_id = t.tenant_id AND u.id = t.applicant_id
         WHERE t.tenant_id = ?
        """;

    private static final String TRAVEL_SELECT = """
        SELECT t.id, t.application_id, a.title, a.status AS application_status, t.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, t.travel_no, t.destination,
               t.start_date, t.end_date, t.days, t.budget_amount, t.status,
               (SELECT COALESCE(SUM(l.amount), 0) FROM oa_loan l
                 WHERE l.tenant_id = t.tenant_id AND l.travel_id = t.id AND l.status <> 'CLOSED') AS loan_amount,
               (SELECT COALESCE(SUM(l.balance), 0) FROM oa_loan l
                 WHERE l.tenant_id = t.tenant_id AND l.travel_id = t.id AND l.status = 'OUTSTANDING') AS loan_balance,
               (SELECT COALESCE(SUM(e.total_amount), 0) FROM oa_expense e
                 WHERE e.tenant_id = t.tenant_id AND e.travel_id = t.id AND e.status <> 'CLOSED') AS expense_amount,
               t.create_time
        """ + TRAVEL_FROM;

    private static final RowMapper<OaTravelResp> TRAVEL_MAPPER = (rs, rowNum) -> new OaTravelResp(rs.getLong("id"), rs
        .getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getString("travel_no"), rs
                .getString("destination"), date(rs, "start_date"), date(rs, "end_date"), rs.getInt("days"), rs
                    .getBigDecimal("budget_amount"), rs.getString("status"), rs.getBigDecimal("loan_amount"), rs
                        .getBigDecimal("loan_balance"), rs
                            .getBigDecimal("expense_amount"), timestamp(rs, "create_time"));

    private static final RowMapper<TravelRow> TRAVEL_ROW_MAPPER = (rs, rowNum) -> new TravelRow(rs.getLong("id"), rs
        .getLong("application_id"), rs.getLong("applicant_id"), rs.getString("travel_no"), rs
            .getBigDecimal("budget_amount"), rs.getString("status"));

    private static final String LOAN_FROM = """
          FROM oa_loan l
          JOIN oa_application a ON a.tenant_id = l.tenant_id AND a.id = l.application_id
          LEFT JOIN sys_user u ON u.tenant_id = l.tenant_id AND u.id = l.applicant_id
          LEFT JOIN sys_user d ON d.tenant_id = l.tenant_id AND d.id = l.disburser_id
          LEFT JOIN oa_travel t ON t.tenant_id = l.tenant_id AND t.id = l.travel_id
         WHERE l.tenant_id = ?
        """;

    private static final String LOAN_SELECT = """
        SELECT l.id, l.application_id, a.title, a.status AS application_status, l.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, l.travel_id, t.travel_no, l.amount,
               l.balance, l.status, l.expected_repay_date, l.disburse_date, l.disburse_method, l.disburse_reference,
               l.disburser_id, COALESCE(NULLIF(d.nickname, ''), d.username) AS disburser_name, l.disbursed_time,
               l.settled_time, l.create_time
        """ + LOAN_FROM;

    private static final RowMapper<OaLoanResp> LOAN_MAPPER = (rs, rowNum) -> new OaLoanResp(rs.getLong("id"), rs
        .getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), nullableLong(rs, "travel_id"), rs
                .getString("travel_no"), rs.getBigDecimal("amount"), rs.getBigDecimal("balance"), rs
                    .getString("status"), date(rs, "expected_repay_date"), date(rs, "disburse_date"), rs
                        .getString("disburse_method"), rs
                            .getString("disburse_reference"), nullableLong(rs, "disburser_id"), rs
                                .getString("disburser_name"), timestamp(rs, "disbursed_time"), timestamp(rs, "settled_time"), timestamp(rs, "create_time"));

    private static final RowMapper<LoanRow> LOAN_ROW_MAPPER = (rs, rowNum) -> new LoanRow(rs.getLong("id"), rs
        .getLong("application_id"), rs.getLong("applicant_id"), nullableLong(rs, "travel_id"), rs
            .getBigDecimal("amount"), rs.getBigDecimal("balance"), rs.getString("status"));

    private static final String LEDGER_SELECT = """
        SELECT g.id, g.loan_id, g.action, g.amount, g.balance_after, g.expense_id, g.biz_date, g.method, g.reference,
               g.operator_id, COALESCE(NULLIF(o.nickname, ''), o.username) AS operator_name, g.remark, g.create_time
          FROM oa_loan_ledger g
          LEFT JOIN sys_user o ON o.tenant_id = g.tenant_id AND o.id = g.operator_id
         WHERE g.tenant_id = ?
        """;

    private static final RowMapper<OaLoanLedgerResp> LEDGER_MAPPER = (rs, rowNum) -> new OaLoanLedgerResp(rs
        .getLong("id"), rs.getLong("loan_id"), rs.getString("action"), rs.getBigDecimal("amount"), rs
            .getBigDecimal("balance_after"), nullableLong(rs, "expense_id"), date(rs, "biz_date"), rs
                .getString("method"), rs.getString("reference"), rs.getLong("operator_id"), rs
                    .getString("operator_name"), rs.getString("remark"), timestamp(rs, "create_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaTravelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁住申请人账号行，串行化同一申请人的出差提交，使时间重叠检查在并发下仍然成立。
     */
    public void lockApplicant(long tenantId, long applicantId) {
        jdbcTemplate.query("SELECT id FROM sys_user WHERE tenant_id = ? AND id = ? FOR UPDATE", (rs, rowNum) -> rs
            .getLong("id"), tenantId, applicantId);
    }

    public List<String> overlappingTravels(long tenantId, long applicantId, LocalDate start, LocalDate end) {
        return jdbcTemplate.query("""
            SELECT travel_no FROM oa_travel
             WHERE tenant_id = ? AND applicant_id = ? AND status <> 'CLOSED' AND start_date <= ? AND end_date >= ?
             ORDER BY start_date
             FOR UPDATE
            """, (rs, rowNum) -> rs.getString("travel_no"), tenantId, applicantId, Date.valueOf(end), Date
            .valueOf(start));
    }

    public long insertTravel(long tenantId,
                             long applicationId,
                             long applicantId,
                             String destination,
                             LocalDate start,
                             LocalDate end,
                             int days,
                             BigDecimal budget,
                             String status,
                             LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_travel
                  (tenant_id, application_id, applicant_id, destination, start_date, end_date, days, budget_amount,
                   status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setLong(3, applicantId);
            ps.setString(4, destination);
            ps.setDate(5, Date.valueOf(start));
            ps.setDate(6, Date.valueOf(end));
            ps.setInt(7, days);
            ps.setBigDecimal(8, budget);
            ps.setString(9, status);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void assignTravelNo(long tenantId, long id, String travelNo) {
        jdbcTemplate
            .update("UPDATE oa_travel SET travel_no = ? WHERE tenant_id = ? AND id = ?", travelNo, tenantId, id);
    }

    public TravelRow lockTravelByApplication(long tenantId, long applicationId) {
        List<TravelRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, travel_no, budget_amount, status
              FROM oa_travel WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, TRAVEL_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TravelRow travelByNo(long tenantId, String travelNo, boolean lock) {
        List<TravelRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, travel_no, budget_amount, status
              FROM oa_travel WHERE tenant_id = ? AND travel_no = ?
            """ + (lock ? " FOR UPDATE" : ""), TRAVEL_ROW_MAPPER, tenantId, travelNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionTravel(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_travel SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public BigDecimal activeLoanTotal(long tenantId, long travelId, boolean lock) {
        return sum(jdbcTemplate.query("""
            SELECT amount FROM oa_loan WHERE tenant_id = ? AND travel_id = ? AND status <> 'CLOSED'
            """ + (lock ? " FOR UPDATE" : ""), (rs, rowNum) -> rs.getBigDecimal("amount"), tenantId, travelId));
    }

    public BigDecimal activeExpenseTotal(long tenantId, long travelId, boolean lock) {
        return sum(jdbcTemplate.query("""
            SELECT total_amount FROM oa_expense WHERE tenant_id = ? AND travel_id = ? AND status <> 'CLOSED'
            """ + (lock ? " FOR UPDATE" : ""), (rs, rowNum) -> rs.getBigDecimal("total_amount"), tenantId, travelId));
    }

    public List<OaTravelResp> travels(long tenantId,
                                      String status,
                                      String keyword,
                                      Long applicantId,
                                      int offset,
                                      int limit) {
        StringBuilder sql = new StringBuilder(TRAVEL_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendTravelFilters(sql, args, status, keyword, applicantId);
        sql.append(" ORDER BY t.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), TRAVEL_MAPPER, args.toArray());
    }

    public long countTravels(long tenantId, String status, String keyword, Long applicantId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + TRAVEL_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendTravelFilters(sql, args, status, keyword, applicantId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaTravelResp travel(long tenantId, long id) {
        List<OaTravelResp> rows = jdbcTemplate.query(TRAVEL_SELECT + " AND t.id = ?", TRAVEL_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertLoan(long tenantId,
                           long applicationId,
                           long applicantId,
                           Long travelId,
                           BigDecimal amount,
                           LocalDate expectedRepayDate,
                           String status,
                           LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_loan
                  (tenant_id, application_id, applicant_id, travel_id, amount, balance, status, expected_repay_date,
                   create_time)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setLong(3, applicantId);
            ps.setObject(4, travelId);
            ps.setBigDecimal(5, amount);
            ps.setString(6, status);
            ps.setDate(7, expectedRepayDate == null ? null : Date.valueOf(expectedRepayDate));
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public LoanRow lockLoanByApplication(long tenantId, long applicationId) {
        List<LoanRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, travel_id, amount, balance, status
              FROM oa_loan WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, LOAN_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public LoanRow lockLoan(long tenantId, long id) {
        List<LoanRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, travel_id, amount, balance, status
              FROM oa_loan WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, LOAN_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionLoan(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_loan SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int markDisbursed(long tenantId,
                             long id,
                             BigDecimal amount,
                             LocalDate disburseDate,
                             String method,
                             String reference,
                             long disburserId,
                             LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_loan
               SET status = 'OUTSTANDING', balance = ?, disburse_date = ?, disburse_method = ?, disburse_reference = ?,
                   disburser_id = ?, disbursed_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_DISBURSE'
            """, amount, Date.valueOf(disburseDate), method, reference, disburserId, now, now, tenantId, id);
    }

    /**
     * 以旧余额为条件改写余额；余额归零时转为已还清。
     */
    public int changeBalance(long tenantId, long id, BigDecimal oldBalance, BigDecimal newBalance, LocalDateTime now) {
        boolean settled = newBalance.signum() == 0;
        return jdbcTemplate.update("""
            UPDATE oa_loan SET balance = ?, status = ?, settled_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'OUTSTANDING' AND balance = ?
            """, newBalance, settled ? "SETTLED" : "OUTSTANDING", settled ? now : null, now, tenantId, id, oldBalance);
    }

    public void insertLedger(long tenantId,
                             long loanId,
                             String action,
                             BigDecimal amount,
                             BigDecimal balanceAfter,
                             Long expenseId,
                             String requestId,
                             LocalDate bizDate,
                             String method,
                             String reference,
                             long operatorId,
                             String remark,
                             LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_loan_ledger
              (tenant_id, loan_id, action, amount, balance_after, expense_id, request_id, biz_date, method, reference,
               operator_id, remark, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, loanId, action, amount, balanceAfter, expenseId, requestId, bizDate == null
            ? null
            : Date.valueOf(bizDate), method, reference, operatorId, remark, now);
    }

    public Long loanIdByRequest(long tenantId, String requestId) {
        List<Long> rows = jdbcTemplate.query("""
            SELECT loan_id FROM oa_loan_ledger WHERE tenant_id = ? AND request_id = ?
            """, (rs, rowNum) -> rs.getLong("loan_id"), tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaLoanLedgerResp> ledger(long tenantId, long loanId) {
        return jdbcTemplate.query(LEDGER_SELECT + " AND g.loan_id = ? ORDER BY g.id", LEDGER_MAPPER, tenantId, loanId);
    }

    public List<OaLoanLedgerResp> writeOffs(long tenantId, long expenseId) {
        return jdbcTemplate
            .query(LEDGER_SELECT + " AND g.expense_id = ? ORDER BY g.id", LEDGER_MAPPER, tenantId, expenseId);
    }

    public List<OaLoanResp> loans(long tenantId,
                                  String status,
                                  String keyword,
                                  Long applicantId,
                                  Long travelId,
                                  int offset,
                                  int limit) {
        StringBuilder sql = new StringBuilder(LOAN_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendLoanFilters(sql, args, status, keyword, applicantId, travelId);
        sql.append(" ORDER BY l.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), LOAN_MAPPER, args.toArray());
    }

    public long countLoans(long tenantId, String status, String keyword, Long applicantId, Long travelId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + LOAN_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendLoanFilters(sql, args, status, keyword, applicantId, travelId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaLoanResp loan(long tenantId, long id) {
        List<OaLoanResp> rows = jdbcTemplate.query(LOAN_SELECT + " AND l.id = ?", LOAN_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void appendTravelFilters(StringBuilder sql,
                                            List<Object> args,
                                            String status,
                                            String keyword,
                                            Long applicantId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND t.applicant_id = ?");
            args.add(applicantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ? OR t.travel_no LIKE ? OR t.destination LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
    }

    private static void appendLoanFilters(StringBuilder sql,
                                          List<Object> args,
                                          String status,
                                          String keyword,
                                          Long applicantId,
                                          Long travelId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND l.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND l.applicant_id = ?");
            args.add(applicantId);
        }
        if (travelId != null) {
            sql.append(" AND l.travel_id = ?");
            args.add(travelId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ? OR t.travel_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 4; i++) {
                args.add(like);
            }
        }
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(amount);
        }
        return total;
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

    public record TravelRow(long id, long applicationId, long applicantId, String travelNo, BigDecimal budget,
                            String status) {
    }

    public record LoanRow(long id, long applicationId, long applicantId, Long travelId, BigDecimal amount,
                          BigDecimal balance, String status) {
    }
}
