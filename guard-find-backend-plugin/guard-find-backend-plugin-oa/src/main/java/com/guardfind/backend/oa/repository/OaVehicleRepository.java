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

import com.guardfind.backend.oa.model.OaDriverResp;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceResp;
import com.guardfind.backend.oa.model.OaVehicleRequestResp;
import com.guardfind.backend.oa.model.OaVehicleResp;
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
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.guardfind.backend.oa.repository.OaPurchaseRepository.nullableLong;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.timestamp;

/**
 * 车辆、司机、维保与用车申请的显式租户 SQL。
 * 占用判定：已调度且未过结束时间、出车中（实际占用到归还为止）；维保登记的区间（含进行中超期部分）同样不可调度。
 */
@Repository
public class OaVehicleRepository {

    private static final String VEHICLE_SELECT = """
        SELECT id, plate_no, model, seats, mileage, status, remark, version,
               COALESCE(update_time, create_time) AS update_time
          FROM oa_vehicle
         WHERE tenant_id = ?
        """;

    private static final RowMapper<OaVehicleResp> VEHICLE_MAPPER = (rs, rowNum) -> new OaVehicleResp(rs
        .getLong("id"), rs.getString("plate_no"), rs.getString("model"), rs.getInt("seats"), rs.getInt("mileage"), rs
            .getString("status"), rs.getString("remark"), rs.getInt("version"), timestamp(rs, "update_time"));

    private static final String DRIVER_SELECT = """
        SELECT d.id, d.name, d.phone, d.user_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS user_name, d.status,
               d.remark, d.version, COALESCE(d.update_time, d.create_time) AS update_time
          FROM oa_vehicle_driver d
          LEFT JOIN sys_user u ON u.tenant_id = d.tenant_id AND u.id = d.user_id
         WHERE d.tenant_id = ?
        """;

    private static final RowMapper<OaDriverResp> DRIVER_MAPPER = (rs, rowNum) -> new OaDriverResp(rs.getLong("id"), rs
        .getString("name"), rs.getString("phone"), nullableLong(rs, "user_id"), rs.getString("user_name"), rs
            .getString("status"), rs.getString("remark"), rs.getInt("version"), timestamp(rs, "update_time"));

    private static final String MAINTENANCE_SELECT = """
        SELECT m.id, m.vehicle_id, v.plate_no, m.maint_type, m.start_time, m.end_time, m.status, m.cost, m.remark,
               m.create_user, COALESCE(NULLIF(u.nickname, ''), u.username) AS create_user_name, m.create_time,
               m.complete_time
          FROM oa_vehicle_maintenance m
          JOIN oa_vehicle v ON v.tenant_id = m.tenant_id AND v.id = m.vehicle_id
          LEFT JOIN sys_user u ON u.tenant_id = m.tenant_id AND u.id = m.create_user
         WHERE m.tenant_id = ?
        """;

