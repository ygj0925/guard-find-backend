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

import com.guardfind.backend.oa.model.OaAssetLogResp;
import com.guardfind.backend.oa.model.OaAssetResp;
import com.guardfind.backend.oa.model.OaAssetScrapResp;
import com.guardfind.backend.oa.model.OaStocktakeLineResp;
import com.guardfind.backend.oa.model.OaStocktakeResp;
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.model.OaSupplyLedgerResp;
import com.guardfind.backend.oa.model.OaSupplyRequestItemResp;
import com.guardfind.backend.oa.model.OaSupplyRequestResp;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.guardfind.backend.oa.repository.OaPurchaseRepository.date;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.nullableLong;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.timestamp;

/**
 * 用品库存、领用、资产卡片、报废与盘点的显式租户 SQL；库存只经带下限条件的增量更新变动。
 */
@Repository
public class OaAssetRepository {

    private static final String ITEM_COLUMNS = "id, code, name, spec, unit, stock, safety_stock, status, version, COALESCE(update_time, create_time) AS update_time";

    private static final RowMapper<OaSupplyItemResp> ITEM_MAPPER = (rs, rowNum) -> new OaSupplyItemResp(rs
        .getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("spec"), rs.getString("unit"), rs
            .getInt("stock"), rs.getInt("safety_stock"), rs.getInt("stock") < rs.getInt("safety_stock"), rs
                .getString("status"), rs.getInt("version"), timestamp(rs, "update_time"));

    private static final String LEDGER_SELECT = """
        SELECT g.id, g.item_id, i.code AS item_code, i.name AS item_name, g.action, g.quantity, g.balance_after,
               g.ref_type, g.ref_id, g.request_id, g.operator_id,
               COALESCE(NULLIF(o.nickname, ''), o.username) AS operator_name, g.remark, g.create_time
          FROM oa_supply_ledger g
          JOIN oa_supply_item i ON i.tenant_id = g.tenant_id AND i.id = g.item_id
          LEFT JOIN sys_user o ON o.tenant_id = g.tenant_id AND o.id = g.operator_id
         WHERE g.tenant_id = ?
        """;

    private static final RowMapper<OaSupplyLedgerResp> LEDGER_MAPPER = (rs, rowNum) -> new OaSupplyLedgerResp(rs
        .getLong("id"), rs.getLong("item_id"), rs.getString("item_code"), rs.getString("item_name"), rs
            .getString("action"), rs.getInt("quantity"), rs.getInt("balance_after"), rs
                .getString("ref_type"), nullableLong(rs, "ref_id"), rs.getString("request_id"), rs
                    .getLong("operator_id"), rs.getString("operator_name"), rs
                        .getString("remark"), timestamp(rs, "create_time"));

    private static final String REQUEST_FROM = """
          FROM oa_supply_request r
          JOIN oa_application a ON a.tenant_id = r.tenant_id AND a.id = r.application_id
          LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.applicant_id
          LEFT JOIN sys_user s ON s.tenant_id = r.tenant_id AND s.id = r.issuer_id
         WHERE r.tenant_id = ?
        """;

    private static final String REQUEST_SELECT = """
        SELECT r.id, r.application_id, a.title, a.status AS application_status, r.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, r.item_count, r.total_quantity, r.status,
               r.issuer_id, COALESCE(NULLIF(s.nickname, ''), s.username) AS issuer_name, r.issued_time, r.create_time
        """ + REQUEST_FROM;

    private static final RowMapper<OaSupplyRequestResp> REQUEST_MAPPER = (rs, rowNum) -> new OaSupplyRequestResp(rs
        .getLong("id"), rs.getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getInt("item_count"), rs
                .getInt("total_quantity"), rs.getString("status"), nullableLong(rs, "issuer_id"), rs
                    .getString("issuer_name"), timestamp(rs, "issued_time"), timestamp(rs, "create_time"));

    private static final String ASSET_FROM = """
          FROM oa_asset s
          LEFT JOIN sys_user h ON h.tenant_id = s.tenant_id AND h.id = s.holder_id
          LEFT JOIN sys_dept d ON d.tenant_id = s.tenant_id AND d.id = s.dept_id AND d.deleted = 0
          LEFT JOIN oa_purchase p ON p.tenant_id = s.tenant_id AND p.id = s.purchase_id
         WHERE s.tenant_id = ?
        """;

