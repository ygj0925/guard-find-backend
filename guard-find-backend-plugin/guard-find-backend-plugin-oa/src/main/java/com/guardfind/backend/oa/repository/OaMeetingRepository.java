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

import com.guardfind.backend.oa.model.OaMeetingBookingResp;
import com.guardfind.backend.oa.model.OaMeetingRoomResp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.guardfind.backend.oa.repository.OaPurchaseRepository.nullableLong;
import static com.guardfind.backend.oa.repository.OaPurchaseRepository.timestamp;

/**
 * 会议室与预订的显式租户 SQL。占用判定：已预订，或审批中且尚未到开始时间（过时的审批中预订不再占用）。
 */
@Repository
public class OaMeetingRepository {

    public static final String OCCUPYING = "(b.status = 'BOOKED' OR (b.status = 'APPROVING' AND b.start_time > ?))";

    private static final String ROOM_SELECT = """
        SELECT id, code, name, location, capacity, need_approval, status, remark, version,
               COALESCE(update_time, create_time) AS update_time
          FROM oa_meeting_room
         WHERE tenant_id = ?
        """;

    private static final RowMapper<OaMeetingRoomResp> ROOM_MAPPER = (rs, rowNum) -> new OaMeetingRoomResp(rs
        .getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("location"), rs.getInt("capacity"), rs
            .getBoolean("need_approval"), rs.getString("status"), rs.getString("remark"), rs
                .getInt("version"), timestamp(rs, "update_time"));

    private static final String BOOKING_FROM = """
          FROM oa_meeting_booking b
          JOIN oa_meeting_room r ON r.tenant_id = b.tenant_id AND r.id = b.room_id
          LEFT JOIN oa_application a ON a.tenant_id = b.tenant_id AND a.id = b.application_id
          LEFT JOIN sys_user u ON u.tenant_id = b.tenant_id AND u.id = b.booker_id
          LEFT JOIN sys_user c ON c.tenant_id = b.tenant_id AND c.id = b.cancel_by
         WHERE b.tenant_id = ?
        """;

    private static final String BOOKING_SELECT = """
        SELECT b.id, b.room_id, r.code AS room_code, r.name AS room_name, r.location, b.application_id,
               a.status AS application_status, b.booker_id, COALESCE(NULLIF(u.nickname, ''), u.username) AS booker_name,
               b.subject, b.start_time, b.end_time, b.attendees, b.status, b.cancel_by,
               COALESCE(NULLIF(c.nickname, ''), c.username) AS cancel_by_name, b.cancel_time, b.cancel_reason,
               b.create_time
        """ + BOOKING_FROM;

