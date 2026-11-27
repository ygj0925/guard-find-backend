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

package com.guardfind.backend.property;

import com.guardfind.backend.property.operations.*;
import com.guardfind.backend.common.config.TenantExtensionProperties;
import com.guardfind.backend.miniapp.mapper.MiniappNoticeMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappNoticeDO;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import top.continew.starter.extension.tenant.context.TenantContextHolder;
import cn.dev33.satoken.stp.StpUtil;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class PropertyOperationsTest {
    @BeforeAll
    static void utilities() {
        var context = mock(org.springframework.context.ApplicationContext.class);
        var factory = mock(com.feiniaojin.gracefulresponse.api.ResponseStatusFactory.class);
        when(factory.defaultError())
            .thenAnswer(i -> new com.feiniaojin.gracefulresponse.defaults.DefaultResponseStatus("500", "error"));
        when(context.getBean(com.feiniaojin.gracefulresponse.api.ResponseStatusFactory.class)).thenReturn(factory);
        new cn.hutool.extra.spring.SpringUtil().setApplicationContext(context);
    }

    private JdbcTemplate jdbc;
    private PropertyOperationsService operations;
    private com.guardfind.backend.system.service.RoleService roles;
    private PropertyBookingOperations bookings;
    private PropertyNotificationService notifications;
    private MiniappNoticeMapper notices;
    private MockedStatic<TenantContextHolder> tenants;
    private DataSourceTransactionManager tx;

    @BeforeEach
    void setup() throws Exception {
        tenants = mockStatic(TenantContextHolder.class);
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ops" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE gf_miniapp_user(id BIGINT PRIMARY KEY,nickname VARCHAR(100),avatar VARCHAR(300),openid VARCHAR(100),account_status INT DEFAULT 1,is_property_manager INT DEFAULT 0,deleted BIGINT DEFAULT 0,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE sys_user(id BIGINT PRIMARY KEY,nickname VARCHAR(100),status INT DEFAULT 1,deleted BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE gf_property(id BIGINT PRIMARY KEY,user_id BIGINT,title VARCHAR(100),price DECIMAL(12,2),price_type VARCHAR(10),images VARCHAR(500),address VARCHAR(100),status INT DEFAULT 2,deleted BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE gf_property_booking(id BIGINT PRIMARY KEY,property_id BIGINT,user_id BIGINT,owner_id BIGINT,booking_time TIMESTAMP,contact_name VARCHAR(100),contact_phone VARCHAR(30),remark VARCHAR(500),status INT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP)");
        String sql = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_operations_workflow.sql"));
        sql = sql.split("-- changeset guardfind:property-operations-workflow-menu-1")[0];
        for (String statement : sql.split(";"))
            if (!statement.isBlank())
                jdbc.execute(statement);
        var leaseSql = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_lease_workflow.sql"))
            .split("-- changeset guardfind:property-lease-menu-1")[0];
        for (String statement : leaseSql.split(";"))
            if (!statement.isBlank())
                jdbc.execute(statement);
        jdbc.update("INSERT INTO gf_miniapp_user(id,nickname,openid,is_property_manager) VALUES(1,'guest','open1',0),(2,'owner','open2',0),(3,'manager','open3',1),(4,'other','open4',0)");
        jdbc.update("INSERT INTO sys_user(id,nickname) VALUES(10,'staff'),(20,'other staff')");
        jdbc.update("INSERT INTO gf_property(id,user_id,title,price,price_type,address) VALUES(100,2,'test property',1000,'rent','address')");
        var store = proxy(new OperationsStore(jdbc));
        roles = mock(com.guardfind.backend.system.service.RoleService.class);
        operations = proxy(new PropertyOperationsService(store, new ObjectMapper(), new TenantExtensionProperties(), roles));
        notifications = new PropertyNotificationService(store, operations, tx);
        notices = mock(MiniappNoticeMapper.class);
        bookings = proxy(new PropertyBookingOperations(store, operations, new MiniappNoticeService(notices), notifications));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var p = new ProxyFactory(target);
        p.setProxyTargetClass(true);
        p.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        return (T)p.getProxy();
    }

    @AfterEach
    void close() {
        if (jdbc != null)
            jdbc.execute("SHUTDOWN");
        if (tenants != null)
            tenants.close();
    }

    private void booking(int status, Long owner, LocalDateTime time) {
        jdbc.update("INSERT INTO gf_property_booking(id,property_id,user_id,owner_id,booking_time,contact_name,contact_phone,status) VALUES(200,100,1,?,?,'guest','13800000000',?)", owner, time, status);
    }

    private int status() {
        return jdbc.queryForObject("SELECT status FROM gf_property_booking WHERE id=200", Integer.class);
    }

    private PropertyBookingOperations.Action action(String name) {
        return new PropertyBookingOperations.Action(name, "reason", null);
    }

    @Test
    void guestCannotConfirmAndStrangerCannotView() {
        booking(1, 2L, LocalDateTime.now().plusDays(2));
        assertThatThrownBy(() -> bookings.action(200, 1L, false, action("CONFIRM"))).hasMessageContaining("只能");
        assertThatThrownBy(() -> bookings.detail(200, 4L, false)).hasMessageContaining("无权");
        assertThat(status()).isEqualTo(1);
    }

    @Test
    void adminCancellationAlwaysNotifiesGuestEvenWithoutOwner() {
        booking(2, null, LocalDateTime.now().plusDays(2));
        bookings.action(200, 10L, true, action("CANCEL"));
        assertThat(status()).isEqualTo(4);
        var capture = org.mockito.ArgumentCaptor.forClass(MiniappNoticeDO.class);
        verify(notices).insert(capture.capture());
        assertThat(capture.getValue().getUserId()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT state FROM gf_property_notification", String.class))
            .isEqualTo("DISABLED");
    }

    @Test
    void failedNoticeRollsBackBookingAndAudit() {
        booking(1, 2L, LocalDateTime.now().plusDays(2));
        doThrow(new IllegalStateException("notice failed")).when(notices).insert(any(MiniappNoticeDO.class));
        assertThatThrownBy(() -> bookings.action(200, 10L, true, action("CONFIRM"))).hasMessage("notice failed");
        assertThat(status()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_booking_log", Long.class)).isZero();
    }

    @Test
    void rescheduleResetsConfirmationAndPreservesTrace() {
        booking(2, 2L, LocalDateTime.now().plusDays(2));
        var time = LocalDateTime.now().plusDays(4).withNano(0);
        bookings.action(200, 1L, false, new PropertyBookingOperations.Action("RESCHEDULE", "change time", time
            .toString()));
        assertThat(status()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT booking_time FROM gf_property_booking", LocalDateTime.class))
            .isEqualTo(time);
        assertThat(bookings.detail(200, 1L, false).get("logs")).asList().hasSize(1);
    }

    @Test
    void assignedManagerCanHandleButRevokedManagerCannot() {
        booking(1, null, LocalDateTime.now().plusDays(2));
        jdbc.update("INSERT INTO gf_property_operation(property_id,manager_id,availability,version,update_time) VALUES(100,3,'ACTIVE',0,?)", LocalDateTime
            .now());
        bookings.action(200, 3L, false, action("CONFIRM"));
        assertThat(status()).isEqualTo(2);
        jdbc.update("UPDATE gf_miniapp_user SET is_property_manager=0 WHERE id=3");
        assertThatThrownBy(() -> bookings.detail(200, 3L, false)).hasMessageContaining("无权");
        assertThat(((List<?>)bookings.mine(3L, "owner", null, 1, 10).get("list"))).isEmpty();
    }

    @Test
    void expiresOldBookingsAndReminderDoesNotRepeat() {
        booking(2, 2L, LocalDateTime.now().minusDays(3));
        assertThat(bookings.maintain()).isEqualTo(1);
        assertThat(status()).isEqualTo(8);
        assertThat(bookings.maintain()).isZero();
        jdbc.update("UPDATE gf_property_booking SET status=2,booking_time=?,update_time=?", LocalDateTime.now()
            .plusHours(3), LocalDateTime.now());
        bookings.maintain();
        bookings.maintain();
        assertThat(jdbc
            .queryForObject("SELECT COUNT(*) FROM gf_property_booking_log WHERE action='REMIND'", Long.class))
            .isEqualTo(1);
    }

    @Test
    void receptionValidatesStateCoordinatesAndStaleVersion() {
        try (var stp = mockStatic(StpUtil.class)) {
            operations
                .saveProperty(100, new PropertyOperationsService.PropertyInput(3L, "PAUSED", "maintenance", null, 31.2, 121.5, "gate", "arrive early", 0));
            assertThatThrownBy(() -> operations.assertAvailable(100)).hasMessageContaining("暂不接受");
            assertThatThrownBy(() -> operations
                .saveProperty(100, new PropertyOperationsService.PropertyInput(3L, "ACTIVE", "", null, 31.2, 121.5, "gate", "", 0)))
                .hasMessageContaining("刷新");
            assertThat(operations.property(100, 1L, false).get("managerId")).isEqualTo("3");
        }
    }

    @Test
    void pausedPropertyRequiresCheckoutAndSettlementOfLatestLeaseBeforeReopening() throws Exception {
        for (var statement : Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_lease_lifecycle.sql"))
            .split(";"))
            if (!statement.isBlank())
                jdbc.execute(statement);
        String insertLease = """
            INSERT INTO gf_property_lease(id,property_id,property_title,property_address,tenant_id,landlord_id,title,
                landlord_name,tenant_name,start_date,end_date,monthly_rent,deposit,terms,status,create_time,update_time)
            VALUES(?,100,'apartment','address',1,2,'lease','owner','guest',?,?,1000,1000,'terms','SIGNED',NOW(),NOW())
            """;
        var today = LocalDate.now();
        jdbc.update(insertLease, 10, today.minusMonths(4), today.minusMonths(3));
        jdbc.update(insertLease, 11, today.minusMonths(2), today.minusDays(1));
        String insertDocument = """
            INSERT INTO gf_property_lease_document(id,lease_id,kind,content_json,content_hash,status,create_time,update_time)
            VALUES(?,?,?,'{}','hash',?,NOW(),NOW())
            """;
        jdbc.update(insertDocument, 1, 10, "CHECK_OUT", "CONFIRMED");
        jdbc.update(insertDocument, 2, 10, "SETTLEMENT", "CLOSED");
        jdbc.update(insertDocument, 3, 11, "CHECK_OUT", "CONFIRMED");
        jdbc.update(insertDocument, 4, 11, "SETTLEMENT", "REFUNDED");
        jdbc.update("INSERT INTO gf_property_operation(property_id,availability,version,update_time) VALUES(100,'RENTED',0,NOW())");
        try (var stp = mockStatic(StpUtil.class)) {
            operations
                .saveProperty(100, new PropertyOperationsService.PropertyInput(null, "PAUSED", "维修", null, null, null, null, null, 0));
            var open = new PropertyOperationsService.PropertyInput(null, "ACTIVE", "", null, null, null, null, null, 1);
            assertThatThrownBy(() -> operations.saveProperty(100, open)).hasMessageContaining("押金结算到账确认");
            assertThat(operations.property(100, null, true).get("availability")).isEqualTo("PAUSED");
            jdbc.update("UPDATE gf_property_lease_document SET status='CLOSED' WHERE id=4");
            operations.saveProperty(100, open);
            assertThat(operations.property(100, null, true).get("availability")).isEqualTo("ACTIVE");
        }
    }

    @Test
    void policiesMustBeCompleteAndWechatStartsDisabled() {
        var settings = operations.settings();
        assertThat(settings.isSubscriptionEnabled()).isFalse();
        assertThatThrownBy(() -> notifications.subscribe(1, 200, "fake")).hasMessageContaining("尚未启用");
        settings.setPoliciesPublished(true);
        assertThatThrownBy(() -> operations.saveSettings(settings)).hasMessageContaining("完整填写");
        settings.setOperator("operator");
        settings.setContact("service@example.test");
        settings.setPolicyVersion("v1");
        settings.setEffectiveDate("2026-11-17");
        settings.setUserAgreement("terms");
        settings.setPrivacyPolicy("privacy");
        operations.saveSettings(settings);
        operations.acceptPolicy(1, "v1");
        operations.acceptPolicy(1, "v1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_policy_accept", Long.class)).isEqualTo(1);
    }

    @Test
    void transferRequiresAnActiveStaffMemberWithSupportPermissions() {
        when(roles.listPermissionByUserId(10L)).thenReturn(Set
            .of("system:im:list", "system:im:send", "system:im:workflow"));
        assertThat(operations.supportAdmins()).extracting(row -> row.get("id")).containsExactly("10");
        try (var stp = mockStatic(StpUtil.class)) {
            operations.incoming(1);
            operations.supportAction(1, 10, new PropertyOperationsService.SupportInput("CLAIM", "", null, 0));
            assertThatThrownBy(() -> operations
                .supportAction(1, 10, new PropertyOperationsService.SupportInput("TRANSFER", "", 20L, 1)))
                .hasMessageContaining("接待权限");
            assertThat(((Number)operations.support(1, true).get("version")).longValue()).isEqualTo(1L);
            when(roles.listPermissionByUserId(20L)).thenReturn(Set
                .of("system:im:list", "system:im:send", "system:im:workflow"));
            operations.supportAction(1, 10, new PropertyOperationsService.SupportInput("TRANSFER", "", 20L, 1));
            operations.guardReply(1, 20);
        }
    }

    @Test
    void supportClaimNotesTransferAndResolutionAreVersioned() {
        try (var stp = mockStatic(StpUtil.class)) {
            operations.incoming(1);
            operations.supportAction(1, 10, new PropertyOperationsService.SupportInput("CLAIM", "start", null, 0));
            assertThatThrownBy(() -> operations.guardReply(1, 20)).hasMessageContaining("其他客服");
            operations.supportAction(1, 10, new PropertyOperationsService.SupportInput("NOTE", "internal", null, 1));
            assertThat(operations.support(1, false)).doesNotContainKey("logs");
            operations.supportAction(1, 10, new PropertyOperationsService.SupportInput("RESOLVE", "done", null, 2));
            assertThatThrownBy(() -> operations.guardReply(1, 10)).hasMessageContaining("重新打开");
            operations.incoming(1);
            operations.guardReply(1, 10);
            assertThatThrownBy(() -> operations
                .supportAction(1, 10, new PropertyOperationsService.SupportInput("NOTE", "stale", null, 1)))
                .hasMessageContaining("刷新");
        }
    }
}