    private static final String ASSET_SELECT = """
        SELECT s.id, s.asset_no, s.name, s.category, s.spec, s.status, s.holder_id,
               COALESCE(NULLIF(h.nickname, ''), h.username) AS holder_name, s.dept_id, d.name AS dept_name, s.location,
               s.original_value, s.purchase_id, p.purchase_no, s.acquired_date,
               (SELECT COUNT(*) FROM oa_asset_scrap x
                 WHERE x.tenant_id = s.tenant_id AND x.asset_id = s.id AND x.status IN ('APPROVING', 'PENDING_SCRAP')) AS scrap_pending,
               s.version, s.create_time
        """ + ASSET_FROM;

    private static final RowMapper<OaAssetResp> ASSET_MAPPER = (rs, rowNum) -> new OaAssetResp(rs.getLong("id"), rs
        .getString("asset_no"), rs.getString("name"), rs.getString("category"), rs.getString("spec"), rs
            .getString("status"), nullableLong(rs, "holder_id"), rs
                .getString("holder_name"), nullableLong(rs, "dept_id"), rs.getString("dept_name"), rs
                    .getString("location"), rs.getBigDecimal("original_value"), nullableLong(rs, "purchase_id"), rs
                        .getString("purchase_no"), date(rs, "acquired_date"), rs.getLong("scrap_pending") > 0, rs
                            .getInt("version"), timestamp(rs, "create_time"));

    private static final RowMapper<AssetRow> ASSET_ROW_MAPPER = (rs, rowNum) -> new AssetRow(rs.getLong("id"), rs
        .getString("asset_no"), rs.getString("name"), rs.getString("status"), nullableLong(rs, "holder_id"), rs
            .getString("location"), rs.getBigDecimal("original_value"), rs.getInt("version"));

    private static final String SCRAP_FROM = """
          FROM oa_asset_scrap x
          JOIN oa_application a ON a.tenant_id = x.tenant_id AND a.id = x.application_id
          JOIN oa_asset s ON s.tenant_id = x.tenant_id AND s.id = x.asset_id
          LEFT JOIN sys_user u ON u.tenant_id = x.tenant_id AND u.id = x.applicant_id
          LEFT JOIN sys_user e ON e.tenant_id = x.tenant_id AND e.id = x.executor_id
         WHERE x.tenant_id = ?
        """;

    private static final String SCRAP_SELECT = """
        SELECT x.id, x.application_id, a.title, a.status AS application_status, x.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, x.asset_id, s.asset_no, s.name AS asset_name,
               s.original_value, x.status, x.executor_id, COALESCE(NULLIF(e.nickname, ''), e.username) AS executor_name,
               x.executed_time, x.create_time
        """ + SCRAP_FROM;

    private static final RowMapper<OaAssetScrapResp> SCRAP_MAPPER = (rs, rowNum) -> new OaAssetScrapResp(rs
        .getLong("id"), rs.getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getLong("asset_id"), rs
                .getString("asset_no"), rs.getString("asset_name"), rs.getBigDecimal("original_value"), rs
                    .getString("status"), nullableLong(rs, "executor_id"), rs
                        .getString("executor_name"), timestamp(rs, "executed_time"), timestamp(rs, "create_time"));

    private static final String STOCKTAKE_SELECT = """
        SELECT t.id, t.title, t.status, t.create_user, COALESCE(NULLIF(c.nickname, ''), c.username) AS create_user_name,
               t.create_time, t.closed_by, COALESCE(NULLIF(b.nickname, ''), b.username) AS closed_by_name, t.closed_time,
               (SELECT COUNT(*) FROM oa_stocktake_line l WHERE l.tenant_id = t.tenant_id AND l.stocktake_id = t.id) AS line_count,
               (SELECT COUNT(*) FROM oa_stocktake_line l
                 WHERE l.tenant_id = t.tenant_id AND l.stocktake_id = t.id AND l.counted_qty IS NOT NULL) AS counted_count,
               (SELECT COUNT(*) FROM oa_stocktake_line l
                 WHERE l.tenant_id = t.tenant_id AND l.stocktake_id = t.id AND l.counted_qty IS NOT NULL
                   AND l.counted_qty <> l.book_qty) AS variance_count
          FROM oa_stocktake t
          LEFT JOIN sys_user c ON c.tenant_id = t.tenant_id AND c.id = t.create_user
          LEFT JOIN sys_user b ON b.tenant_id = t.tenant_id AND b.id = t.closed_by
         WHERE t.tenant_id = ?
        """;

