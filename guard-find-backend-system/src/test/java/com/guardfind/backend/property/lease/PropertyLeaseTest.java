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
    OperationsStore store;
    PropertyLeaseService service;
    MiniappNoticeService notices;
    PropertyOperationsService operations;
    DataSourceTransactionManager tx;
    PropertySigningProfileService profiles;

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
        var lifecycleSql = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_lease_lifecycle.sql"));
        for (var stmt : lifecycleSql.split(";"))
            if (!stmt.isBlank())
                jdbc.execute(stmt);
        for (var stmt : Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_lease_handwriting.sql"))
            .split(";"))
            if (!stmt.isBlank())
                jdbc.execute(stmt);
        var identitySql = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_identity_verification.sql"))
            .split("-- changeset guardfind:property-identity-verification-menu-1")[0]
            .replace("b'0'", "0");
        for (var stmt : identitySql.split(";"))
            if (!stmt.isBlank())
                jdbc.execute(stmt);
        jdbc.update("INSERT INTO gf_miniapp_user(id,nickname) VALUES(1,'tenant'),(2,'landlord'),(3,'other')");
        for (var stmt : Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_lease_review.sql"))
            .split(";"))
            if (!stmt.isBlank())
                jdbc.execute(stmt);
        jdbc.update("INSERT INTO gf_property(id,user_id,title,address,price_type) VALUES(100,2,'apartment','address','rent')");
        notices = mock(MiniappNoticeService.class);
        operations = mock(PropertyOperationsService.class);
        var storeProxy = new ProxyFactory(new OperationsStore(jdbc));
        storeProxy.setProxyTargetClass(true);
        storeProxy.addAdvice((org.aopalliance.intercept.MethodInterceptor)invocation -> invocation.proceed());
        store = (OperationsStore)storeProxy.getProxy();
        profiles = new PropertySigningProfileService(store, new ObjectMapper());
        var target = new PropertyLeaseService(store, operations, notices, new ObjectMapper(), tx, profiles);
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
            : "房东", true, (String)detail(id)
                .get("contentHash"), null, null, null, null, null, handwriting(), false, null);
    }

    LeaseHandwriting handwriting() {
        return new LeaseHandwriting(List.of(List
            .of(new LeaseHandwriting.Point(80d, 80d), new LeaseHandwriting.Point(250d, 80d), new LeaseHandwriting.Point(120d, 140d), new LeaseHandwriting.Point(250d, 280d), new LeaseHandwriting.Point(310d, 130d), new LeaseHandwriting.Point(420d, 270d), new LeaseHandwriting.Point(460d, 100d), new LeaseHandwriting.Point(600d, 240d))));
    }

    @Test
    void reviewBlocksSigningUntilTheAuthorConfirmsAndKeepsBothPartiesInformed() {
        long lease = create();
        publish(lease);
        long round = ((Number)detail(lease).get("publishedVersion")).longValue();
        String hash = (String)detail(lease).get("contentHash");
        var question = new PropertyLeaseService.ReviewInput("CREATE", round, hash, null, null, "第三条押金退还", "请说明退款日期");
        service.review(lease, 1, false, question);
        service.review(lease, 1, false, question);
        assertThat(service.page(null, true, "REVIEW_PENDING", null, null, 1, 10).get("total")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_lease_review", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.action(lease, 2, false, sign(lease, 2))).hasMessageContaining("审阅意见");
        long review = jdbc.queryForObject("SELECT id FROM gf_property_lease_review", Long.class);
        service
            .review(lease, 9, true, new PropertyLeaseService.ReviewInput("REPLY", round, hash, review, 1L, null, "按合同约定交接后7天退款"));
        assertThatThrownBy(() -> service.action(lease, 1, false, sign(lease, 1))).hasMessageContaining("审阅意见");
        var confirm = new PropertyLeaseService.ReviewInput("CONFIRM", round, hash, review, 2L, null, null);
        assertThatThrownBy(() -> service.review(lease, 9, true, confirm)).hasMessageContaining("提出人");
        assertThatThrownBy(() -> service.review(lease, 2, false, confirm)).hasMessageContaining("提出人");
        service
            .review(lease, 1, false, new PropertyLeaseService.ReviewInput("REOPEN", round, hash, review, 2L, null, "退款渠道仍需说明"));
        service
            .review(lease, 9, true, new PropertyLeaseService.ReviewInput("REPLY", round, hash, review, 3L, null, "原付款渠道退款"));
        service
            .review(lease, 1, false, new PropertyLeaseService.ReviewInput("CONFIRM", round, hash, review, 4L, null, null));
        assertThat(detail(lease).get("unresolvedReviews")).isEqualTo(0L);
        assertThat(service.page(null, true, "REVIEW_PENDING", null, null, 1, 10).get("total")).isEqualTo(0L);
        service.action(lease, 1, false, sign(lease, 1));
        service.action(lease, 2, false, sign(lease, 2));
        assertThat(detail(lease).get("status")).isEqualTo("SIGNED");
        assertThat(jdbc
            .queryForObject("SELECT COUNT(*) FROM gf_property_lease_log WHERE action LIKE 'REVIEW_%'", Long.class))
            .isEqualTo(5);
    }

    @Test
    void reviewsRejectOtherAccountsAndOldRoundsAndDoNotCarryOverToNewPublication() {
        long lease = create();
        publish(lease);
        long round = ((Number)detail(lease).get("publishedVersion")).longValue();
        String hash = (String)detail(lease).get("contentHash");
        var question = new PropertyLeaseService.ReviewInput("CREATE", round, hash, null, null, "房源信息", "请确认地址");
        assertThatThrownBy(() -> service.review(lease, 3, false, question)).isInstanceOf(RuntimeException.class);
        service.review(lease, 1, false, question);
        service.action(lease, 9, true, action("RECALL", version(lease), "核对地址后重新发送"));
        publish(lease);
        assertThatThrownBy(() -> service.review(lease, 1, false, question)).hasMessageContaining("重新审阅");
        assertThat(detail(lease).get("unresolvedReviews")).isEqualTo(0L);
        assertThat((List<?>)detail(lease).get("reviews")).hasSize(1);
        service.action(lease, 1, false, sign(lease, 1));
    }

    @Test
    void handwritingIsRequiredAndTheSavedSignatureIsPrivateAndBoundToTheContract() throws Exception {
        long lease = create();
        publish(lease);
        var valid = sign(lease, 1);
        var textOnly = new PropertyLeaseService.Action("SIGN", valid.version(), null, "租客", true, valid
            .contentHash(), null, null, null, null, null);
        assertThatThrownBy(() -> service.action(lease, 1, false, textOnly)).hasMessageContaining("手写");
        var blank = new PropertyLeaseService.Action("SIGN", valid.version(), null, "租客", true, valid
            .contentHash(), null, null, null, null, null, new LeaseHandwriting(List.of()), false, null);
        assertThatThrownBy(() -> service.action(lease, 1, false, blank)).hasMessageContaining("手写");
        var saved = new PropertyLeaseService.Action("SIGN", valid.version(), null, "租客", true, valid
            .contentHash(), null, null, null, null, null, handwriting(), true, null);
        service.action(lease, 1, false, saved);
        assertThat(profiles.profile(1)).containsKey("handwriting");
        assertThat(profiles.profile(2)).isEmpty();
        var records = store
            .rows("SELECT signer_role,content_hash,signature_hash,contract_snapshot FROM gf_property_lease_signature WHERE lease_id=?", lease);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().get("contentHash")).isEqualTo(valid.contentHash());
        assertThat(records.getFirst().get("signatureHash").toString()).hasSize(64);
        assertThat(records.getFirst().get("contractSnapshot").toString()).contains("双方约定的租赁条款全文");
        assertThatThrownBy(() -> service.action(lease, 9, true, action("RECALL", version(lease), "改正文")))
            .hasMessageContaining("已有一方");
        assertThatThrownBy(() -> service.action(lease, 1, false, action("DECLINE", version(lease), "拒绝")))
            .hasMessageContaining("已签署");
        profiles.remove(1, "HANDWRITING");
        assertThat(profiles.profile(1)).isEmpty();
        assertThat((List<?>)detail(lease).get("signatures")).hasSize(1);
    }

    @Test
    void recallRequiresUnsignedPartiesAndRemindersAreThrottledAndOnlyNotifyUnsignedParty() {
        long lease = create();
        publish(lease);
        service.action(lease, 9, true, action("RECALL", version(lease), "双方信息需核对"));
        assertThat(detail(lease).get("status")).isEqualTo("DRAFT");
        assertThatThrownBy(() -> service.detail(lease, 1L, false)).hasMessageContaining("无权");
        publish(lease);
        service.action(lease, 1, false, sign(lease, 1));
        reset(notices);
        service.action(lease, 9, true, action("REMIND_SIGN", version(lease), null));
        verify(notices).send(eq(2L), eq("PROPERTY_LEASE"), eq(lease), eq("租赁合同待签提醒"), anyString());
        verifyNoMoreInteractions(notices);
        assertThatThrownBy(() -> service.action(lease, 9, true, action("REMIND_SIGN", version(lease), null)))
            .hasMessageContaining("10分钟");
    }

    @Test
    void personalSealCannotBeUsedByAnotherUserAndSignedPdfContainsImmutableImages() throws Exception {
        var image = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED);
        graphics.drawOval(5, 5, 90, 90);
        graphics.dispose();
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        profiles
            .uploadSeal(1, new org.springframework.mock.web.MockMultipartFile("file", "seal.png", "image/png", output
                .toByteArray()));
        @SuppressWarnings("unchecked") var seal = (Map<String, Object>)profiles.profile(1).get("seal");
        long sealId = Long.parseLong(seal.get("id").toString());
        long lease = create();
        publish(lease);
        var wrong = new PropertyLeaseService.Action("SIGN", version(lease), null, "房东", true, detail(lease)
            .get("contentHash")
            .toString(), null, null, null, null, null, handwriting(), false, sealId);
        assertThatThrownBy(() -> service.action(lease, 2, false, wrong)).hasMessageContaining("本人");
        var right = new PropertyLeaseService.Action("SIGN", version(lease), null, "租客", true, detail(lease)
            .get("contentHash")
            .toString(), null, null, null, null, null, handwriting(), false, sealId);
        service.action(lease, 1, false, right);
        service.action(lease, 2, false, sign(lease, 2));
        profiles.remove(1, "SEAL");
        var pdf = new PropertyLeasePdfService(service);
        assertThatThrownBy(() -> pdf.download(lease, 3L, false)).hasMessageContaining("无权");
        byte[] bytes = Base64.getDecoder().decode(pdf.download(lease, 1L, false).get("base64").toString());
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            var text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("双方约定的租赁条款全文", "双方已签署", "签署记录校验值");
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        }
        Files.createDirectories(Path.of("target/signing-preview"));
        Files.write(Path.of("target/signing-preview/contract.pdf"), bytes);
        jdbc.update("UPDATE gf_property_lease_signature SET image_data=? WHERE lease_id=? AND signer_role='TENANT'", new byte[] {
            0}, lease);
        assertThatThrownBy(() -> pdf.download(lease, 1L, false)).hasMessageContaining("签署记录校验失败");
    }

    @Test
    void longDraftPdfKeepsCompleteTermsAndClearlyLabelsItsUnsignedState() throws Exception {
        long lease = create();
        var record = detail(lease);
        String paragraph = "这是用于排版验证的合同条款。租赁双方应逐项核对房屋、租金、押金、交接事项和约定内容。";
        record.put("terms", (paragraph + "\n").repeat(70) + "条款末尾确认项\n特殊字符保留：\uD83E\uDD84");
        byte[] bytes = new PropertyLeasePdfService(service).render(record);
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(3);
            assertThat(extracted).contains("条款末尾确认项", "尚未发送签署", "尚未签署", "[U+1F984]");
        }
        Files.createDirectories(Path.of("target/signing-preview"));
        Files.write(Path.of("target/signing-preview/long-contract.pdf"), bytes);
    }

    @Test
    void bothPartiesCanSignTheSamePublicationButOldPublicationRequestsCannotBeReplayed() {
        long lease = create();
        publish(lease);
        var tenant = sign(lease, 1);
        var landlord = sign(lease, 2);
        service.action(lease, 1, false, tenant);
        service.action(lease, 2, false, landlord);
        assertThat(detail(lease).get("status")).isEqualTo("SIGNED");
        service.action(lease, 9, true, action("TERMINATE", version(lease), "测试结束"));
        long next = create();
        publish(next);
        var previousRound = sign(next, 1);
        service.action(next, 9, true, action("RECALL", version(next), "重新核对"));
        publish(next);
        assertThatThrownBy(() -> service.action(next, 1, false, previousRound)).hasMessageContaining("重新发送");
        service.action(next, 1, false, sign(next, 1));
        assertThat(detail(next).get("tenantSignedAt")).isNotNull();
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
        verify(operations).occupyForLease(100);
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
        // Simulate an imported legacy lease whose term has ended (no handwriting snapshot).
        jdbc.update("DELETE FROM gf_property_lease_signature WHERE lease_id=?", id);
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

    @Test
    void terminatedSignedRenewalReleasesOriginalForAnotherApplicationAndMoveOut() {
        long original = create();
        signed(original);
        service
            .action(original, 1, false, new PropertyLeaseService.Action("RENEW_REQUEST", version(original), "renew", null, null, null, LocalDate
                .now()
                .plusDays(395)
                .toString(), null, null, null, null));
        var approval = new PropertyLeaseService.Action("RENEW_APPROVE", version(original), null, null, null, null, null, LocalDate
            .now()
            .plusDays(31)
            .toString(), LocalDate.now().plusDays(395).toString(), null, null);
        long renewal = Long.parseLong(service.action(original, 9, true, approval).get("renewalLeaseId").toString());
        signed(renewal);
        service.action(renewal, 9, true, action("TERMINATE", version(renewal), "cancel future renewal"));
        assertThat(service.detail(original, 1L, false).get("canRenew")).isEqualTo(true);
        assertThat(detail(original).get("renewalStatus")).isEqualTo("CANCELED");
        assertThat(detail(renewal).get("previousLeaseId")).isEqualTo(Long.toString(original));
        service.action(original, 1, false, action("MOVE_OUT_REQUEST", version(original), "move out"));
        service.action(original, 1, false, action("MOVE_OUT_CANCEL", version(original), "stay"));
        assertThat(detail(original).get("moveOutStatus")).isEqualTo("NONE");
    }

    @Test
    void signingExpiryIsEnforcedBeforeSchedulerAndStillRunsWithRemindersDisabled() {
        long lease = create();
        publish(lease);
        jdbc.update("UPDATE gf_property_lease SET signature_deadline=? WHERE id=?", LocalDateTime.now()
            .minusMinutes(1), lease);
        assertThat(detail(lease).get("status")).isEqualTo("SIGNING_EXPIRED");
        assertThat(service.page(1L, false, "PENDING_SIGNATURE", null, null, 1, 10).get("total")).isEqualTo(0L);
        assertThat(service.page(1L, false, "SIGNING_EXPIRED", null, null, 1, 10).get("total")).isEqualTo(1L);
        assertThatThrownBy(() -> service.action(lease, 1, false, sign(lease, 1))).hasMessageContaining("签署期限");
        long replacement = create();
        publish(replacement);
        var settings = service.settings();
        settings.setRemindersEnabled(false);
        service.saveSettings(settings);
        service.maintain();
        assertThat(jdbc.queryForObject("SELECT status FROM gf_property_lease WHERE id=?", String.class, lease))
            .isEqualTo("SIGNING_EXPIRED");
    }
}
