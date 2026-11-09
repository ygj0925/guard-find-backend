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

import com.guardfind.backend.oa.model.OaContractResp;
import com.guardfind.backend.oa.model.OaContractVersionResp;
import com.guardfind.backend.oa.model.OaSealRequestResp;
import com.guardfind.backend.oa.model.OaSealResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
 * 合同台账与版本、印章档案与用印申请的显式租户 SQL；状态只经带前置状态条件的更新迁移。
 */
@Repository
public class OaContractRepository {

    private static final String CONTRACT_FROM = """
          FROM oa_contract c
          JOIN oa_application a ON a.tenant_id = c.tenant_id AND a.id = c.current_application_id
          LEFT JOIN sys_user o ON o.tenant_id = c.tenant_id AND o.id = c.owner_id
          LEFT JOIN sys_user sb ON sb.tenant_id = c.tenant_id AND sb.id = c.signed_by
          LEFT JOIN sys_user ab ON ab.tenant_id = c.tenant_id AND ab.id = c.archived_by
         WHERE c.tenant_id = ?
        """;

    private static final String CONTRACT_SELECT = """
        SELECT c.id, c.contract_no, c.title, c.counterparty, c.contract_type, c.amount, c.start_date, c.end_date,
               c.owner_id, COALESCE(NULLIF(o.nickname, ''), o.username) AS owner_name, c.current_version,
               c.current_application_id, a.status AS application_status, c.status, c.signed_date, c.signatories,
               c.sign_remark, c.signed_by, COALESCE(NULLIF(sb.nickname, ''), sb.username) AS signed_by_name,
               c.signed_time, c.archive_no, c.archive_location, c.archived_by,
               COALESCE(NULLIF(ab.nickname, ''), ab.username) AS archived_by_name, c.archived_time, c.create_time,
               c.update_time
        """ + CONTRACT_FROM;

    private static final RowMapper<OaContractResp> CONTRACT_MAPPER = (rs, rowNum) -> new OaContractResp(rs
        .getLong("id"), rs.getString("contract_no"), rs.getString("title"), rs.getString("counterparty"), rs
            .getString("contract_type"), rs.getBigDecimal("amount"), date(rs, "start_date"), date(rs, "end_date"), rs
                .getLong("owner_id"), rs.getString("owner_name"), rs.getInt("current_version"), rs
                    .getLong("current_application_id"), rs.getString("application_status"), rs
                        .getString("status"), date(rs, "signed_date"), rs.getString("signatories"), rs
                            .getString("sign_remark"), nullableLong(rs, "signed_by"), rs
                                .getString("signed_by_name"), timestamp(rs, "signed_time"), rs
                                    .getString("archive_no"), rs
                                        .getString("archive_location"), nullableLong(rs, "archived_by"), rs
                                            .getString("archived_by_name"), timestamp(rs, "archived_time"), timestamp(rs, "create_time"), timestamp(rs, "update_time"));

    private static final RowMapper<OaContractVersionResp> VERSION_MAPPER = (rs, rowNum) -> new OaContractVersionResp(rs
        .getLong("id"), rs.getInt("version_no"), rs.getLong("application_id"), rs.getString("application_status"), rs
            .getString("title"), rs.getString("counterparty"), rs.getString("contract_type"), rs
                .getBigDecimal("amount"), date(rs, "start_date"), date(rs, "end_date"), rs.getString("summary"), rs
                    .getString("change_note"), rs.getString("status"), rs.getLong("applicant_id"), rs
                        .getString("applicant_name"), timestamp(rs, "create_time"));

    private static final String SEAL_SELECT = """
        SELECT s.id, s.code, s.name, s.seal_type, s.keeper_id, COALESCE(NULLIF(k.nickname, ''), k.username) AS keeper_name,
               s.status, s.remark, s.version, COALESCE(s.update_time, s.create_time) AS update_time
          FROM oa_seal s
          LEFT JOIN sys_user k ON k.tenant_id = s.tenant_id AND k.id = s.keeper_id
         WHERE s.tenant_id = ?
        """;

    private static final RowMapper<OaSealResp> SEAL_MAPPER = (rs, rowNum) -> new OaSealResp(rs.getLong("id"), rs
        .getString("code"), rs.getString("name"), rs.getString("seal_type"), rs.getLong("keeper_id"), rs
            .getString("keeper_name"), rs.getString("status"), rs.getString("remark"), rs
                .getInt("version"), timestamp(rs, "update_time"));

