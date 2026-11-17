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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.property.operations.*;
import com.guardfind.backend.miniapp.service.MiniappNoticeService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PropertyLeaseTest {
    JdbcTemplate jdbc;
    PropertyLeaseService service;
    MiniappNoticeService notices;
    PropertyOperationsService operations;
    DataSourceTransactionManager tx;

    @BeforeAll
    static void utilities() {
        var context = mock(org.springframework.context.ApplicationContext.class);
        var factory = mock(com.feiniaojin.gracefulresponse.api.ResponseStatusFactory.class);
        when(factory.defaultError())
            .thenAnswer(i -> new com.feiniaojin.gracefulresponse.defaults.DefaultResponseStatus("500", "error"));
        when(context.getBean(com.feiniaojin.gracefulresponse.api.ResponseStatusFactory.class)).thenReturn(factory);
        new cn.hutool.extra.spring.SpringUtil().setApplicationContext(context);
    }

    @BeforeEach
    void setup() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lease" + System
            .nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        jdbc.execute("CREATE TABLE gf_miniapp_user(id BIGINT PRIMARY KEY,nickname VARCHAR(100),account_status INT DEFAULT 1,deleted BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE gf_property(id BIGINT PRIMARY KEY,user_id BIGINT,title VARCHAR(100),address VARCHAR(200),price_type VARCHAR(10),deleted BIGINT DEFAULT 0)");
        var sql = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_lease_workflow.sql"))
            .split("-- changeset guardfind:property-lease-menu-1")[0];
        for (var stmt : sql.split(";"))
            if (!stmt.isBlank())
                jdbc.execute(stmt);
        jdbc.update("INSERT INTO gf_miniapp_user(id,nickname) VALUES(1,'tenant'),(2,'landlord'),(3,'other')");
        jdbc.update("INSERT INTO gf_property(id,user_id,title,address,price_type) VALUES(100,2,'apartment','address','rent')");
        notices = mock(MiniappNoticeService.class);
        operations = mock(PropertyOperationsService.class);
        var target = new PropertyLeaseService(new OperationsStore(jdbc), operations, notices, new ObjectMapper(), tx);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        service = (PropertyLeaseService)proxy.getProxy();
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("SHUTDOWN");
    }

    PropertyLeaseService.Draft draft(LocalDate start, LocalDate end) {
        return new PropertyLeaseService.Draft(100L, 1L, 2L, "租赁合同", "房东", "租客", start.toString(), end
            .toString(), new BigDecimal("2000.00"), new BigDecimal("2000.00"), "双方约定的租赁条款全文", null, null);
    }

    long create() {
        return Long.parseLong(service.create(draft(LocalDate.now(), LocalDate.now().plusDays(30)), 9));
    }

    PropertyLeaseService.Action action(String action, long version, String reason) {
        return new PropertyLeaseService.Action(action, version, reason, null, null, null, null, null, null, null, null);
    }

    Map<String, Object> detail(long id) {
        return service.detail(id, null, true);
    }

    long version(long id) {
        return ((Number)detail(id).get("version")).longValue();
    }

    void publish(long id) {
        service.action(id, 9, true, action("PUBLISH", version(id), null));
    }

    PropertyLeaseService.Action sign(long id, long user) {
        return new PropertyLeaseService.Action("SIGN", version(id), null, user == 1
            ? "租客"
            : "房东", true, (String)detail(id).get("contentHash"), null, null, null, null, null);
    }

    void signed(long id) {
        publish(id);
        service.action(id, 1, false, sign(id, 1));
        service.action(id, 2, false, sign(id, 2));
    }

    @Test
    void draftsArePrivateAndSignaturesRequireBothPartiesAndMatchingFrozenContent() {
        long id = create();
        assertThatThrownBy(() -> service.detail(id, 1L, false)).hasMessageContaining("无权");
        publish(id);
        assertThatThrownBy(() -> service.detail(id, 3L, false)).hasMessageContaining("无权");
        var bad = new PropertyLeaseService.Action("SIGN", version(id), null, "租客", true, "outdated", null, null, null, null, null);
        assertThatThrownBy(() -> service.action(id, 1, false, bad)).hasMessageContaining("最新合同");
        service.action(id, 1, false, sign(id, 1));
        assertThat(detail(id).get("status")).isEqualTo("PENDING_SIGNATURE");
        var first = detail(id).get("tenantSignedAt");
        service.action(id, 2, false, sign(id, 2));
        assertThat(detail(id).get("status")).isEqualTo("SIGNED");
        assertThat(detail(id).get("tenantSignedAt")).isEqualTo(first);
        assertThat(detail(id).get("contentHash").toString()).hasSize(64);
        assertThatThrownBy(() -> service.update(id, draft(LocalDate.now(), LocalDate.now().plusDays(40)), 9))
            .hasMessageContaining("刷新");
    }

    @Test
    void signatureRejectsTamperedTermsAndStaleVersions() {
        long id = create();
        publish(id);
        var signature = sign(id, 1);
        jdbc.update("UPDATE gf_property_lease SET terms='tampered' WHERE id=?", id);
        assertThatThrownBy(() -> service.action(id, 1, false, signature)).hasMessageContaining("正文校验");
        assertThat(detail(id).get("tenantSignedAt")).isNull();
        assertThatThrownBy(() -> service.action(id, 9, true, action("VOID", 0, "reason"))).hasMessageContaining("刷新");
    }

    @Test
    void publicationRejectsOverlapsAndOwnershipAndUserErrors() {
        long first = create();
        publish(first);
        long second = create();
        assertThatThrownBy(() -> publish(second)).hasMessageContaining("已有待签");
        var self = new PropertyLeaseService.Draft(100L, 2L, 2L, "a", "房东", "房东", LocalDate.now().toString(), LocalDate
            .now()
            .plusDays(1)
            .toString(), BigDecimal.ONE, BigDecimal.ZERO, "terms", null, null);
        assertThatThrownBy(() -> service.create(self, 9)).hasMessageContaining("同一人");
        jdbc.update("UPDATE gf_miniapp_user SET account_status=2 WHERE id=1");
        assertThatThrownBy(() -> service.action(first, 1, false, sign(first, 1))).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.guardForPropertyDelete(100)).hasMessageContaining("合同");
    }

    @Test
    void reminderDeliveryDeduplicatesAndRollsBackItsMarkerOnFailure() {
        long id = create();
        signed(id);
        reset(notices);
        doThrow(new IllegalStateException("notice failed")).when(notices)
            .send(anyLong(), anyString(), anyLong(), anyString(), anyString());
        assertThat(service.maintain()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_lease_reminder", Long.class)).isZero();
        reset(notices);
        assertThat(service.maintain()).isEqualTo(1);
        assertThat(service.maintain()).isZero();
        verify(notices, times(2)).send(anyLong(), eq("PROPERTY_LEASE"), eq(id), anyString(), anyString());
    }

    @Test
    void reminderBatchesDoNotStarveAfterFiveHundredCompletedRows() {
        long id = create();
        signed(id);
        reset(notices);
        for (int i = 1; i <= 501; i++)
            jdbc.update("INSERT INTO gf_property_lease(id,property_id,property_title,property_address,tenant_id,landlord_id,title,landlord_name,tenant_name,start_date,end_date,monthly_rent,deposit,terms,status,create_time,update_time) SELECT ?,property_id,property_title,property_address,tenant_id,landlord_id,title,landlord_name,tenant_name,start_date,end_date,monthly_rent,deposit,terms,status,create_time,update_time FROM gf_property_lease WHERE id=?", (long)i, id);
        assertThat(service.maintain()).isEqualTo(500);
        assertThat(service.maintain()).isEqualTo(2);
        assertThat(service.maintain()).isZero();
    }

    @Test
    void renewalCreatesNewDraftAndRequiresNewSignaturesWhileOriginalRemainsUnchanged() {
        long id = create();
        signed(id);
        String originalEnd = detail(id).get("endDate").toString(), hash = detail(id).get("contentHash").toString();
        var request = new PropertyLeaseService.Action("RENEW_REQUEST", version(id), "继续住", null, null, null, LocalDate
            .now()
            .plusDays(395)
            .toString(), null, null, null, null);
        service.action(id, 1, false, request);
        assertThatThrownBy(() -> service.action(id, 1, false, request)).hasMessageContaining("刷新");
        var approve = new PropertyLeaseService.Action("RENEW_APPROVE", version(id), null, null, null, null, null, LocalDate
            .now()
            .plusDays(31)
            .toString(), LocalDate.now().plusDays(395).toString(), null, null);
        long renewal = Long.parseLong(service.action(id, 9, true, approve).get("renewalLeaseId").toString());
        assertThat(detail(renewal).get("status")).isEqualTo("DRAFT");
        assertThat(detail(renewal).get("tenantSignedAt")).isNull();
        assertThat(detail(id).get("endDate")).isEqualTo(originalEnd);
        assertThat(detail(id).get("contentHash")).isEqualTo(hash);
        assertThatThrownBy(() -> service.action(id, 9, true, action("TERMINATE", version(id), "reason")))
            .hasMessageContaining("关联续租");
        service.action(renewal, 9, true, action("VOID", version(renewal), "撤销续租"));
        assertThat(detail(id).get("renewalStatus")).isEqualTo("CANCELED");
    }

    @Test
    void concurrentPublicationsSerializeOnTheProperty() {
        long first = create(), second = create();
        var pool = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(1);
        try {
            var jobs = List.of(first, second).stream().map(id -> pool.submit(() -> {
                latch.await();
                try {
                    publish(id);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            })).toList();
            latch.countDown();
            long successes = 0;
            for (var job : jobs)
                if (job.get(15, TimeUnit.SECONDS))
                    successes++;
            assertThat(successes).isEqualTo(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiryAndMoveoutRemainAuditableAndReminderSettingsValidate() {
        long id = create();
        signed(id);
        jdbc.update("UPDATE gf_property_lease SET end_date=? WHERE id=?", LocalDate.now().minusDays(1), id);
        assertThat(service.detail(id, 1L, false).get("status")).isEqualTo("EXPIRED");
        assertThat(service.detail(id, 1L, false).get("canRenew")).isEqualTo(false);
        service.action(id, 1, false, action("MOVE_OUT_REQUEST", version(id), "交接退租"));
        assertThat(detail(id).get("moveOutStatus")).isEqualTo("PENDING");
        service.action(id, 9, true, action("MOVE_OUT_RESOLVE", version(id), "已办理交接"));
        assertThat(detail(id).get("moveOutStatus")).isEqualTo("RESOLVED");
        var settings = service.settings();
        settings.setReminderDays(List.of(-1));
        assertThatThrownBy(() -> service.saveSettings(settings)).hasMessageContaining("365");
        settings.setReminderDays(List.of(7, 1, 0));
        settings.setRemindersEnabled(false);
        service.saveSettings(settings);
        assertThat(service.maintain()).isZero();
    }

    @Test
    void voidedUnpublishedDraftsStayPrivateAndNeverSendTenantNotices() {
        long lease = create();
        service.action(lease, 9, true, action("VOID", version(lease), "未发布草稿撤销"));
        assertThatThrownBy(() -> service.detail(lease, 1L, false)).hasMessageContaining("无权");
        assertThat(service.page(1L, false, null, null, null, 1, 10).get("total")).isEqualTo(0L);
        verifyNoInteractions(notices);
    }

    @Test
    void signingAndRenewalAreRestrictedToMatchingParticipants() {
        long lease = create();
        publish(lease);
        var wrongName = new PropertyLeaseService.Action("SIGN", version(lease), null, "非本人", true, detail(lease)
            .get("contentHash")
            .toString(), null, null, null, null, null);
        assertThatThrownBy(() -> service.action(lease, 1, false, wrongName)).hasMessageContaining("姓名");
        assertThatThrownBy(() -> service.action(lease, 9, true, sign(lease, 1))).hasMessageContaining("双方本人");
        service.action(lease, 1, false, sign(lease, 1));
        service.action(lease, 2, false, sign(lease, 2));
        service.action(lease, 1, false, action("MOVE_OUT_REQUEST", version(lease), "希望退租"));
        assertThat(service.detail(lease, 1L, false).get("canRenew")).isEqualTo(false);
        var request = new PropertyLeaseService.Action("RENEW_REQUEST", version(lease), null, null, null, null, LocalDate
            .now()
            .plusDays(395)
            .toString(), null, null, null, null);
        assertThatThrownBy(() -> service.action(lease, 2, false, request)).hasMessageContaining("承租人");
        assertThatThrownBy(() -> service.action(lease, 1, false, request)).hasMessageContaining("退租申请");
    }

    @Test
    void administratorsCanFindContractNumbersAndPendingApplications() {
        long lease = create();
        signed(lease);
        assertThat(service.page(null, true, null, Long.toString(lease), null, 1, 10).get("total")).isEqualTo(1L);
        assertThat(service.page(null, true, null, null, null, "PENDING", null, 1, 10).get("total")).isEqualTo(0L);
        service
            .action(lease, 1, false, new PropertyLeaseService.Action("RENEW_REQUEST", version(lease), null, null, null, null, LocalDate
                .now()
                .plusDays(395)
                .toString(), null, null, null, null));
        assertThat(service.page(null, true, null, null, null, "PENDING", null, 1, 10).get("total")).isEqualTo(1L);
        assertThat(service.page(null, true, null, null, null, null, "PENDING", 1, 10).get("total")).isEqualTo(0L);
        assertThatThrownBy(() -> service.page(null, true, null, null, null, "INVALID", null, 1, 10))
            .hasMessageContaining("续租状态");
    }

    @Test
    void stateChangesRollbackWhenNoticesFail() {
        long id = create();
        doThrow(new IllegalStateException("notice failed")).when(notices)
            .send(anyLong(), anyString(), anyLong(), anyString(), anyString());
        assertThatThrownBy(() -> publish(id)).hasMessageContaining("notice failed");
        assertThat(detail(id).get("status")).isEqualTo("DRAFT");
        assertThat(detail(id).get("contentHash")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_lease_log WHERE action='PUBLISH'", Long.class))
            .isZero();
    }
}
