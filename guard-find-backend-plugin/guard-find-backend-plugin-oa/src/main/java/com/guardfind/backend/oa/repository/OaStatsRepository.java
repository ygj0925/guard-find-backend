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

import com.guardfind.backend.oa.model.OaExportTaskResp;
import com.guardfind.backend.oa.model.OaWorkbenchApplication;
import com.guardfind.backend.oa.model.OaWorkbenchItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台、统计与导出任务的显式租户 SQL。
 *
 * <p>
 * 金额按业务表与申请一对一关联取值：报销合计、借款金额、采购合计、合同版本金额、出差预算，其他业务类型无金额。统计以提交时间落在区间内的非草稿申请为口径。
 * </p>
 */
@Repository
public class OaStatsRepository {

    private static final String AMOUNT_JOINS = """
         LEFT JOIN oa_expense ex ON ex.tenant_id = a.tenant_id AND ex.application_id = a.id
         LEFT JOIN oa_loan ln ON ln.tenant_id = a.tenant_id AND ln.application_id = a.id
         LEFT JOIN oa_purchase pu ON pu.tenant_id = a.tenant_id AND pu.application_id = a.id
         LEFT JOIN oa_contract_version cv ON cv.tenant_id = a.tenant_id AND cv.application_id = a.id
         LEFT JOIN oa_travel tr ON tr.tenant_id = a.tenant_id AND tr.application_id = a.id
        """;

    private static final String AMOUNT = "COALESCE(ex.total_amount, ln.amount, pu.total_amount, cv.amount, tr.budget_amount)";

    // oa_application.business_type 存的是流程模型 key，统计口径的业务类型取自发布版本所属模型
    private static final String TYPE_JOINS = """
         LEFT JOIN oa_process_release rl ON rl.tenant_id = a.tenant_id AND rl.id = a.release_id
         LEFT JOIN oa_process_model pm ON pm.tenant_id = rl.tenant_id AND pm.id = rl.model_id
        """;

    private static final String BIZ_TYPE = "COALESCE(pm.business_type, 'GENERIC')";

    private static final RowMapper<OaExportTaskResp> EXPORT_MAPPER = (rs, rowNum) -> new OaExportTaskResp(rs
        .getLong("id"), rs.getString("export_type"), date(rs, "date_from"), date(rs, "date_to"), rs
            .getString("business_type"), rs.getString("status"), rs
                .getString("file_name"), nullableInt(rs, "row_count"), rs
                    .getString("error_message"), timestamp(rs, "create_time"), timestamp(rs, "finish_time"), timestamp(rs, "expire_time"));

    private final JdbcTemplate jdbcTemplate;