    private static final String REQUEST_FROM = """
          FROM oa_seal_request r
          JOIN oa_application a ON a.tenant_id = r.tenant_id AND a.id = r.application_id
          JOIN oa_seal s ON s.tenant_id = r.tenant_id AND s.id = r.seal_id
          LEFT JOIN oa_contract c ON c.tenant_id = r.tenant_id AND c.id = r.contract_id
          LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.applicant_id
          LEFT JOIN sys_user k ON k.tenant_id = r.tenant_id AND k.id = s.keeper_id
          LEFT JOIN sys_user e ON e.tenant_id = r.tenant_id AND e.id = r.executor_id
         WHERE r.tenant_id = ?
        """;

    private static final String REQUEST_SELECT = """
        SELECT r.id, r.application_id, a.title, a.status AS application_status, r.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, r.seal_id, s.code AS seal_code,
               s.name AS seal_name, s.seal_type, s.keeper_id, COALESCE(NULLIF(k.nickname, ''), k.username) AS keeper_name,
               r.contract_id, c.contract_no, r.copies, r.use_date, r.status, r.used_copies, r.used_date, r.executor_id,
               COALESCE(NULLIF(e.nickname, ''), e.username) AS executor_name, r.executed_time, r.execute_remark,
               r.create_time
        """ + REQUEST_FROM;