    private static final RowMapper<OaVehicleMaintenanceResp> MAINTENANCE_MAPPER = (rs,
                                                                                   rowNum) -> new OaVehicleMaintenanceResp(rs
                                                                                       .getLong("id"), rs
                                                                                           .getLong("vehicle_id"), rs
                                                                                               .getString("plate_no"), rs
                                                                                                   .getString("maint_type"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                                                                                                       .getString("status"), rs
                                                                                                           .getBigDecimal("cost"), rs
                                                                                                               .getString("remark"), rs
                                                                                                                   .getLong("create_user"), rs
                                                                                                                       .getString("create_user_name"), timestamp(rs, "create_time"), timestamp(rs, "complete_time"));

    private static final String REQUEST_FROM = """
          FROM oa_vehicle_request r
          JOIN oa_application a ON a.tenant_id = r.tenant_id AND a.id = r.application_id
          LEFT JOIN sys_user u ON u.tenant_id = r.tenant_id AND u.id = r.applicant_id
          LEFT JOIN oa_vehicle v ON v.tenant_id = r.tenant_id AND v.id = r.vehicle_id
          LEFT JOIN oa_vehicle_driver d ON d.tenant_id = r.tenant_id AND d.id = r.driver_id
          LEFT JOIN sys_user db ON db.tenant_id = r.tenant_id AND db.id = r.dispatch_by
         WHERE r.tenant_id = ?
        """;

    private static final String REQUEST_SELECT = """
        SELECT r.id, r.application_id, a.title, a.status AS application_status, r.applicant_id,
               COALESCE(NULLIF(u.nickname, ''), u.username) AS applicant_name, r.purpose, r.destination, r.passengers,
               r.need_driver, r.start_time, r.end_time, r.status, r.vehicle_id, v.plate_no, v.model AS vehicle_model,
               r.driver_id, d.name AS driver_name, d.phone AS driver_phone,
               COALESCE(NULLIF(db.nickname, ''), db.username) AS dispatch_by_name, r.dispatch_time, r.dispatch_remark,
               r.depart_time, r.start_mileage, r.return_time, r.end_mileage, r.fuel_cost, r.toll_cost, r.other_cost,
               r.return_remark, r.cancel_reason, r.cancel_time, r.create_time
        """ + REQUEST_FROM;

    private static final RowMapper<OaVehicleRequestResp> REQUEST_MAPPER = (rs, rowNum) -> new OaVehicleRequestResp(rs
        .getLong("id"), rs.getLong("application_id"), rs.getString("title"), rs.getString("application_status"), rs
            .getLong("applicant_id"), rs.getString("applicant_name"), rs.getString("purpose"), rs
                .getString("destination"), rs.getInt("passengers"), rs
                    .getBoolean("need_driver"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                        .getString("status"), nullableLong(rs, "vehicle_id"), rs.getString("plate_no"), rs
                            .getString("vehicle_model"), nullableLong(rs, "driver_id"), rs.getString("driver_name"), rs
                                .getString("driver_phone"), rs
                                    .getString("dispatch_by_name"), timestamp(rs, "dispatch_time"), rs
                                        .getString("dispatch_remark"), timestamp(rs, "depart_time"), nullableInt(rs, "start_mileage"), timestamp(rs, "return_time"), nullableInt(rs, "end_mileage"), rs
                                            .getBigDecimal("fuel_cost"), rs.getBigDecimal("toll_cost"), rs
                                                .getBigDecimal("other_cost"), rs.getString("return_remark"), rs
                                                    .getString("cancel_reason"), timestamp(rs, "cancel_time"), timestamp(rs, "create_time"));

    private static final String REQUEST_ROW_SELECT = """
        SELECT id, application_id, applicant_id, passengers, need_driver, start_time, end_time, status, vehicle_id,
               driver_id, dispatch_request_id, depart_request_id, return_request_id, start_mileage
          FROM oa_vehicle_request
        """;

    private static final RowMapper<RequestRow> REQUEST_ROW_MAPPER = (rs, rowNum) -> new RequestRow(rs.getLong("id"), rs
        .getLong("application_id"), rs.getLong("applicant_id"), rs.getInt("passengers"), rs
            .getBoolean("need_driver"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                .getString("status"), nullableLong(rs, "vehicle_id"), nullableLong(rs, "driver_id"), rs
                    .getString("dispatch_request_id"), rs.getString("depart_request_id"), rs
                        .getString("return_request_id"), nullableInt(rs, "start_mileage"));

    /** 行程占用：参数依次为 now, end, start, end, start, now, start。 */
    private static final String TRIP_OCCUPIES = """
        ((t.status = 'DISPATCHED' AND t.end_time > ? AND t.start_time < ? AND t.end_time > ?)
          OR (t.status = 'IN_USE' AND t.start_time < ? AND (t.end_time > ? OR ? > ?)))
        """;

    private final JdbcTemplate jdbcTemplate;

    public OaVehicleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaVehicleResp> vehicles(long tenantId, String status) {
        if (status == null) {
            return jdbcTemplate.query(VEHICLE_SELECT + " ORDER BY plate_no", VEHICLE_MAPPER, tenantId);
        }
        return jdbcTemplate
            .query(VEHICLE_SELECT + " AND status = ? ORDER BY plate_no", VEHICLE_MAPPER, tenantId, status);
    }

    public OaVehicleResp vehicle(long tenantId, long id) {
        List<OaVehicleResp> rows = jdbcTemplate.query(VEHICLE_SELECT + " AND id = ?", VEHICLE_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertVehicle(long tenantId,
                              String plateNo,
                              String model,
                              int seats,
                              int mileage,
                              String status,
                              String remark,
                              long userId,
                              LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_vehicle (tenant_id, plate_no, model, seats, mileage, status, remark, version,
                                        create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, plateNo);
            ps.setString(3, model);
            ps.setInt(4, seats);
            ps.setInt(5, mileage);
            ps.setString(6, status);
            ps.setString(7, remark);
            ps.setLong(8, userId);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    /**
     * 里程只能调大，避免把归还登记写入的里程改回去。
     */
    public int updateVehicle(long tenantId,
                             long id,
                             int version,
                             String plateNo,
                             String model,
                             int seats,
                             Integer mileage,
                             String status,
                             String remark,
                             long userId,
                             LocalDateTime now) {
        return jdbcTemplate
            .update("""
                UPDATE oa_vehicle
                   SET plate_no = ?, model = ?, seats = ?, mileage = CASE WHEN ? IS NOT NULL AND ? > mileage THEN ? ELSE mileage END,
                       status = ?, remark = ?, version = version + 1, update_user = ?, update_time = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, plateNo, model, seats, mileage, mileage, mileage, status, remark, userId, now, tenantId, id, version);
    }

    public VehicleRow lockVehicle(long tenantId, long id) {
        List<VehicleRow> rows = jdbcTemplate.query("""
            SELECT id, plate_no, seats, mileage, status FROM oa_vehicle WHERE tenant_id = ? AND id = ? FOR UPDATE
            """, (rs, rowNum) -> new VehicleRow(rs.getLong("id"), rs.getString("plate_no"), rs.getInt("seats"), rs
            .getInt("mileage"), rs.getString("status")), tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int raiseMileage(long tenantId, long id, int mileage, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle SET mileage = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND mileage <= ?
            """, mileage, now, tenantId, id, mileage);
    }

    public List<OaDriverResp> drivers(long tenantId, String status) {
        if (status == null) {
            return jdbcTemplate.query(DRIVER_SELECT + " ORDER BY d.id", DRIVER_MAPPER, tenantId);
        }
        return jdbcTemplate.query(DRIVER_SELECT + " AND d.status = ? ORDER BY d.id", DRIVER_MAPPER, tenantId, status);
    }

    public OaDriverResp driver(long tenantId, long id) {
        List<OaDriverResp> rows = jdbcTemplate.query(DRIVER_SELECT + " AND d.id = ?", DRIVER_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertDriver(long tenantId,
                             String name,
                             String phone,
                             Long userId,
                             String status,
                             String remark,
                             long operatorId,
                             LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_vehicle_driver (tenant_id, name, phone, user_id, status, remark, version, create_user,
                                               create_time)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, name);
            ps.setString(3, phone);
            if (userId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, userId);
            }
            ps.setString(5, status);
            ps.setString(6, remark);
            ps.setLong(7, operatorId);
            ps.setObject(8, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateDriver(long tenantId,
                            long id,
                            int version,
                            String name,
                            String phone,
                            Long userId,
                            String status,
                            String remark,
                            long operatorId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_driver
               SET name = ?, phone = ?, user_id = ?, status = ?, remark = ?, version = version + 1, update_user = ?,
                   update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, name, phone, userId, status, remark, operatorId, now, tenantId, id, version);
    }

    public DriverRow lockDriver(long tenantId, long id) {
        List<DriverRow> rows = jdbcTemplate.query("""
            SELECT id, name, user_id, status FROM oa_vehicle_driver WHERE tenant_id = ? AND id = ? FOR UPDATE
            """, (rs, rowNum) -> new DriverRow(rs.getLong("id"), rs.getString("name"), nullableLong(rs, "user_id"), rs
            .getString("status")), tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 调用前必须已锁住车辆（查司机冲突时还须锁住司机），且锁要在事务内首次普通读之前取得：
     * 快照随之建立在锁等待之后，普通读即可看到并发写者的提交；不用 FOR UPDATE，避免区间扫描的间隙锁跨车辆死锁。
     * excludeRequestId 为正在调度的申请本身。
     */
    public long countVehicleTrips(long tenantId,
                                  long vehicleId,
                                  long excludeRequestId,
                                  LocalDateTime start,
                                  LocalDateTime end,
                                  LocalDateTime now) {
        return jdbcTemplate
            .queryForList("""
                SELECT t.id FROM oa_vehicle_request t
                 WHERE t.tenant_id = ? AND t.vehicle_id = ? AND t.id <> ? AND
                """ + TRIP_OCCUPIES, Long.class, tenantId, vehicleId, excludeRequestId, now, end, start, end, start, now, start)
            .size();
    }

    public long countDriverTrips(long tenantId,
                                 long driverId,
                                 long excludeRequestId,
                                 LocalDateTime start,
                                 LocalDateTime end,
                                 LocalDateTime now) {
        return jdbcTemplate
            .queryForList("""
                SELECT t.id FROM oa_vehicle_request t
                 WHERE t.tenant_id = ? AND t.driver_id = ? AND t.id <> ? AND
                """ + TRIP_OCCUPIES, Long.class, tenantId, driverId, excludeRequestId, now, end, start, end, start, now, start)
            .size();
    }

    public long countMaintenances(long tenantId,
                                  long vehicleId,
                                  LocalDateTime start,
                                  LocalDateTime end,
                                  LocalDateTime now) {
        return jdbcTemplate.queryForList("""
            SELECT m.id FROM oa_vehicle_maintenance m
             WHERE m.tenant_id = ? AND m.vehicle_id = ? AND m.start_time < ? AND m.end_time > m.start_time
               AND (m.end_time > ? OR (m.status = 'ACTIVE' AND ? > ?))
            """, Long.class, tenantId, vehicleId, end, start, now, start).size();
    }

    public long countInUse(long tenantId, long vehicleId, long excludeRequestId) {
        return jdbcTemplate.queryForList("""
            SELECT id FROM oa_vehicle_request
             WHERE tenant_id = ? AND vehicle_id = ? AND id <> ? AND status = 'IN_USE'
            """, Long.class, tenantId, vehicleId, excludeRequestId).size();
    }

    public long insertMaintenance(long tenantId,
                                  long vehicleId,
                                  String requestId,
                                  String maintType,
                                  LocalDateTime start,
                                  LocalDateTime end,
                                  String remark,
                                  long userId,
                                  LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_vehicle_maintenance (tenant_id, vehicle_id, request_id, maint_type, start_time, end_time,
                                                    status, remark, create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, vehicleId);
            ps.setString(3, requestId);
            ps.setString(4, maintType);
            ps.setObject(5, start);
            ps.setObject(6, end);
            ps.setString(7, remark);
            ps.setLong(8, userId);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public MaintenanceRow maintenanceByRequest(long tenantId, String requestId) {
        List<MaintenanceRow> rows = jdbcTemplate.query("""
            SELECT id, vehicle_id, start_time, end_time, status FROM oa_vehicle_maintenance
             WHERE tenant_id = ? AND request_id = ?
            """, MaintenanceRow.MAPPER, tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MaintenanceRow lockMaintenance(long tenantId, long id) {
        List<MaintenanceRow> rows = jdbcTemplate.query("""
            SELECT id, vehicle_id, start_time, end_time, status FROM oa_vehicle_maintenance
             WHERE tenant_id = ? AND id = ? FOR UPDATE
            """, MaintenanceRow.MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int completeMaintenance(long tenantId,
                                   long id,
                                   LocalDateTime endTime,
                                   BigDecimal cost,
                                   String remark,
                                   long userId,
                                   LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_maintenance
               SET status = 'DONE', end_time = ?, cost = ?, remark = COALESCE(?, remark), complete_user = ?,
                   complete_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
            """, endTime, cost, remark, userId, now, tenantId, id);
    }

    public OaVehicleMaintenanceResp maintenance(long tenantId, long id) {
        List<OaVehicleMaintenanceResp> rows = jdbcTemplate
            .query(MAINTENANCE_SELECT + " AND m.id = ?", MAINTENANCE_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaVehicleMaintenanceResp> maintenances(long tenantId, Long vehicleId, String status, int limit) {
        StringBuilder sql = new StringBuilder(MAINTENANCE_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (vehicleId != null) {
            sql.append(" AND m.vehicle_id = ?");
            args.add(vehicleId);
        }
        if (status != null) {
            sql.append(" AND m.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY m.start_time DESC, m.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), MAINTENANCE_MAPPER, args.toArray());
    }

    public void insertRequest(long tenantId,
                              long applicationId,
                              long applicantId,
                              RequestValues values,
                              LocalDateTime now) {
        jdbcTemplate.update("""
            INSERT INTO oa_vehicle_request (tenant_id, application_id, applicant_id, purpose, destination, passengers,
                                            need_driver, start_time, end_time, status, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVING', ?)
            """, tenantId, applicationId, applicantId, values.purpose(), values.destination(), values
            .passengers(), values.needDriver(), values.start(), values.end(), now);
    }

    public RequestRow lockRequest(long tenantId, long id) {
        List<RequestRow> rows = jdbcTemplate
            .query(REQUEST_ROW_SELECT + " WHERE tenant_id = ? AND id = ? FOR UPDATE", REQUEST_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RequestRow lockRequestByApplication(long tenantId, long applicationId) {
        List<RequestRow> rows = jdbcTemplate
            .query(REQUEST_ROW_SELECT + " WHERE tenant_id = ? AND application_id = ? FOR UPDATE", REQUEST_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long requestIdByCommand(long tenantId, String column, String requestId) {
        List<Long> rows = jdbcTemplate.queryForList("SELECT id FROM oa_vehicle_request WHERE tenant_id = ? AND %s = ?"
            .formatted(column), Long.class, tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionRequest(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_request SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int markDispatched(long tenantId,
                              long id,
                              String requestId,
                              long vehicleId,
                              Long driverId,
                              String remark,
                              long operatorId,
                              LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_request
               SET status = 'DISPATCHED', vehicle_id = ?, driver_id = ?, dispatch_request_id = ?, dispatch_by = ?,
                   dispatch_time = ?, dispatch_remark = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'PENDING_DISPATCH'
            """, vehicleId, driverId, requestId, operatorId, now, remark, now, tenantId, id);
    }

    public int markDeparted(long tenantId,
                            long id,
                            String requestId,
                            int startMileage,
                            long operatorId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_request
               SET status = 'IN_USE', depart_request_id = ?, depart_time = ?, start_mileage = ?, exec_by = ?,
                   update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'DISPATCHED'
            """, requestId, now, startMileage, operatorId, now, tenantId, id);
    }

    public int markReturned(long tenantId,
                            long id,
                            String requestId,
                            int endMileage,
                            BigDecimal fuelCost,
                            BigDecimal tollCost,
                            BigDecimal otherCost,
                            String remark,
                            long operatorId,
                            LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_request
               SET status = 'RETURNED', return_request_id = ?, return_time = ?, end_mileage = ?, fuel_cost = ?,
                   toll_cost = ?, other_cost = ?, return_remark = ?, exec_by = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = 'IN_USE'
            """, requestId, now, endMileage, fuelCost, tollCost, otherCost, remark, operatorId, now, tenantId, id);
    }

    public int cancelRequest(long tenantId, long id, String from, long operatorId, String reason, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_vehicle_request
               SET status = 'CANCELLED', cancel_by = ?, cancel_time = ?, cancel_reason = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = ?
            """, operatorId, now, reason, now, tenantId, id, from);
    }

    public OaVehicleRequestResp request(long tenantId, long id) {
        List<OaVehicleRequestResp> rows = jdbcTemplate
            .query(REQUEST_SELECT + " AND r.id = ?", REQUEST_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaVehicleRequestResp> requests(long tenantId, RequestFilter filter, int offset, int limit) {
        StringBuilder sql = new StringBuilder(REQUEST_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, filter);
        sql.append(" ORDER BY r.start_time DESC, r.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), REQUEST_MAPPER, args.toArray());
    }

    public long countRequests(long tenantId, RequestFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + REQUEST_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, filter);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public List<ExpiringRequest> expiredDispatches(LocalDateTime now, int limit) {
        return jdbcTemplate.query("""
            SELECT id, tenant_id, application_id, applicant_id, status, destination
              FROM oa_vehicle_request
             WHERE status IN ('PENDING_DISPATCH', 'DISPATCHED') AND end_time <= ?
             ORDER BY end_time, id
             LIMIT ?
            """, (rs, rowNum) -> new ExpiringRequest(rs.getLong("id"), rs.getLong("tenant_id"), rs
            .getLong("application_id"), rs.getLong("applicant_id"), rs.getString("status"), rs
                .getString("destination")), now, limit);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, RequestFilter filter) {
        if (filter.status() != null) {
            sql.append(" AND r.status = ?");
            args.add(filter.status());
        }
        if (filter.applicantId() != null) {
            sql.append(" AND r.applicant_id = ?");
            args.add(filter.applicantId());
        }
        if (filter.vehicleId() != null) {
            sql.append(" AND r.vehicle_id = ?");
            args.add(filter.vehicleId());
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

    public record VehicleRow(long id, String plateNo, int seats, int mileage, String status) {
    }

    public record DriverRow(long id, String name, Long userId, String status) {
    }

    public record MaintenanceRow(long id, long vehicleId, LocalDateTime start, LocalDateTime end, String status) {

        static final RowMapper<MaintenanceRow> MAPPER = (rs, rowNum) -> new MaintenanceRow(rs.getLong("id"), rs
            .getLong("vehicle_id"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs.getString("status"));
    }

    public record RequestValues(String purpose, String destination, int passengers, boolean needDriver,
                                LocalDateTime start, LocalDateTime end) {
    }

    public record RequestRow(long id, long applicationId, long applicantId, int passengers, boolean needDriver,
                             LocalDateTime start, LocalDateTime end, String status, Long vehicleId, Long driverId,
                             String dispatchRequestId, String departRequestId, String returnRequestId,
                             Integer startMileage) {
    }

    public record RequestFilter(String status, Long applicantId, Long vehicleId) {
    }

    public record ExpiringRequest(long id, long tenantId, long applicationId, long applicantId, String status,
                                  String destination) {
    }
}