    public OaStatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countMyApplications(long tenantId, long userId, String status) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_application
             WHERE tenant_id = ? AND applicant_id = ? AND status = ? AND deleted = 0
            """, Long.class, tenantId, userId, status);
        return count == null ? 0 : count;
    }

    public List<OaWorkbenchApplication> recentApplications(long tenantId, long userId, int limit) {
        return jdbcTemplate.query("""
            SELECT a.id, %s AS business_type, a.title, a.status, a.submitted_time FROM oa_application a
            %s
             WHERE a.tenant_id = ? AND a.applicant_id = ? AND a.status <> 'DRAFT' AND a.deleted = 0
             ORDER BY a.id DESC LIMIT ?
            """.formatted(BIZ_TYPE, TYPE_JOINS), (rs, rowNum) -> new OaWorkbenchApplication(rs.getLong("id"), rs
            .getString("business_type"), rs.getString("title"), rs
                .getString("status"), timestamp(rs, "submitted_time")), tenantId, userId, limit);
    }

    public List<OaWorkbenchItem> upcomingBookings(long tenantId, long userId, LocalDateTime now, int limit) {
        return jdbcTemplate.query("""
            SELECT b.id, b.subject, r.name AS room_name, b.start_time, b.end_time, b.status
              FROM oa_meeting_booking b
              LEFT JOIN oa_meeting_room r ON r.tenant_id = b.tenant_id AND r.id = b.room_id
             WHERE b.tenant_id = ? AND b.booker_id = ? AND b.status IN ('APPROVING', 'BOOKED') AND b.end_time > ?
             ORDER BY b.start_time LIMIT ?
            """, (rs, rowNum) -> new OaWorkbenchItem(rs.getLong("id"), rs.getString("subject"), rs
            .getString("room_name"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                .getString("status")), tenantId, userId, now, limit);
    }

    public List<OaWorkbenchItem> activeVehicleRequests(long tenantId, long userId, int limit) {
        return jdbcTemplate.query("""
            SELECT id, purpose, destination, start_time, end_time, status FROM oa_vehicle_request
             WHERE tenant_id = ? AND applicant_id = ?
               AND status IN ('APPROVING', 'PENDING_DISPATCH', 'DISPATCHED', 'IN_USE')
             ORDER BY start_time LIMIT ?
            """, (rs, rowNum) -> new OaWorkbenchItem(rs.getLong("id"), rs.getString("purpose"), rs
            .getString("destination"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                .getString("status")), tenantId, userId, limit);
    }

    public boolean dailyReportSubmitted(long tenantId, long userId, LocalDate day) {
        Long count = jdbcTemplate
            .queryForObject("""
                SELECT COUNT(*) FROM oa_work_report
                 WHERE tenant_id = ? AND author_id = ? AND report_type = 'DAILY' AND period_start = ? AND status = 'SUBMITTED'
                """, Long.class, tenantId, userId, Date
                .valueOf(day));
        return count != null && count > 0;
    }

    /**
     * 按业务类型与状态分组：数量、金额合计，以及已结束申请的平均耗时（秒）。
     */
    public List<TypeStatusRow> typeStatusRows(long tenantId,
                                              LocalDateTime from,
                                              LocalDateTime to,
                                              String businessType) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        String filter = typeFilter(businessType, args);
        return jdbcTemplate
            .query("""
                SELECT %s AS business_type, a.status, COUNT(*) AS cnt, SUM(%s) AS amount,
                       AVG(CASE WHEN a.finished_time IS NOT NULL AND a.status IN ('APPROVED', 'REJECTED')
                                THEN TIMESTAMPDIFF(SECOND, a.submitted_time, a.finished_time) END) AS avg_seconds,
                       SUM(CASE WHEN a.finished_time IS NOT NULL AND a.status IN ('APPROVED', 'REJECTED') THEN 1 ELSE 0 END) AS finished
                  FROM oa_application a
                %s%s
                 WHERE a.tenant_id = ? AND a.deleted = 0 AND a.status <> 'DRAFT'
                   AND a.submitted_time >= ? AND a.submitted_time < ? %s
                 GROUP BY %s, a.status
                """
                .formatted(BIZ_TYPE, AMOUNT, AMOUNT_JOINS, TYPE_JOINS, filter, BIZ_TYPE), (rs,
                                                                                           rowNum) -> new TypeStatusRow(rs
                                                                                               .getString("business_type"), rs
                                                                                                   .getString("status"), rs
                                                                                                       .getLong("cnt"), decimal(rs, "amount"), nullableDouble(rs, "avg_seconds"), rs
                                                                                                           .getLong("finished")), args
                                                                                                               .toArray());
    }

    public List<DeptRow> deptRows(long tenantId, LocalDateTime from, LocalDateTime to, String businessType, int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        String filter = typeFilter(businessType, args);
        args.add(limit);
        return jdbcTemplate.query("""
            SELECT COALESCE(a.applicant_dept_name, '未分配部门') AS dept_name, COUNT(*) AS cnt,
                   SUM(CASE WHEN a.status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
                   SUM(CASE WHEN a.status = 'APPROVED' THEN %s END) AS approved_amount
              FROM oa_application a
            %s%s
             WHERE a.tenant_id = ? AND a.deleted = 0 AND a.status <> 'DRAFT'
               AND a.submitted_time >= ? AND a.submitted_time < ? %s
             GROUP BY COALESCE(a.applicant_dept_name, '未分配部门')
             ORDER BY cnt DESC, dept_name LIMIT ?
            """.formatted(AMOUNT, AMOUNT_JOINS, TYPE_JOINS, filter), (rs, rowNum) -> new DeptRow(rs
            .getString("dept_name"), rs.getLong("cnt"), rs.getLong("approved"), decimal(rs, "approved_amount")), args
                .toArray());
    }

    public Map<String, ApplicationBrief> runningApplicationsByInstance(long tenantId, Collection<String> instanceIds) {
        Map<String, ApplicationBrief> result = new HashMap<>();
        if (instanceIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(instanceIds);
        jdbcTemplate.query("""
            SELECT a.id, a.process_instance_id, %s AS business_type, a.title, a.applicant_name FROM oa_application a
            %s
             WHERE a.tenant_id = ? AND a.deleted = 0 AND a.status = 'RUNNING' AND a.process_instance_id IN (%s)
            """.formatted(BIZ_TYPE, TYPE_JOINS, placeholders(instanceIds.size())), (RowCallbackHandler)rs -> result
            .put(rs.getString("process_instance_id"), new ApplicationBrief(rs.getLong("id"), rs
                .getString("business_type"), rs.getString("title"), rs.getString("applicant_name"))), args.toArray());
        return result;
    }

    public Map<Long, String> userNames(long tenantId, Collection<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(userIds);
        jdbcTemplate.query("""
            SELECT id, nickname FROM sys_user WHERE tenant_id = ? AND id IN (%s)
            """.formatted(placeholders(userIds.size())), (RowCallbackHandler)rs -> result.put(rs.getLong("id"), rs
            .getString("nickname")), args.toArray());
        return result;
    }

    /**
     * 申请明细导出；不含表单数据快照，避免自由表单中的个人信息随报表外流。多取一行用于判断是否超出上限。
     */
    public List<ApplicationExportRow> exportApplications(long tenantId,
                                                         LocalDateTime from,
                                                         LocalDateTime to,
                                                         String businessType,
                                                         int limit) {
        List<Object> args = new ArrayList<>(List.of(tenantId, from, to));
        String filter = typeFilter(businessType, args);
        args.add(limit);
        return jdbcTemplate
            .query("""
                SELECT a.id, %s AS business_type, a.business_key, a.title, a.applicant_name, a.applicant_dept_name, a.status,
                       a.submitted_time, a.finished_time, %s AS amount
                  FROM oa_application a
                %s%s
                 WHERE a.tenant_id = ? AND a.deleted = 0 AND a.status <> 'DRAFT'
                   AND a.submitted_time >= ? AND a.submitted_time < ? %s
                 ORDER BY a.id LIMIT ?
                """
                .formatted(BIZ_TYPE, AMOUNT, AMOUNT_JOINS, TYPE_JOINS, filter), (rs,
                                                                                 rowNum) -> new ApplicationExportRow(rs
                                                                                     .getLong("id"), rs
                                                                                         .getString("business_type"), rs
                                                                                             .getString("business_key"), rs
                                                                                                 .getString("title"), rs
                                                                                                     .getString("applicant_name"), rs
                                                                                                         .getString("applicant_dept_name"), rs
                                                                                                             .getString("status"), timestamp(rs, "submitted_time"), timestamp(rs, "finished_time"), decimal(rs, "amount")), args
                                                                                                                 .toArray());
    }

    /**
     * 用车记录导出：按用车开始时间落在区间内。司机电话为明文列，由调用方脱敏后写入文件。
     */
    public List<VehicleExportRow> exportVehicleRequests(long tenantId,
                                                        LocalDateTime from,
                                                        LocalDateTime to,
                                                        int limit) {
        return jdbcTemplate.query("""
            SELECT r.id, u.nickname AS applicant_name, r.purpose, r.destination, r.passengers, r.start_time, r.end_time,
                   r.status, v.plate_no, d.name AS driver_name, d.phone AS driver_phone, r.start_mileage, r.end_mileage,
                   r.fuel_cost, r.toll_cost, r.other_cost
              FROM oa_vehicle_request r
              LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.applicant_id
              LEFT JOIN oa_vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id
              LEFT JOIN oa_vehicle_driver d ON d.tenant_id = r.tenant_id AND d.id = r.driver_id
             WHERE r.tenant_id = ? AND r.start_time >= ? AND r.start_time < ?
             ORDER BY r.start_time, r.id LIMIT ?
            """, (rs, rowNum) -> new VehicleExportRow(rs.getLong("id"), rs.getString("applicant_name"), rs
            .getString("purpose"), rs.getString("destination"), rs
                .getInt("passengers"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                    .getString("status"), rs.getString("plate_no"), rs.getString("driver_name"), rs
                        .getString("driver_phone"), nullableInt(rs, "start_mileage"), nullableInt(rs, "end_mileage"), decimal(rs, "fuel_cost"), decimal(rs, "toll_cost"), decimal(rs, "other_cost")), tenantId, from, to, limit);
    }

    public boolean insertExport(long tenantId,
                                long userId,
                                String requestId,
                                String exportType,
                                LocalDate from,
                                LocalDate to,
                                String businessType,
                                LocalDateTime now) {
        try {
            return jdbcTemplate.update("""
                INSERT INTO oa_export_task
                  (tenant_id, user_id, request_id, export_type, date_from, date_to, business_type, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, tenantId, userId, requestId, exportType, Date.valueOf(from), Date
                .valueOf(to), businessType, now) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public OaExportTaskResp exportByRequest(long tenantId, long userId, String requestId) {
        List<OaExportTaskResp> rows = jdbcTemplate.query("""
            SELECT * FROM oa_export_task WHERE tenant_id = ? AND user_id = ? AND request_id = ?
            """, EXPORT_MAPPER, tenantId, userId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long countActiveExports(long tenantId, long userId) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM oa_export_task WHERE tenant_id = ? AND user_id = ? AND status IN ('PENDING', 'RUNNING')
            """, Long.class, tenantId, userId);
        return count == null ? 0 : count;
    }

    public List<OaExportTaskResp> myExports(long tenantId, long userId, int limit) {
        return jdbcTemplate.query("""
            SELECT * FROM oa_export_task WHERE tenant_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?
            """, EXPORT_MAPPER, tenantId, userId, limit);
    }

    public ExportTask exportTask(long id) {
        List<ExportTask> rows = jdbcTemplate.query("""
            SELECT id, tenant_id, user_id, export_type, date_from, date_to, business_type, status, file_name, file_path,
                   expire_time
              FROM oa_export_task WHERE id = ?
            """, (rs, rowNum) -> new ExportTask(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("user_id"), rs
            .getString("export_type"), date(rs, "date_from"), date(rs, "date_to"), rs.getString("business_type"), rs
                .getString("status"), rs.getString("file_name"), rs
                    .getString("file_path"), timestamp(rs, "expire_time")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Long> pendingExportIds(int limit) {
        return jdbcTemplate.queryForList("""
            SELECT id FROM oa_export_task WHERE status = 'PENDING' ORDER BY id LIMIT ?
            """, Long.class, limit);
    }

    /**
     * 抢占待处理任务；多副本同时扫描时只有一个能把 PENDING 改为 RUNNING。
     */
    public boolean claimExport(long id, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_export_task SET status = 'RUNNING', start_time = ? WHERE id = ? AND status = 'PENDING'
            """, now, id) == 1;
    }

    public void finishExport(long id,
                             String fileName,
                             String filePath,
                             int rowCount,
                             LocalDateTime now,
                             LocalDateTime expireTime) {
        jdbcTemplate.update("""
            UPDATE oa_export_task
               SET status = 'DONE', file_name = ?, file_path = ?, row_count = ?, finish_time = ?, expire_time = ?
             WHERE id = ? AND status = 'RUNNING'
            """, fileName, filePath, rowCount, now, expireTime, id);
    }

    public void failExport(long id, String error, LocalDateTime now) {
        jdbcTemplate.update("""
            UPDATE oa_export_task SET status = 'FAILED', error_message = ?, finish_time = ?
             WHERE id = ? AND status = 'RUNNING'
            """, error, now, id);
    }

    public int failStaleRunning(LocalDateTime startedBefore, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_export_task SET status = 'FAILED', error_message = '生成超时，请重新导出', finish_time = ?
             WHERE status = 'RUNNING' AND start_time < ?
            """, now, startedBefore);
    }

    public List<ExportTask> expiredExports(LocalDateTime now, int limit) {
        return jdbcTemplate.query("""
            SELECT id, tenant_id, user_id, export_type, date_from, date_to, business_type, status, file_name, file_path,
                   expire_time
              FROM oa_export_task WHERE status = 'DONE' AND expire_time <= ? ORDER BY id LIMIT ?
            """, (rs, rowNum) -> new ExportTask(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("user_id"), rs
            .getString("export_type"), date(rs, "date_from"), date(rs, "date_to"), rs.getString("business_type"), rs
                .getString("status"), rs.getString("file_name"), rs
                    .getString("file_path"), timestamp(rs, "expire_time")), now, limit);
    }

    public boolean markExpired(long id) {
        return jdbcTemplate.update("""
            UPDATE oa_export_task SET status = 'EXPIRED', file_path = NULL WHERE id = ? AND status = 'DONE'
            """, id) == 1;
    }

    private static String typeFilter(String businessType, List<Object> args) {
        if (businessType == null || businessType.isBlank()) {
            return "";
        }
        args.add(businessType);
        return " AND " + BIZ_TYPE + " = ?";
    }

    private static String placeholders(int size) {
        return String.join(", ", Collections.nCopies(size, "?"));
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    public record TypeStatusRow(String businessType, String status, long count, BigDecimal amount, Double avgSeconds,
                                long finished) {
    }

    public record DeptRow(String deptName, long count, long approved, BigDecimal approvedAmount) {
    }

    public record ApplicationBrief(long id, String businessType, String title, String applicantName) {
    }

    public record ApplicationExportRow(long id, String businessType, String businessKey, String title,
                                       String applicantName, String deptName, String status,
                                       LocalDateTime submittedTime, LocalDateTime finishedTime, BigDecimal amount) {
    }

    public record VehicleExportRow(long id, String applicantName, String purpose, String destination, int passengers,
                                   LocalDateTime startTime, LocalDateTime endTime, String status, String plateNo,
                                   String driverName, String driverPhone, Integer startMileage, Integer endMileage,
                                   BigDecimal fuelCost, BigDecimal tollCost, BigDecimal otherCost) {
    }

    public record ExportTask(long id, long tenantId, long userId, String exportType, LocalDate from, LocalDate to,
                             String businessType, String status, String fileName, String filePath,
                             LocalDateTime expireTime) {
    }
}