    private static final RowMapper<OaMeetingBookingResp> BOOKING_MAPPER = (rs, rowNum) -> new OaMeetingBookingResp(rs
        .getLong("id"), rs.getLong("room_id"), rs.getString("room_code"), rs.getString("room_name"), rs
            .getString("location"), nullableLong(rs, "application_id"), rs.getString("application_status"), rs
                .getLong("booker_id"), rs.getString("booker_name"), rs
                    .getString("subject"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs
                        .getInt("attendees"), rs.getString("status"), nullableLong(rs, "cancel_by"), rs
                            .getString("cancel_by_name"), timestamp(rs, "cancel_time"), rs
                                .getString("cancel_reason"), timestamp(rs, "create_time"));

    private static final RowMapper<BookingRow> BOOKING_ROW_MAPPER = (rs, rowNum) -> new BookingRow(rs.getLong("id"), rs
        .getLong("room_id"), nullableLong(rs, "application_id"), rs.getString("request_id"), rs.getLong("booker_id"), rs
            .getString("subject"), timestamp(rs, "start_time"), timestamp(rs, "end_time"), rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public OaMeetingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OaMeetingRoomResp> rooms(long tenantId, String status) {
        if (status == null) {
            return jdbcTemplate.query(ROOM_SELECT + " ORDER BY code", ROOM_MAPPER, tenantId);
        }
        return jdbcTemplate.query(ROOM_SELECT + " AND status = ? ORDER BY code", ROOM_MAPPER, tenantId, status);
    }

    public OaMeetingRoomResp room(long tenantId, long id) {
        List<OaMeetingRoomResp> rows = jdbcTemplate.query(ROOM_SELECT + " AND id = ?", ROOM_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertRoom(long tenantId, RoomValues values, long userId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_meeting_room (tenant_id, code, name, location, capacity, need_approval, status, remark,
                                             version, create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, values.code());
            ps.setString(3, values.name());
            ps.setString(4, values.location());
            ps.setInt(5, values.capacity());
            ps.setBoolean(6, values.needApproval());
            ps.setString(7, values.status());
            ps.setString(8, values.remark());
            ps.setLong(9, userId);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public int updateRoom(long tenantId, long id, int version, RoomValues values, long userId, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_meeting_room
               SET code = ?, name = ?, location = ?, capacity = ?, need_approval = ?, status = ?, remark = ?,
                   version = version + 1, update_user = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
            """, values.code(), values.name(), values.location(), values.capacity(), values.needApproval(), values
            .status(), values.remark(), userId, now, tenantId, id, version);
    }

    public RoomRow lockRoom(long tenantId, long id) {
        return lockRoom("id = ?", tenantId, id);
    }

    public RoomRow lockRoomByCode(long tenantId, String code) {
        return lockRoom("code = ?", tenantId, code);
    }

    private RoomRow lockRoom(String condition, long tenantId, Object key) {
        List<RoomRow> rows = jdbcTemplate.query("""
            SELECT id, code, name, capacity, need_approval, status FROM oa_meeting_room
             WHERE tenant_id = ? AND %s
             FOR UPDATE
            """.formatted(condition), (rs, rowNum) -> new RoomRow(rs.getLong("id"), rs.getString("code"), rs
            .getString("name"), rs.getInt("capacity"), rs.getBoolean("need_approval"), rs
                .getString("status")), tenantId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 必须在锁住会议室后调用：统计与 [start, end) 相交的占用预订。
     * 用加锁读取，避免可重复读下事务快照早于会议室锁而漏看刚提交的预订。
     */
    public long countOverlaps(long tenantId, long roomId, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return jdbcTemplate.queryForList("""
            SELECT b.id FROM oa_meeting_booking b
             WHERE b.tenant_id = ? AND b.room_id = ? AND b.start_time < ? AND b.end_time > ? AND
            """ + OCCUPYING + " FOR UPDATE", Long.class, tenantId, roomId, end, start, now).size();
    }

    public long insertBooking(long tenantId, BookingValues values, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO oa_meeting_booking (tenant_id, room_id, application_id, request_id, booker_id, subject,
                                                start_time, end_time, attendees, status, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setLong(2, values.roomId());
            if (values.applicationId() == null) {
                ps.setNull(3, Types.BIGINT);
            } else {
                ps.setLong(3, values.applicationId());
            }
            ps.setString(4, values.requestId());
            ps.setLong(5, values.bookerId());
            ps.setString(6, values.subject());
            ps.setObject(7, values.start());
            ps.setObject(8, values.end());
            ps.setInt(9, values.attendees());
            ps.setString(10, values.status());
            ps.setObject(11, now);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    public BookingRow lockBooking(long tenantId, long id) {
        List<BookingRow> rows = jdbcTemplate.query("""
            SELECT id, room_id, application_id, request_id, booker_id, subject, start_time, end_time, status
              FROM oa_meeting_booking WHERE tenant_id = ? AND id = ? FOR UPDATE
            """, BOOKING_ROW_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BookingRow lockBookingByApplication(long tenantId, long applicationId) {
        List<BookingRow> rows = jdbcTemplate.query("""
            SELECT id, room_id, application_id, request_id, booker_id, subject, start_time, end_time, status
              FROM oa_meeting_booking WHERE tenant_id = ? AND application_id = ? FOR UPDATE
            """, BOOKING_ROW_MAPPER, tenantId, applicationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BookingRow bookingByRequest(long tenantId, String requestId) {
        List<BookingRow> rows = jdbcTemplate.query("""
            SELECT id, room_id, application_id, request_id, booker_id, subject, start_time, end_time, status
              FROM oa_meeting_booking WHERE tenant_id = ? AND request_id = ?
            """, BOOKING_ROW_MAPPER, tenantId, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int transitionBooking(long tenantId, long id, String from, String to, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_meeting_booking SET status = ?, update_time = ? WHERE tenant_id = ? AND id = ? AND status = ?
            """, to, now, tenantId, id, from);
    }

    public int cancelBooking(long tenantId, long id, String from, long operatorId, String reason, LocalDateTime now) {
        return jdbcTemplate.update("""
            UPDATE oa_meeting_booking
               SET status = 'CANCELLED', cancel_by = ?, cancel_time = ?, cancel_reason = ?, update_time = ?
             WHERE tenant_id = ? AND id = ? AND status = ?
            """, operatorId, now, reason, now, tenantId, id, from);
    }

    public OaMeetingBookingResp booking(long tenantId, long id) {
        List<OaMeetingBookingResp> rows = jdbcTemplate
            .query(BOOKING_SELECT + " AND b.id = ?", BOOKING_MAPPER, tenantId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OaMeetingBookingResp> bookings(long tenantId, BookingFilter filter, int offset, int limit) {
        StringBuilder sql = new StringBuilder(BOOKING_SELECT);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, filter);
        sql.append(" ORDER BY b.start_time DESC, b.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), BOOKING_MAPPER, args.toArray());
    }

    public long countBookings(long tenantId, BookingFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) " + BOOKING_FROM);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        appendFilters(sql, args, filter);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    /**
     * 某时间窗内启用会议室上的占用预订，供预订日历展示。
     */
    public List<OaMeetingBookingResp> schedule(long tenantId, LocalDateTime from, LocalDateTime to, LocalDateTime now) {
        return jdbcTemplate.query(BOOKING_SELECT + """
             AND r.status = 'ENABLED' AND b.start_time < ? AND b.end_time > ? AND
            """ + OCCUPYING + " ORDER BY b.room_id, b.start_time", BOOKING_MAPPER, tenantId, to, from, now);
    }

    public List<ExpiringBooking> expiredApprovals(LocalDateTime now, int limit) {
        return jdbcTemplate.query("""
            SELECT b.id, b.tenant_id, b.application_id, b.booker_id, b.subject, r.name AS room_name
              FROM oa_meeting_booking b
              JOIN oa_meeting_room r ON r.tenant_id = b.tenant_id AND r.id = b.room_id
             WHERE b.status = 'APPROVING' AND b.start_time <= ?
             ORDER BY b.start_time, b.id
             LIMIT ?
            """, (rs, rowNum) -> new ExpiringBooking(rs.getLong("id"), rs
            .getLong("tenant_id"), nullableLong(rs, "application_id"), rs.getLong("booker_id"), rs
                .getString("subject"), rs.getString("room_name")), now, limit);
    }

    private static void appendFilters(StringBuilder sql, List<Object> args, BookingFilter filter) {
        if (filter.status() != null) {
            sql.append(" AND b.status = ?");
            args.add(filter.status());
        }
        if (filter.roomId() != null) {
            sql.append(" AND b.room_id = ?");
            args.add(filter.roomId());
        }
        if (filter.bookerId() != null) {
            sql.append(" AND b.booker_id = ?");
            args.add(filter.bookerId());
        }
        if (filter.from() != null) {
            sql.append(" AND b.end_time > ?");
            args.add(filter.from());
        }
        if (filter.to() != null) {
            sql.append(" AND b.start_time < ?");
            args.add(filter.to());
        }
    }

    private static long requireKey(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("数据库未返回自增主键");
        }
        return key.longValue();
    }

    public record RoomValues(String code, String name, String location, int capacity, boolean needApproval,
                             String status, String remark) {
    }

    public record RoomRow(long id, String code, String name, int capacity, boolean needApproval, String status) {
    }

    public record BookingValues(long roomId, Long applicationId, String requestId, long bookerId, String subject,
                                LocalDateTime start, LocalDateTime end, int attendees, String status) {
    }

    public record BookingRow(long id, long roomId, Long applicationId, String requestId, long bookerId, String subject,
                             LocalDateTime start, LocalDateTime end, String status) {
    }

    public record BookingFilter(String status, Long roomId, Long bookerId, LocalDateTime from, LocalDateTime to) {
    }

    public record ExpiringBooking(long id, long tenantId, Long applicationId, long bookerId, String subject,
                                  String roomName) {
    }
}