    private static final RowMapper<OaSealRequestResp> REQUEST_MAPPER = (rs, rowNum) -> new OaSealRequestResp(rs
        .getLong("id"), rs.getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getLong("seal_id"), rs
                .getString("seal_code"), rs.getString("seal_name"), rs.getString("seal_type"), rs
                    .getLong("keeper_id"), rs.getString("keeper_name"), nullableLong(rs, "contract_id"), rs
                        .getString("contract_no"), rs.getInt("copies"), date(rs, "use_date"), rs
                            .getString("status"), nullableInt(rs, "used_copies"), date(rs, "used_date"), nullableLong(rs, "executor_id"), rs
                                .getString("executor_name"), timestamp(rs, "executed_time"), rs
                                    .getString("execute_remark"), timestamp(rs, "create_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaContractRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertContract(long tenantId,
                               ContractValues values,
                               long ownerId,
                               long applicationId,
                               LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_contract (tenant_id, title, counterparty, contract_type, amount, start_date, end_date,
                                         owner_id, current_version, current_application_id, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'APPROVING', ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, values.title());
            ps.setString(3, values.counterparty());
            ps.setString(4, values.contractType());
            ps.setBigDecimal(5, values.amount());
            ps.setObject(6, values.startDate());
            ps.setObject(7, values.endDate());
            ps.setLong(8, ownerId);
            ps.setLong(9, applicationId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public void assignContractNo(long tenantId, long id, String contractNo) {
        jdbcTemplate
            .update("UPDATE oa_contract SET contract_no = ? WHERE tenant_id = ? AND id = ?", contractNo, tenantId, id);
    }

    /**
     * 修订：只有已关闭或待签署的合同可以开始新版本，版本号必须与加锁时一致。
     */
    public int reviseContract(long tenantId,
                              long id,
                              int fromVersion,
                              ContractValues values,
                              long applicationId,
                              LocalDateTime now) {
        return jdbcTemplate
            .update("""
                UPDATE oa_contract
                   SET title = ?, counterparty = ?, contract_type = ?, amount = ?, start_date = ?, end_date = ?,
                       current_version = current_version + 1, current_application_id = ?, status = 'APPROVING', update_time = ?
                 WHERE tenant_id = ? AND id = ? AND current_version = ? AND status IN ('CLOSED', 'PENDING_SIGN')
                """, values
                .title(), values.counterparty(), values.contractType(), values.amount(), values.startDate(), values
                    .endDate(), applicationId, now, tenantId, id, fromVersion);
    }

    public void insertVersion(long tenantId,
                              long contractId,
                              int versionNo,
                              long applicationId,
                              ContractValues values,
                              String summary,
                              String changeNote,
                              long applicantId,
                              LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_contract_version (tenant_id, contract_id, version_no, application_id, title, counterparty,
                                             contract_type, amount, start_date, end_date, summary, change_note, status,
                                             applicant_id, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVING', ?, ?)
            """, tenantId, contractId, versionNo, applicationId, values.title(), values.counterparty(), values
            .contractType(), values.amount(), values.startDate(), values
                .endDate(), summary, changeNote, applicantId, now);
    }

    public ContractRow lockContractByNo(long tenantId, String contractNo) {
        return lockContract("contract_no = ?", tenantId, contractNo);
    }

    public ContractRow lockContract(long tenantId, long id) {
        return lockContract("id = ?", tenantId, id);
    }

    private ContractRow lockContract(String condition, long tenantId, Object key) {
        List<ContractRow> rows = jdbcTemplate.query("""
            SELECT id, contract_no, title, owner_id, current_version, current_application_id, status, end_date
              FROM oa_contract
             WHERE tenant_id = ? AND %s
             FOR UPDATE
            """.formatted(condition), (rs, rowNum) -> new ContractRow(rs.getLong("id"), rs.getString("contract_no"), rs
            .getString("title"), rs.getLong("owner_id"), rs.getInt("current_version"), rs
                .getLong("current_application_id"), rs.getString("status"), date(rs, "end_date")), tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public VersionRow lockVersionByApplication(long tenantId, long applicationId) {
        List<VersionRow> rows = jdbcTemplate.query("""
            SELECT id, contract_id, version_no, status FROM oa_contract_version
             WHERE tenant_id = ? AND application_id = ?
             FOR UPDATE
            """, (rs, rowNum) -> new VersionRow(rs.getLong("id"), rs.getLong("contract_id"), rs.getInt("version_no"), rs
            .getString("status")), tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionVersion(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_contract_version SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int transitionContract(long tenantId,
                                  long id,
                                  long applicationId,
                                  String from,
                                  String to,
                                  LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_contract SET status = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND current_application_id = ? AND status = ?
            """, to, now, tenantId, id, applicationId, from);
    }

    public Long contractIdBySignRequest(long tenantId, String requestId) {
        return firstLong("SELECT id FROM oa_contract WHERE tenant_id = ? AND sign_request_id = ?", tenantId, requestId);
    }

    public Long contractIdByArchiveRequest(long tenantId, String requestId) {
        return firstLong("SELECT id FROM oa_contract WHERE tenant_id = ? AND archive_request_id = ?", tenantId, requestId);
    }

    public Long contractIdByArchiveNo(long tenantId, String archiveNo) {
        return firstLong("SELECT id FROM oa_contract WHERE tenant_id = ? AND archive_no = ?", tenantId, archiveNo);
    }

    public int markSigned(long tenantId,
                          long id,
                          int version,
                          String requestId,
                          LocalDate signedDate,
                          String signatories,
                          String remark,
                          long operatorId,
                          LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_contract
               SET status = 'SIGNED', sign_request_id = ?, signed_date = ?, signatories = ?, sign_remark = ?,
                   signed_by = ?, signed_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND current_version = ? AND status = 'PENDING_SIGN'
            """, requestId, signedDate, signatories, remark, operatorId, now, now, tenantId, id, version);
    }

    public int markArchived(long tenantId,
                            long id,
                            String requestId,
                            String archiveNo,
                            String location,
                            long operatorId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_contract
               SET status = 'ARCHIVED', archive_request_id = ?, archive_no = ?, archive_location = ?, archived_by = ?,
                   archived_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'SIGNED'
            """, requestId, archiveNo, location, operatorId, now, now, tenantId, id);
    }

    public List<OaContractResp> contracts(long tenantId,
                                          String status,
                                          String keyword,
                                          Long ownerId,
                                          LocalDate expiringBefore,
                                          int offset,
                                          int limit) {
        StringBuilder sql = new StringBuilder(CONTRACT_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendContractFilters(sql, args, status, keyword, ownerId, expiringBefore);
        sql.append(expiringBefore == null ? " ORDER BY c.id DESC" : " ORDER BY c.end_date, c.id");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), CONTRACT_MAPPER, args.toArray());
    }

    public long countContracts(long tenantId, String status, String keyword, Long ownerId, LocalDate expiringBefore) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + CONTRACT_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendContractFilters(sql, args, status, keyword, ownerId, expiringBefore);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaContractResp contract(long tenantId, long id) {
        List<OaContractResp> rows = jdbcTemplate
            .query(CONTRACT_SELECT + " AND c.id = ?", CONTRACT_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaContractVersionResp> versions(long tenantId, long contractId) {
        return jdbcTemplate.query("""
            SELECT v.id, v.version_no, v.application_id, a.status AS application_status, v.title, v.counterparty,
                   v.contract_type, v.amount, v.start_date, v.end_date, v.summary, v.change_note, v.status,
                   v.applicant_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, v.create_time
              FROM oa_contract_version v
              JOIN oa_application a ON a.tenant_id = v.tenant_id AND a.id = v.application_id
              LEFT JOIN sys_user u ON u.tenant_id = v.tenant_id AND u.id = v.applicant_id
             WHERE v.tenant_id = ? AND v.contract_id = ?
             ORDER BY v.version_no DESC
            """, VERSION_MAPPER, tenantId, contractId);
    }

    public List<Long> versionApplicationIds(long tenantId, long contractId) {
        return jdbcTemplate.queryForList("""
            SELECT application_id FROM oa_contract_version WHERE tenant_id = ? AND contract_id = ?
            """, Long.class, tenantId, contractId);
    }

    /**
     * 跨租户扫描已签署/已归档且到期日落在区间内的合同，供定时提醒按租户写通知。
     */
    public List<ExpiringContract> expiring(LocalDate from, LocalDate to, int limit) {
        return jdbcTemplate.query("""
            SELECT tenant_id, id, contract_no, title, end_date, owner_id, current_application_id
              FROM oa_contract
             WHERE status IN ('SIGNED', 'ARCHIVED') AND end_date BETWEEN ? AND ?
             ORDER BY end_date, id
             LIMIT ?
            """, (rs, rowNum) -> new ExpiringContract(rs.getLong("tenant_id"), rs.getLong("id"), rs
            .getString("contract_no"), rs.getString("title"), date(rs, "end_date"), rs.getLong("owner_id"), rs
                .getLong("current_application_id")), from, to, limit);
    }

    public List<OaSealResp> seals(long tenantId, String status) {
        if (status == null) {
            return jdbcTemplate.query(SEAL_SELECT + " ORDER BY s.id", SEAL_MAPPER, tenantId);
        }
        return jdbcTemplate.query(SEAL_SELECT + " AND s.status = ? ORDER BY s.id", SEAL_MAPPER, tenantId, status);
    }

    public OaSealResp seal(long tenantId, long id) {
        List<OaSealResp> rows = jdbcTemplate.query(SEAL_SELECT + " AND s.id = ?", SEAL_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertSeal(long tenantId,
                           String code,
                           String name,
                           String sealType,
                           long keeperId,
                           String status,
                           String remark,
                           long userId,
                           LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection
                .prepareStatement("""
                    INSERT INTO oa_seal (tenant_id, code, name, seal_type, keeper_id, status, remark, version, create_user, create_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, sealType);
            ps.setLong(5, keeperId);
            ps.setString(6, status);
            ps.setString(7, remark);
            ps.setLong(8, userId);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateSeal(long tenantId,
                          long id,
                          int version,
                          String code,
                          String name,
                          String sealType,
                          long keeperId,
                          String status,
                          String remark,
                          long userId,
                          LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_seal
               SET code = ?, name = ?, seal_type = ?, keeper_id = ?, status = ?, remark = ?, version = version + 1,
                   update_user = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, code, name, sealType, keeperId, status, remark, userId, now, tenantId, id, version);
    }

    public SealRow lockSealByCode(long tenantId, String code) {
        return lockSeal("code = ?", tenantId, code);
    }

    public SealRow lockSeal(long tenantId, long id) {
        return lockSeal("id = ?", tenantId, id);
    }

    private SealRow lockSeal(String condition, long tenantId, Object key) {
        List<SealRow> rows = jdbcTemplate.query("""
            SELECT id, code, name, seal_type, keeper_id, status FROM oa_seal
             WHERE tenant_id = ? AND %s
             FOR UPDATE
            """.formatted(condition), (rs, rowNum) -> new SealRow(rs.getLong("id"), rs.getString("code"), rs
            .getString("name"), rs.getString("seal_type"), rs.getLong("keeper_id"), rs
                .getString("status")), tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertSealRequest(long tenantId,
                                  long applicationId,
                                  long applicantId,
                                  long sealId,
                                  Long contractId,
                                  int copies,
                                  LocalDate useDate,
                                  LocalDateTime now) {
        jdbcTemplate
            .update("""
                INSERT INTO oa_seal_request (tenant_id, application_id, applicant_id, seal_id, contract_id, copies, use_date,
                                             status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'APPROVING', ?)
                """, tenantId, applicationId, applicantId, sealId, contractId, copies, useDate, now);
    }

    public SealRequestRow lockSealRequestByApplication(long tenantId, long applicationId) {
        return lockSealRequest("application_id = ?", tenantId, applicationId);
    }

    public SealRequestRow lockSealRequest(long tenantId, long id) {
        return lockSealRequest("id = ?", tenantId, id);
    }

    private SealRequestRow lockSealRequest(String condition, long tenantId, long key) {
        List<SealRequestRow> rows = jdbcTemplate.query("""
            SELECT id, application_id, applicant_id, seal_id, copies, status FROM oa_seal_request
             WHERE tenant_id = ? AND %s
             FOR UPDATE
            """.formatted(condition), (rs, rowNum) -> new SealRequestRow(rs.getLong("id"), rs
            .getLong("application_id"), rs.getLong("applicant_id"), rs.getLong("seal_id"), rs.getInt("copies"), rs
                .getString("status")), tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionSealRequest(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_seal_request SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public Long sealRequestIdByExecuteRequest(long tenantId, String requestId) {
        return firstLong("SELECT id FROM oa_seal_request WHERE tenant_id = ? AND execute_request_id = ?", tenantId, requestId);
    }

    public int markSealUsed(long tenantId,
                            long id,
                            String requestId,
                            int usedCopies,
                            LocalDate usedDate,
                            String remark,
                            long executorId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_seal_request
               SET status = 'USED', execute_request_id = ?, used_copies = ?, used_date = ?, execute_remark = ?,
                   executor_id = ?, executed_time = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_SEAL'
            """, requestId, usedCopies, usedDate, remark, executorId, now, now, tenantId, id);
    }

    public List<OaSealRequestResp> sealRequests(long tenantId, RequestFilter filter, int offset, int limit) {
        StringBuilder sql = new StringBuilder(REQUEST_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendRequestFilters(sql, args, filter);
        sql.append(" ORDER BY r.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), REQUEST_MAPPER, args.toArray());
    }

    public long countSealRequests(long tenantId, RequestFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + REQUEST_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendRequestFilters(sql, args, filter);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public OaSealRequestResp sealRequest(long tenantId, long id) {
        List<OaSealRequestResp> rows = jdbcTemplate
            .query(REQUEST_SELECT + " AND r.id = ?", REQUEST_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long firstLong(String sql, Object... args) {
        List<Long> rows = jdbcTemplate.queryForList(sql, Long.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void appendContractFilters(StringBuilder sql,
                                              List<Object> args,
                                              String status,
                                              String keyword,
                                              Long ownerId,
                                              LocalDate expiringBefore) {
        if (status != null) {
            sql.append(" AND c.status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (c.contract_no LIKE ? OR c.title LIKE ? OR c.counterparty LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (ownerId != null) {
            sql.append(" AND c.owner_id = ?");
            args.add(ownerId);
        }
        if (expiringBefore != null) {
            sql.append(" AND c.status IN ('SIGNED', 'ARCHIVED') AND c.end_date <= ?");
            args.add(expiringBefore);
        }
    }

    private static void appendRequestFilters(StringBuilder sql, List<Object> args, RequestFilter filter) {
        if (filter.status() != null) {
            sql.append(" AND r.status = ?");
            args.add(filter.status());
        }
        if (filter.applicantId() != null) {
            sql.append(" AND r.applicant_id = ?");
            args.add(filter.applicantId());
        }
        if (filter.keeperId() != null) {
            sql.append(" AND s.keeper_id = ?");
            args.add(filter.keeperId());
        }
        if (filter.contractId() != null) {
            sql.append(" AND r.contract_id = ?");
            args.add(filter.contractId());
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

    public record ContractValues(String title, String counterparty, String contractType, BigDecimal amount,
                                 LocalDate startDate, LocalDate endDate) {
    }

    public record ContractRow(long id, String contractNo, String title, long ownerId, int currentVersion,
                              long currentApplicationId, String status, LocalDate endDate) {
    }

    public record VersionRow(long id, long contractId, int versionNo, String status) {
    }

    public record SealRow(long id, String code, String name, String sealType, long keeperId, String status) {
    }

    public record SealRequestRow(long id, long applicationId, long applicantId, long sealId, int copies,
                                 String status) {
    }

    public record RequestFilter(String status, Long applicantId, Long keeperId, Long contractId) {
    }

    public record ExpiringContract(long tenantId, long id, String contractNo, String title, LocalDate endDate,
                                   long ownerId, long applicationId) {
    }
}