    private static final RowMapper<OaStocktakeResp> STOCKTAKE_MAPPER = (rs, rowNum) -> new OaStocktakeResp(rs
        .getLong("id"), rs.getString("title"), rs.getString("status"), rs.getInt("line_count"), rs
            .getInt("counted_count"), rs.getInt("variance_count"), rs.getLong("create_user"), rs
                .getString("create_user_name"), timestamp(rs, "create_time"), nullableLong(rs, "closed_by"), rs
                    .getString("closed_by_name"), timestamp(rs, "closed_time"));

    private static final RowMapper<OaStocktakeLineResp> LINE_MAPPER = (rs, rowNum) -> new OaStocktakeLineResp(rs
        .getLong("id"), rs.getString("target_type"), rs.getLong("target_id"), rs.getString("target_code"), rs
            .getString("target_name"), rs
                .getInt("book_qty"), nullableInt(rs, "counted_qty"), nullableInt(rs, "adjusted_qty"));

    private final JdbcTemplate jdbcTemplate;

    public OaAssetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaSupplyItemResp> supplyItems(long tenantId, String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT " + ITEM_COLUMNS + " FROM oa_supply_item WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY code");
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, args.toArray());
    }

    public OaSupplyItemResp supplyItem(long tenantId, long id, boolean lock) {
        List<OaSupplyItemResp> rows = jdbcTemplate
            .query("SELECT " + ITEM_COLUMNS + " FROM oa_supply_item WHERE tenant_id = ? AND id = ?" + (lock
                ? " FOR UPDATE"
                : ""), ITEM_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public OaSupplyItemResp supplyItemByCode(long tenantId, String code, boolean lock) {
        List<OaSupplyItemResp> rows = jdbcTemplate
            .query("SELECT " + ITEM_COLUMNS + " FROM oa_supply_item WHERE tenant_id = ? AND code = ?" + (lock
                ? " FOR UPDATE"
                : ""), ITEM_MAPPER, tenantId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertSupplyItem(long tenantId,
                                 String code,
                                 String name,
                                 String spec,
                                 String unit,
                                 int safetyStock,
                                 String status,
                                 long userId,
                                 LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_supply_item
                  (tenant_id, code, name, spec, unit, stock, safety_stock, status, version, create_user, create_time)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, spec);
            ps.setString(5, unit);
            ps.setInt(6, safetyStock);
            ps.setString(7, status);
            ps.setLong(8, userId);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateSupplyItem(long tenantId,
                                long id,
                                int version,
                                String code,
                                String name,
                                String spec,
                                String unit,
                                int safetyStock,
                                String status,
                                long userId,
                                LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_supply_item
               SET code = ?, name = ?, spec = ?, unit = ?, safety_stock = ?, status = ?, version = version + 1,
                   update_user = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, code, name, spec, unit, safetyStock, status, userId, now, tenantId, id, version);
    }

    /**
     * 按增量改库存，结果不能为负；返回影响行数，调用方据此判断库存是否足够。
     */
    public int changeStock(long tenantId, long id, int delta, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_supply_item SET stock = stock + ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND stock + ? >= 0
            """, delta, now, tenantId, id, delta);
    }

    public void insertSupplyLedger(long tenantId,
                                   long itemId,
                                   String action,
                                   int quantity,
                                   int balanceAfter,
                                   String refType,
                                   Long refId,
                                   String requestId,
                                   long operatorId,
                                   String remark,
                                   LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_supply_ledger
              (tenant_id, item_id, action, quantity, balance_after, ref_type, ref_id, request_id, operator_id, remark,
               create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tenantId, itemId, action, quantity, balanceAfter, refType, refId, requestId, operatorId, remark, now);
    }

    public Long supplyItemIdByRequest(long tenantId, String requestId) {
        List<Long> rows = jdbcTemplate.query("""
            SELECT item_id FROM oa_supply_ledger WHERE tenant_id = ? AND request_id = ?
            """, (rs, rowNum) -> rs.getLong("item_id"), tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaSupplyLedgerResp> supplyLedger(long tenantId, Long itemId, String refType, Long refId, int limit) {
        StringBuilder sql = new StringBuilder(LEDGER_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (itemId != null) {
            sql.append(" AND g.item_id = ?");
            args.add(itemId);
        }
        if (refType != null) {
            sql.append(" AND g.ref_type = ? AND g.ref_id = ?");
            args.add(refType);
            args.add(refId);
        }
        sql.append(" ORDER BY g.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), LEDGER_MAPPER, args.toArray());
    }

    public long insertSupplyRequest(long tenantId,
                                    long applicationId,
                                    long applicantId,
                                    int itemCount,
                                    int totalQuantity,
                                    String status,
                                    LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_supply_request
                  (tenant_id, application_id, applicant_id, item_count, total_quantity, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setLong(3, applicantId);
            ps.setInt(4, itemCount);
            ps.setInt(5, totalQuantity);
            ps.setString(6, status);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void insertSupplyRequestItem(long tenantId, long requestId, long itemId, int quantity) {
        jdbcTemplate.update("""
            INSERT INTO oa_supply_request_item (tenant_id, supply_request_id, item_id, quantity) VALUES (?, ?, ?, ?)
            """, tenantId, requestId, itemId, quantity);
    }

    public SupplyRequestRow lockSupplyRequestByApplication(long tenantId, long applicationId) {
        List<SupplyRequestRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, status FROM oa_supply_request
             WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new SupplyRequestRow(rs.getLong("id"), rs.getLong("application_id"), rs
            .getLong("applicant_id"), rs.getString("status")), tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SupplyRequestRow lockSupplyRequest(long tenantId, long id) {
        List<SupplyRequestRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, status FROM oa_supply_request
             WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new SupplyRequestRow(rs.getLong("id"), rs.getLong("application_id"), rs
            .getLong("applicant_id"), rs.getString("status")), tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionSupplyRequest(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_supply_request SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int markIssued(long tenantId, long id, String issueRequestId, long issuerId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_supply_request
               SET status = 'ISSUED', issue_request_id = ?, issuer_id = ?, issued_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_ISSUE'
            """, issueRequestId, issuerId, now, now, tenantId, id);
    }

    public Long supplyRequestIdByIssueRequest(long tenantId, String issueRequestId) {
        List<Long> rows = jdbcTemplate.query("""
            SELECT id FROM oa_supply_request WHERE tenant_id = ? AND issue_request_id = ?
            """, (rs, rowNum) -> rs.getLong("id"), tenantId, issueRequestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaSupplyRequestResp> supplyRequests(long tenantId,
                                                    String status,
                                                    String keyword,
                                                    Long applicantId,
                                                    int offset,
                                                    int limit) {
        StringBuilder sql = new StringBuilder(REQUEST_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendRequestFilters(sql, args, status, keyword, applicantId);
        sql.append(" ORDER BY r.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), REQUEST_MAPPER, args.toArray());
    }

    public long countSupplyRequests(long tenantId, String status, String keyword, Long applicantId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + REQUEST_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendRequestFilters(sql, args, status, keyword, applicantId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaSupplyRequestResp supplyRequest(long tenantId, long id) {
        List<OaSupplyRequestResp> rows = jdbcTemplate
            .query(REQUEST_SELECT + " AND r.id = ?", REQUEST_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaSupplyRequestItemResp> supplyRequestItems(long tenantId, long requestId) {
        return jdbcTemplate.query("""
            SELECT ri.item_id, i.code, i.name, i.unit, ri.quantity, i.stock
              FROM oa_supply_request_item ri
              JOIN oa_supply_item i ON i.tenant_id = ri.tenant_id AND i.id = ri.item_id
             WHERE ri.tenant_id = ? AND ri.supply_request_id = ?
             ORDER BY ri.item_id
            """, (rs, rowNum) -> new OaSupplyRequestItemResp(rs.getLong("item_id"), rs.getString("code"), rs
            .getString("name"), rs.getString("unit"), rs.getInt("quantity"), rs.getInt("stock")), tenantId, requestId);
    }

    public long insertAsset(long tenantId,
                            String name,
                            String category,
                            String spec,
                            String status,
                            String location,
                            BigDecimal originalValue,
                            Long purchaseId,
                            LocalDate acquiredDate,
                            long userId,
                            LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_asset
                  (tenant_id, name, category, spec, status, location, original_value, purchase_id, acquired_date,
                   version, create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, category);
            ps.setString(4, spec);
            ps.setString(5, status);
            ps.setString(6, location);
            ps.setBigDecimal(7, originalValue);
            ps.setObject(8, purchaseId);
            ps.setDate(9, Date.valueOf(acquiredDate));
            ps.setLong(10, userId);
            ps.setObject(11, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void assignAssetNo(long tenantId, long id, String assetNo) {
        jdbcTemplate.update("UPDATE oa_asset SET asset_no = ? WHERE tenant_id = ? AND id = ?", assetNo, tenantId, id);
    }

    public AssetRow lockAsset(long tenantId, long id) {
        List<AssetRow> rows = jdbcTemplate.query("""
            SELECT id, asset_no, name, status, holder_id, location, original_value, version
              FROM oa_asset WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, ASSET_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AssetRow lockAssetByNo(long tenantId, String assetNo) {
        List<AssetRow> rows = jdbcTemplate.query("""
            SELECT id, asset_no, name, status, holder_id, location, original_value, version
              FROM oa_asset WHERE tenant_id = ? AND asset_no = ?
             FOR UPDATE
            """, ASSET_ROW_MAPPER, tenantId, assetNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int moveAsset(long tenantId,
                         long id,
                         int version,
                         String status,
                         Long holderId,
                         Long deptId,
                         String location,
                         LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_asset
               SET status = ?, holder_id = ?, dept_id = ?, location = ?, version = version + 1, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, status, holderId, deptId, location, now, tenantId, id, version);
    }

    public void insertAssetLog(long tenantId,
                               long assetId,
                               String action,
                               String fromStatus,
                               String toStatus,
                               Long fromHolderId,
                               Long toHolderId,
                               String location,
                               String refType,
                               Long refId,
                               String requestId,
                               long operatorId,
                               String remark,
                               LocalDateTime now) {
        jdbcTemplate
            .update("""
                INSERT INTO oa_asset_log
                  (tenant_id, asset_id, action, from_status, to_status, from_holder_id, to_holder_id, location, ref_type,
                   ref_id, request_id, operator_id, remark, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, assetId, action, fromStatus, toStatus, fromHolderId, toHolderId, location, refType, refId, requestId, operatorId, remark, now);
    }

    public Long assetIdByLogRequest(long tenantId, String requestId) {
        List<Long> rows = jdbcTemplate.query("""
            SELECT asset_id FROM oa_asset_log WHERE tenant_id = ? AND request_id = ?
            """, (rs, rowNum) -> rs.getLong("asset_id"), tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaAssetLogResp> assetLogs(long tenantId, long assetId) {
        return jdbcTemplate.query("""
            SELECT g.id, g.asset_id, g.action, g.from_status, g.to_status, g.from_holder_id,
                   COALESCE(NULLIF(fh.nickname, ''), fh.username) AS from_holder_name, g.to_holder_id,
                   COALESCE(NULLIF(th.nickname, ''), th.username) AS to_holder_name, g.location, g.ref_type, g.ref_id,
                   g.request_id, g.operator_id, COALESCE(NULLIF(o.nickname, ''), o.username) AS operator_name, g.remark,
                   g.create_time
              FROM oa_asset_log g
              LEFT JOIN sys_user fh ON fh.tenant_id = g.tenant_id AND fh.id = g.from_holder_id
              LEFT JOIN sys_user th ON th.tenant_id = g.tenant_id AND th.id = g.to_holder_id
              LEFT JOIN sys_user o ON o.tenant_id = g.tenant_id AND o.id = g.operator_id
             WHERE g.tenant_id = ? AND g.asset_id = ?
             ORDER BY g.id DESC
            """, (rs, rowNum) -> new OaAssetLogResp(rs.getLong("id"), rs.getLong("asset_id"), rs.getString("action"), rs
            .getString("from_status"), rs.getString("to_status"), nullableLong(rs, "from_holder_id"), rs
                .getString("from_holder_name"), nullableLong(rs, "to_holder_id"), rs.getString("to_holder_name"), rs
                    .getString("location"), rs.getString("ref_type"), nullableLong(rs, "ref_id"), rs
                        .getString("request_id"), rs.getLong("operator_id"), rs.getString("operator_name"), rs
                            .getString("remark"), timestamp(rs, "create_time")), tenantId, assetId);
    }

    public List<OaAssetResp> assets(long tenantId,
                                    String status,
                                    String keyword,
                                    Long holderId,
                                    Long purchaseId,
                                    int offset,
                                    int limit) {
        StringBuilder sql = new StringBuilder(ASSET_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendAssetFilters(sql, args, status, keyword, holderId, purchaseId);
        sql.append(" ORDER BY s.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), ASSET_MAPPER, args.toArray());
    }

    public long countAssets(long tenantId, String status, String keyword, Long holderId, Long purchaseId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + ASSET_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendAssetFilters(sql, args, status, keyword, holderId, purchaseId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaAssetResp asset(long tenantId, long id) {
        List<OaAssetResp> rows = jdbcTemplate.query(ASSET_SELECT + " AND s.id = ?", ASSET_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int activeScrapCount(long tenantId, long assetId) {
        return jdbcTemplate.query("""
            SELECT id FROM oa_asset_scrap
             WHERE tenant_id = ? AND asset_id = ? AND status IN ('APPROVING', 'PENDING_SCRAP')
             FOR UPDATE
            """, (rs, rowNum) -> rs.getLong("id"), tenantId, assetId).size();
    }

    public long insertScrap(long tenantId,
                            long applicationId,
                            long applicantId,
                            long assetId,
                            String status,
                            LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_asset_scrap (tenant_id, application_id, applicant_id, asset_id, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, applicationId);
            ps.setLong(3, applicantId);
            ps.setLong(4, assetId);
            ps.setString(5, status);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public ScrapRow lockScrapByApplication(long tenantId, long applicationId) {
        List<ScrapRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, asset_id, status FROM oa_asset_scrap
             WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new ScrapRow(rs.getLong("id"), rs.getLong("application_id"), rs
            .getLong("applicant_id"), rs.getLong("asset_id"), rs.getString("status")), tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ScrapRow lockScrap(long tenantId, long id) {
        List<ScrapRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, asset_id, status FROM oa_asset_scrap
             WHERE tenant_id = ? AND id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new ScrapRow(rs.getLong("id"), rs.getLong("application_id"), rs
            .getLong("applicant_id"), rs.getLong("asset_id"), rs.getString("status")), tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionScrap(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_asset_scrap SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int markScrapped(long tenantId, long id, long executorId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_asset_scrap SET status = 'SCRAPPED', executor_id = ?, executed_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_SCRAP'
            """, executorId, now, now, tenantId, id);
    }

    public List<OaAssetScrapResp> scraps(long tenantId,
                                         String status,
                                         Long applicantId,
                                         Long assetId,
                                         int offset,
                                         int limit) {
        StringBuilder sql = new StringBuilder(SCRAP_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendScrapFilters(sql, args, status, applicantId, assetId);
        sql.append(" ORDER BY x.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), SCRAP_MAPPER, args.toArray());
    }

    public long countScraps(long tenantId, String status, Long applicantId, Long assetId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + SCRAP_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendScrapFilters(sql, args, status, applicantId, assetId);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaAssetScrapResp scrap(long tenantId, long id) {
        List<OaAssetScrapResp> rows = jdbcTemplate.query(SCRAP_SELECT + " AND x.id = ?", SCRAP_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Long> openStocktakes(long tenantId) {
        return jdbcTemplate.query("""
            SELECT id FROM oa_stocktake WHERE tenant_id = ? AND status = 'OPEN' FOR UPDATE
            """, (rs, rowNum) -> rs.getLong("id"), tenantId);
    }

    public long insertStocktake(long tenantId, String title, long userId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection
                .prepareStatement("""
                    INSERT INTO oa_stocktake (tenant_id, title, status, create_user, create_time) VALUES (?, ?, 'OPEN', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, title);
            ps.setLong(3, userId);
            ps.setObject(4, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    /**
     * 快照启用用品的当前库存与未报废资产（每张卡片账面 1），作为本次盘点的账面数量。
     */
    public int snapshotStocktake(long tenantId, long stocktakeId) {
        int supplies = jdbcTemplate
            .update("""
                INSERT INTO oa_stocktake_line (tenant_id, stocktake_id, target_type, target_id, target_code, target_name, book_qty)
                SELECT tenant_id, ?, 'SUPPLY', id, code, name, stock FROM oa_supply_item
                 WHERE tenant_id = ? AND status = 'ENABLED'
                """, stocktakeId, tenantId);
        int assets = jdbcTemplate
            .update("""
                INSERT INTO oa_stocktake_line (tenant_id, stocktake_id, target_type, target_id, target_code, target_name, book_qty)
                SELECT tenant_id, ?, 'ASSET', id, asset_no, name, 1 FROM oa_asset
                 WHERE tenant_id = ? AND status <> 'SCRAPPED'
                """, stocktakeId, tenantId);
        return supplies + assets;
    }

    public String lockStocktakeStatus(long tenantId, long id) {
        List<String> rows = jdbcTemplate.query("""
            SELECT status FROM oa_stocktake WHERE tenant_id = ? AND id = ? FOR UPDATE
            """, (rs, rowNum) -> rs.getString("status"), tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaStocktakeResp> stocktakes(long tenantId, int offset, int limit) {
        return jdbcTemplate
            .query(STOCKTAKE_SELECT + " ORDER BY t.id DESC LIMIT ? OFFSET ?", STOCKTAKE_MAPPER, tenantId, limit, offset);
    }

    public long countStocktakes(long tenantId) {
        Long count = jdbcTemplate
            .queryForObject("SELECT COUNT(*) FROM oa_stocktake WHERE tenant_id = ?", Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public OaStocktakeResp stocktake(long tenantId, long id) {
        List<OaStocktakeResp> rows = jdbcTemplate
            .query(STOCKTAKE_SELECT + " AND t.id = ?", STOCKTAKE_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaStocktakeLineResp> stocktakeLines(long tenantId, long stocktakeId) {
        return jdbcTemplate.query("""
            SELECT id, target_type, target_id, target_code, target_name, book_qty, counted_qty, adjusted_qty
              FROM oa_stocktake_line WHERE tenant_id = ? AND stocktake_id = ?
             ORDER BY target_type DESC, target_id
            """, LINE_MAPPER, tenantId, stocktakeId);
    }

    public int countLine(long tenantId, long stocktakeId, long lineId, int countedQty, boolean asset) {
        return jdbcTemplate.update("""
            UPDATE oa_stocktake_line SET counted_qty = ?
             WHERE tenant_id = ? AND stocktake_id = ? AND id = ? AND target_type = ?
            """, countedQty, tenantId, stocktakeId, lineId, asset ? "ASSET" : "SUPPLY");
    }

    public String lineType(long tenantId, long stocktakeId, long lineId) {
        List<String> rows = jdbcTemplate.query("""
            SELECT target_type FROM oa_stocktake_line WHERE tenant_id = ? AND stocktake_id = ? AND id = ?
            """, (rs, rowNum) -> rs.getString("target_type"), tenantId, stocktakeId, lineId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setAdjusted(long tenantId, long lineId, int adjustedQty) {
        jdbcTemplate.update("""
            UPDATE oa_stocktake_line SET adjusted_qty = ? WHERE tenant_id = ? AND id = ?
            """, adjustedQty, tenantId, lineId);
    }

    public int closeStocktake(long tenantId, long id, long userId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_stocktake SET status = 'CLOSED', closed_by = ?, closed_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'OPEN'
            """, userId, now, tenantId, id);
    }

    private static void appendRequestFilters(StringBuilder sql,
                                             List<Object> args,
                                             String status,
                                             String keyword,
                                             Long applicantId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND r.applicant_id = ?");
            args.add(applicantId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (a.title LIKE ? OR u.username LIKE ? OR u.nickname LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 3; i++) {
                args.add(like);
            }
        }
    }

    private static void appendAssetFilters(StringBuilder sql,
                                           List<Object> args,
                                           String status,
                                           String keyword,
                                           Long holderId,
                                           Long purchaseId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND s.status = ?");
            args.add(status);
        }
        if (holderId != null) {
            sql.append(" AND s.holder_id = ?");
            args.add(holderId);
        }
        if (purchaseId != null) {
            sql.append(" AND s.purchase_id = ?");
            args.add(purchaseId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (s.asset_no LIKE ? OR s.name LIKE ? OR s.location LIKE ? OR h.username LIKE ? OR h.nickname LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
    }

    private static void appendScrapFilters(StringBuilder sql,
                                           List<Object> args,
                                           String status,
                                           Long applicantId,
                                           Long assetId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND x.status = ?");
            args.add(status);
        }
        if (applicantId != null) {
            sql.append(" AND x.applicant_id = ?");
            args.add(applicantId);
        }
        if (assetId != null) {
            sql.append(" AND x.asset_id = ?");
            args.add(assetId);
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public record AssetRow(long id, String assetNo, String name, String status, Long holderId, String location,
                           BigDecimal originalValue, int version) {
    }

    public record SupplyRequestRow(long id, long applicationId, long applicantId, String status) {
    }

    public record ScrapRow(long id, long applicationId, long applicantId, long assetId, String status) {
    }
}
