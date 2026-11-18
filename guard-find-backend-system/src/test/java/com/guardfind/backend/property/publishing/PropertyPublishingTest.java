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

package com.guardfind.backend.property.publishing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.property.operations.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PropertyPublishingTest {
    JdbcTemplate jdbc;
    PropertyPublishingService service;
    PublishingGateway gateway;
    PublishingProperties properties;

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
        ds.setURL("jdbc:h2:mem:publishing" + System
            .nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE gf_property(id BIGINT PRIMARY KEY,title VARCHAR(200),city VARCHAR(50),district VARCHAR(50),community VARCHAR(100),layout VARCHAR(50),area_sqm DECIMAL(10,2),price DECIMAL(12,2),price_type VARCHAR(20),price_unit VARCHAR(20),images LONGTEXT,status INT,deleted BIGINT DEFAULT 0,create_time DATETIME)");
        jdbc.execute("CREATE TABLE gf_property_operation(property_id BIGINT PRIMARY KEY,availability VARCHAR(20))");
        String sql = Files.readString(Path
            .of("../guard-find-backend-api/src/main/resources/db/changelog/mysql/property_publishing.sql"))
            .split("-- changeset guardfind:property-publishing-menu-1")[0];
        for (String statement : sql.split(";"))
            if (!statement.isBlank())
                jdbc.execute(statement);
        jdbc.update("INSERT INTO gf_property(id,title,city,district,community,layout,area_sqm,price,price_type,price_unit,images,status,create_time) VALUES(100,'阳光公寓','上海市','浦东新区','阳光小区','两室一厅',80,3000,'rent','month','[\"https://images.example.com/house.jpg\"]',2,?)", LocalDateTime
            .now());
        gateway = mock(PublishingGateway.class);
        properties = new PublishingProperties();
        when(gateway.ready(anyString())).thenReturn(true);
        when(gateway.publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class)))
            .thenAnswer(i -> new PublishingGateway.Result(((BooleanSupplier)i.getArgument(4)).getAsBoolean()
                ? "SUBMITTED"
                : "FAILED", "official-item", "平台接收"));
        var storeFactory = new ProxyFactory(new OperationsStore(jdbc));
        storeFactory.setProxyTargetClass(true);
        storeFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor)i -> i.proceed());
        var tx = new DataSourceTransactionManager(ds);
        var target = new PropertyPublishingService((OperationsStore)storeFactory
            .getProxy(), mock(PropertyOperationsService.class), new ObjectMapper(), gateway, properties, tx);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        service = (PropertyPublishingService)proxy.getProxy();
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    long account(String key, boolean api) {
        return Long.parseLong(service.saveAccount(null, new PropertyPublishingService.Account(api
            ? "DOUYIN"
            : "XIAOHONGSHU", key, key, api ? "DOUYIN_API" : "MANUAL", api ? key : null, true)));
    }

    PropertyPublishingService.Create request(List<Long> ids, String key) {
        return new PropertyPublishingService.Create(100L, ids, null, null, null, null, key);
    }

    long create(long account, String key) {
        return Long.parseLong(((List<String>)service.create(request(List.of(account), key), 9).get("ids")).get(0));
    }

    long version(long job) {
        return ((Number)service.job(job).get("version")).longValue();
    }

    void action(long id, String action) {
        service.action(id, new PropertyPublishingService.Action(version(id), action, "核对完成", null), 9);
    }

    String status(long id) {
        return (String)service.job(id).get("status");
    }

    @Test
    void batchCreationIsIdempotentAndAChangedRequestCannotReuseTheSameKey() {
        long a = account("a", true), b = account("b", false);
        var request = request(List.of(a, b), "batch-1");
        assertThat(service.create(request, 9)).isEqualTo(service.create(request, 9));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_publish_job", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> service.create(new PropertyPublishingService.Create(100L, List
            .of(a, b), "变更标题", null, null, null, "batch-1"), 9)).hasMessageContaining("其他内容");
        assertThatThrownBy(() -> service.create(request(List.of(a), "different-key"), 9)).hasMessageContaining("相同内容");
    }

    @Test
    void multiAccountJobsKeepManualHandoffSeparateFromActualSubmission() {
        long api = create(account("api", true), "api-post"), manual = create(account("manual", false), "manual-post");
        action(api, "APPROVE");
        action(manual, "APPROVE");
        service.maintain();
        assertThat(status(api)).isEqualTo("SUBMITTED");
        assertThat(status(manual)).isEqualTo("MANUAL_REQUIRED");
        assertThat(service.exportPackage(manual).get("content").toString())
            .contains("不表示已经发布", "https://images.example.com/house.jpg");
        assertThatThrownBy(() -> service
            .action(manual, new PropertyPublishingService.Action(version(manual), "CONFIRM_PUBLISHED", null, "https://evil.example.com/a"), 9))
            .hasMessageContaining("目标平台");
        service
            .action(manual, new PropertyPublishingService.Action(version(manual), "CONFIRM_PUBLISHED", null, "https://www.xiaohongshu.com/explore/123"), 9);
        assertThat(status(manual)).isEqualTo("PUBLISHED");
        verify(gateway, times(1)).publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class));
    }

    @Test
    void stoppedOrChangedPropertiesNeverDispatchOldApprovedContent() {
        long id = create(account("api", true), "stale");
        action(id, "APPROVE");
        jdbc.update("UPDATE gf_property SET price=4000 WHERE id=100");
        service.maintain();
        assertThat(status(id)).isEqualTo("REJECTED");
        verify(gateway, never()).publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class));
        service.edit(id, new PropertyPublishingService.Edit(version(id), "阳光公寓", "已核对新的4000元租金", List
            .of("https://images.example.com/house.jpg"), null), 9);
        action(id, "APPROVE");
        jdbc.update("INSERT INTO gf_property_operation VALUES(100,'RENTED')");
        service.maintain();
        assertThat(status(id)).isEqualTo("REJECTED");
    }

    @Test
    void uncertainSubmissionAndExpiredWorkerAreNotAutomaticallyRetried() {
        long id = create(account("api", true), "unknown");
        action(id, "APPROVE");
        when(gateway.publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class)))
            .thenThrow(new RuntimeException("network"));
        service.maintain();
        assertThat(status(id)).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> action(id, "RETRY")).hasMessageContaining("不支持");
        service
            .action(id, new PropertyPublishingService.Action(version(id), "CONFIRM_NOT_PUBLISHED", "已登录创作者中心核实无作品", null), 9);
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> action(id, "RETRY")).hasMessageContaining("不支持");
        service.maintain();
        verify(gateway, times(1)).publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class));
        jdbc.update("UPDATE gf_property_publish_job SET status='PROCESSING',update_time=? WHERE id=?", LocalDateTime
            .now()
            .minusMinutes(16), id);
        service.maintain();
        assertThat(status(id)).isEqualTo("UNKNOWN");
    }

    @Test
    void futureJobsWaitAndKnownFailuresRequireAnExplicitVersionedRetry() {
        long account = account("api", true);
        long id = Long.parseLong(((List<String>)service.create(new PropertyPublishingService.Create(100L, List
            .of(account), null, null, null, LocalDateTime.now().plusDays(1).toString(), "future"), 9).get("ids"))
            .get(0));
        action(id, "APPROVE");
        service.maintain();
        assertThat(status(id)).isEqualTo("QUEUED");
        jdbc.update("UPDATE gf_property_publish_job SET scheduled_at=? WHERE id=?", LocalDateTime.now()
            .minusMinutes(1), id);
        when(gateway.publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class)))
            .thenReturn(new PublishingGateway.Result("FAILED", null, "图片上传失败"));
        service.maintain();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThatThrownBy(() -> service.action(id, new PropertyPublishingService.Action(0, "RETRY", null, null), 9))
            .hasMessageContaining("任务已更新");
        action(id, "RETRY");
        assertThat(status(id)).isEqualTo("QUEUED");
    }

    @Test
    void automaticDraftsOnlyCoverNewEligiblePropertiesAndNeverBypassApproval() {
        long account = account("manual", false);
        service.settings(new PropertyPublishingService.Settings(true, List.of(account)));
        jdbc.update("UPDATE gf_property SET create_time=? WHERE id=100", LocalDateTime.now().plusSeconds(1));
        service.maintain();
        service.maintain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gf_property_publish_job", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM gf_property_publish_job", String.class)).isEqualTo("DRAFT");
        verifyNoInteractionsOnPublish();
    }

    void verifyNoInteractionsOnPublish() {
        verify(gateway, never()).publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class));
    }

    @Test
    void disabledAccountsAndDuplicateCredentialAliasesAreRejected() {
        long account = account("api", true);
        assertThatThrownBy(() -> service
            .saveAccount(null, new PropertyPublishingService.Account("DOUYIN", "alias", "alias", "DOUYIN_API", "api", true)))
            .hasMessageContaining("一个启用账号");
        long job = create(account, "disabled");
        action(job, "APPROVE");
        service
            .saveAccount(account, new PropertyPublishingService.Account("DOUYIN", "api", "api", "DOUYIN_API", "api", false));
        service.maintain();
        assertThat(status(job)).isEqualTo("REJECTED");
        verifyNoInteractionsOnPublish();
    }

    @Test
    void aBadAutomaticDraftDoesNotStarveApprovedJobs() {
        long account = account("api", true), job = create(account, "approved");
        action(job, "APPROVE");
        service.settings(new PropertyPublishingService.Settings(true, List.of(account)));
        jdbc.update("INSERT INTO gf_property SELECT 101,title,city,district,community,layout,area_sqm,price,price_type,price_unit,'bad-json',status,deleted,? FROM gf_property WHERE id=100", LocalDateTime
            .now()
            .plusSeconds(1));
        service.maintain();
        assertThat(status(job)).isEqualTo("SUBMITTED");
    }

    @Test
    void exhaustedAttemptsCanBeCorrectedAndManualAccountsCanUpgradeAfterClosingTasks() {
        long account = Long.parseLong(service
            .saveAccount(null, new PropertyPublishingService.Account("DOUYIN", "same", "same", "MANUAL", null, true)));
        long job = create(account, "upgrade");
        var connected = new PropertyPublishingService.Account("DOUYIN", "same", "same", "DOUYIN_API", "new-key", true);
        assertThatThrownBy(() -> service.saveAccount(account, connected)).hasMessageContaining("未完成");
        action(job, "CANCEL");
        service.saveAccount(account, connected);
        assertThat(service.accounts().get(0).get("mode")).isEqualTo("DOUYIN_API");
        jdbc.update("UPDATE gf_property_publish_job SET status='FAILED',attempts=5 WHERE id=?", job);
        assertThatThrownBy(() -> action(job, "RETRY")).hasMessageContaining("5次");
        service.edit(job, new PropertyPublishingService.Edit(version(job), "已核对房源", "更新后的内容", List
            .of("https://images.example.com/house.jpg"), null), 9);
        assertThat(jdbc.queryForObject("SELECT attempts FROM gf_property_publish_job WHERE id=?", Integer.class, job))
            .isZero();
        action(job, "APPROVE");
        assertThat(status(job)).isEqualTo("QUEUED");
    }

    @Test
    void twoWorkersCannotSubmitTheSameJobAndRecheckBeforeRemoteCreate() throws Exception {
        long job = create(account("api", true), "concurrent");
        action(job, "APPROVE");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class)))
            .thenAnswer(i -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return new PublishingGateway.Result(((BooleanSupplier)i.getArgument(4)).getAsBoolean()
                    ? "SUBMITTED"
                    : "FAILED", null, "房源重新校验");
            });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(service::maintain);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(service::maintain).get(5, TimeUnit.SECONDS);
            jdbc.update("UPDATE gf_property SET status=3 WHERE id=100");
            release.countDown();
            one.get(5, TimeUnit.SECONDS);
            assertThat(status(job)).isEqualTo("FAILED");
            verify(gateway, times(1))
                .publish(anyString(), anyString(), anyString(), anyList(), any(BooleanSupplier.class));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
