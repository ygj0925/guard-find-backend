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

package com.guardfind.backend.oa;

import cn.idev.excel.FastExcel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.model.OaCalendarEventReq;
import com.guardfind.backend.oa.model.OaExportCreateReq;
import com.guardfind.backend.oa.model.OaExportTaskResp;
import com.guardfind.backend.oa.model.OaStatsResp;
import com.guardfind.backend.oa.model.OaStatsTypeRow;
import com.guardfind.backend.oa.model.OaWorkbenchResp;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaCollabRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaNotificationRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.repository.OaStatsRepository;
import com.guardfind.backend.oa.service.OaCalendarService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaExportService;
import com.guardfind.backend.oa.service.OaNotificationService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.service.OaStatsService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.starter.core.exception.BusinessException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 16.1 工作台汇总、统计口径、异步导出与脱敏。
 */
class OaStatsExportTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long OTHER_TENANT = 202;
    private static final long ALICE = 101;
    private static final long BOB = 102;
    private static final long CAROL = 103;
    private static final long OLGA = 201;

    private static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:flowable="http://flowable.org/bpmn"
                          targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="oaApproval" name="单级审批" isExecutable="true">
            <bpmn:startEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="manager"/>
            <bpmn:userTask id="manager" name="主管审批" flowable:assignee="${manager}"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="manager" targetRef="end"/>
            <bpmn:endEvent id="end"/>
          </bpmn:process>
        </bpmn:definitions>
        """;

    @TempDir
    Path exportDir;

    @Test
    void workbenchSummarizesOnlyCurrentUserData() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            LocalDateTime now = LocalDateTime.now();
            String first = fixture.startProcess(TENANT, BOB);
            String second = fixture.startProcess(TENANT, BOB);
            fixture.backdate(first, 30);
            fixture.app(1, TENANT, "EXPENSE", "差旅报销", ALICE, "研发部", "RUNNING", now.minusHours(30), null, first);
            fixture.app(2, TENANT, "LEAVE", "年假", ALICE, "研发部", "RUNNING", now.minusHours(2), null, second);
            fixture.app(3, TENANT, "GENERIC", "通用", ALICE, "研发部", "APPROVED", now.minusDays(2), now.minusDays(1), null);
            fixture.app(4, TENANT, "GENERIC", "草稿", ALICE, "研发部", "DRAFT", null, null, null);
            fixture.jdbc.update("""
                INSERT INTO oa_notification (tenant_id, user_id, type, title, dedup_key, status, create_time)
                VALUES (101, 102, 'TODO', '待审批', 'n1', 'UNREAD', ?), (101, 102, 'TODO', '已读', 'n2', 'READ', ?)
                """, now, now);
            fixture.jdbc.update("""
                INSERT INTO oa_cc_receipt (tenant_id, application_id, receiver_id, status, create_time)
                VALUES (101, 1, 102, 'UNREAD', ?), (101, 2, 102, 'READ', ?)
                """, now, now);
            LocalDate today = now.toLocalDate();
            fixture.calendar.create(TENANT, BOB, false, new OaCalendarEventReq("ev-1", "周会", "A101", null, today
                .atTime(9, 0), today.atTime(10, 0), false, "PERSONAL", null, null, List.of(), null));
            fixture.calendar.create(TENANT, BOB, false, new OaCalendarEventReq("ev-2", "明天", null, null, today
                .plusDays(1)
                .atTime(9, 0), today.plusDays(1).atTime(10, 0), false, "PERSONAL", null, null, List.of(), null));
            fixture.jdbc
                .update("INSERT INTO oa_meeting_room (id, tenant_id, code, name, capacity, create_user, create_time) VALUES (1, 101, 'R1', '一号会议室', 10, 1, ?)", now);
            fixture.jdbc
                .update("""
                    INSERT INTO oa_meeting_booking (tenant_id, room_id, booker_id, subject, start_time, end_time, attendees, status, create_time)
                    VALUES (101, 1, 102, '评审会', ?, ?, 5, 'BOOKED', ?), (101, 1, 102, '已取消', ?, ?, 5, 'CANCELLED', ?)
                    """, now
                    .plusDays(1), now.plusDays(1).plusHours(1), now, now.plusDays(1), now.plusDays(1)
                        .plusHours(1), now);
            fixture.vehicleRequest(1, TENANT, BOB, "DISPATCHED", now.plusHours(3), null, null);
            fixture.vehicleRequest(2, TENANT, BOB, "RETURNED", now.minusDays(1), null, null);
            fixture.jdbc
                .update("""
                    INSERT INTO oa_work_report (tenant_id, author_id, report_type, period_start, period_end, done_content, status, create_time)
                    VALUES (101, 102, 'DAILY', ?, ?, '完成评审', 'SUBMITTED', ?)
                    """, today, today, now);

            OaWorkbenchResp bob = fixture.stats.workbench(TENANT, BOB);
            assertThat(bob.todoCount()).isEqualTo(2);
            assertThat(bob.overdueTodoCount()).isEqualTo(1);
            assertThat(bob.overdueHours()).isEqualTo(24);
            assertThat(bob.runningCount()).isZero();
            assertThat(bob.unreadNotificationCount()).isEqualTo(1);
            assertThat(bob.unreadCcCount()).isEqualTo(1);
            assertThat(bob.dailyReportSubmitted()).isTrue();
            assertThat(bob.recentApplications()).isEmpty();
            assertThat(bob.todayEvents()).extracting(item -> item.title()).containsExactly("周会");
            assertThat(bob.upcomingMeetings()).extracting(item -> item.title()).containsExactly("评审会");
            assertThat(bob.upcomingMeetings().get(0).subtitle()).isEqualTo("一号会议室");
            assertThat(bob.activeVehicleRequests()).extracting(item -> item.id()).containsExactly(1L);

            OaWorkbenchResp alice = fixture.stats.workbench(TENANT, ALICE);
            assertThat(alice.todoCount()).isZero();
            assertThat(alice.runningCount()).isEqualTo(2);
            assertThat(alice.unreadCcCount()).isZero();
            assertThat(alice.dailyReportSubmitted()).isFalse();
            assertThat(alice.recentApplications()).extracting(item -> item.id()).containsExactly(3L, 2L, 1L);
            assertThat(alice.todayEvents()).isEmpty();

            OaWorkbenchResp otherTenant = fixture.stats.workbench(OTHER_TENANT, BOB);
            assertThat(otherTenant.todoCount()).isZero();
            assertThat(otherTenant.unreadNotificationCount()).isZero();
            assertThat(otherTenant.upcomingMeetings()).isEmpty();
        });
    }

    @Test
    void overviewAggregatesByTypeDeptAndOverdueWithinTenantRange() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(10);
            LocalDateTime day = from.plusDays(1).atTime(10, 0);
            String running = fixture.startProcess(TENANT, BOB);
            fixture.backdate(running, 30);
            fixture.startProcess(TENANT, CAROL);
            fixture.startProcess(OTHER_TENANT, OLGA);
            fixture.app(1, TENANT, "EXPENSE", "报销一", ALICE, "研发部", "APPROVED", day, day.plusHours(2), null);
            fixture.app(2, TENANT, "EXPENSE", "报销二", ALICE, "研发部", "REJECTED", day, day.plusHours(4), null);
            fixture.app(3, TENANT, "LOAN", "借款", CAROL, "市场部", "RUNNING", day, null, running);
            fixture.app(4, TENANT, "GENERIC", "通用", CAROL, null, "WITHDRAWN", day, null, null);
            fixture.app(5, TENANT, "EXPENSE", "区间外", ALICE, "研发部", "APPROVED", from.minusDays(1)
                .atTime(10, 0), day, null);
            fixture.app(6, TENANT, "EXPENSE", "草稿", ALICE, "研发部", "DRAFT", day, null, null);
            fixture.app(7, OTHER_TENANT, "EXPENSE", "他租户", OLGA, "外部", "APPROVED", day, day.plusHours(1), null);
            fixture.jdbc.update("""
                INSERT INTO oa_expense (tenant_id, application_id, total_amount) VALUES
                  (101, 1, 100.50), (101, 2, 50.00), (101, 5, 999.00), (101, 6, 888.00), (202, 7, 777.00)
                """);
            fixture.jdbc.update("INSERT INTO oa_loan (tenant_id, application_id, amount) VALUES (101, 3, 200.00)");

            OaStatsResp stats = fixture.stats.overview(TENANT, from, to, null);
            OaStatsTypeRow total = stats.total();
            assertThat(total.total()).isEqualTo(4);
            assertThat(total.approved()).isEqualTo(1);
            assertThat(total.rejected()).isEqualTo(1);
            assertThat(total.running()).isEqualTo(1);
            assertThat(total.withdrawn()).isEqualTo(1);
            assertThat(total.avgHours()).isEqualTo(3.0);
            assertThat(total.requestedAmount()).isEqualByComparingTo("350.50");
            assertThat(total.approvedAmount()).isEqualByComparingTo("100.50");
            assertThat(stats.byType()).extracting(OaStatsTypeRow::businessType)
                .containsExactly("EXPENSE", "GENERIC", "LOAN");
            OaStatsTypeRow expense = stats.byType().get(0);
            assertThat(expense.total()).isEqualTo(2);
            assertThat(expense.avgHours()).isEqualTo(3.0);
            assertThat(expense.requestedAmount()).isEqualByComparingTo("150.50");
            assertThat(stats.byType().get(2).requestedAmount()).isEqualByComparingTo("200.00");
            assertThat(stats.byType().get(1).avgHours()).isNull();
            assertThat(stats.byDept()).extracting(row -> row.deptName()).containsExactly("研发部", "市场部", "未分配部门");
            assertThat(stats.byDept().get(0).approved()).isEqualTo(1);
            assertThat(stats.byDept().get(0).approvedAmount()).isEqualByComparingTo(new BigDecimal("100.50"));

            assertThat(stats.overdueTaskCount()).isEqualTo(1);
            assertThat(stats.overdueTasks()).hasSize(1);
            assertThat(stats.overdueTasks().get(0).applicationId()).isEqualTo(3L);
            assertThat(stats.overdueTasks().get(0).title()).isEqualTo("借款");
            assertThat(stats.overdueTasks().get(0).assigneeName()).isEqualTo("Bob");
            assertThat(stats.overdueTasks().get(0).pendingHours()).isGreaterThanOrEqualTo(29);

            OaStatsResp filtered = fixture.stats.overview(TENANT, from, to, "EXPENSE");
            assertThat(filtered.total().total()).isEqualTo(2);
            assertThat(filtered.byType()).hasSize(1);
            assertThat(filtered.byDept()).extracting(row -> row.deptName()).containsExactly("研发部");

            OaStatsResp other = fixture.stats.overview(OTHER_TENANT, from, to, null);
            assertThat(other.total().total()).isEqualTo(1);
            assertThat(other.total().approvedAmount()).isEqualByComparingTo("777.00");
            assertThat(other.overdueTaskCount()).isZero();

            assertThatThrownBy(() -> fixture.stats.overview(TENANT, to, from, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能早于");
            assertThatThrownBy(() -> fixture.stats.overview(TENANT, to.minusDays(400), to, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("366");
            assertThatThrownBy(() -> fixture.stats.overview(TENANT, null, to, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("统计区间");
        });
    }

    @Test
    void exportIsIdempotentLimitedAndMasksDriverPhone() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(5);
            LocalDateTime day = from.plusDays(1).atTime(10, 0);
            fixture.app(1, TENANT, "EXPENSE", "报销一", ALICE, "研发部", "APPROVED", day, day.plusHours(2), null);
            fixture.app(2, TENANT, "LEAVE", "年假", ALICE, "研发部", "RUNNING", day, null, null);
            fixture.app(3, OTHER_TENANT, "EXPENSE", "他租户", OLGA, "外部", "APPROVED", day, day.plusHours(1), null);
            fixture.jdbc
                .update("INSERT INTO oa_expense (tenant_id, application_id, total_amount) VALUES (101, 1, 88.80)");
            fixture.jdbc.update("""
                INSERT INTO oa_vehicle (id, tenant_id, plate_no, model, seats, create_user, create_time)
                VALUES (1, 101, '粤A12345', '轿车', 5, 1, ?)
                """, LocalDateTime.now());
            fixture.jdbc.update("""
                INSERT INTO oa_vehicle_driver (id, tenant_id, name, phone, create_user, create_time)
                VALUES (1, 101, '张师傅', '13812345678', 1, ?)
                """, LocalDateTime.now());
            fixture.vehicleRequest(1, TENANT, BOB, "RETURNED", day, 1L, 1L);
            OaExportService export = fixture.export(50000);

            OaExportTaskResp created = export.create(TENANT, ALICE, req("r1", "APPLICATION", from, to, null));
            assertThat(created.status()).isEqualTo("PENDING");
            assertThat(export.create(TENANT, ALICE, req("r1", "APPLICATION", from, to, null)).id()).isEqualTo(created
                .id());
            assertThatThrownBy(() -> export.create(TENANT, ALICE, req("r1", "APPLICATION", from, to, "EXPENSE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请求号已用于其他导出");
            assertThatThrownBy(() -> export.create(TENANT, ALICE, req("r9", "USER", from, to, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持");
            assertThatThrownBy(() -> export.create(TENANT, ALICE, req("r9", "APPLICATION", to, from, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能早于");
            OaExportTaskResp expenseOnly = export.create(TENANT, ALICE, req("r2", "APPLICATION", from, to, "EXPENSE"));
            OaExportTaskResp vehicle = export.create(TENANT, ALICE, req("r3", "VEHICLE_USE", from, to, "EXPENSE"));
            assertThat(vehicle.businessType()).isNull();
            assertThatThrownBy(() -> export.create(TENANT, ALICE, req("r4", "APPLICATION", from, to, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("排队");
            assertThat(export.create(TENANT, CAROL, req("r4", "APPLICATION", from, to, null)).status())
                .isEqualTo("PENDING");

            assertThat(export.runPending()).isEqualTo(4);
            assertThat(export.runPending()).isZero();
            List<OaExportTaskResp> mine = export.mine(TENANT, ALICE);
            assertThat(mine).extracting(OaExportTaskResp::status).containsOnly("DONE");
            assertThat(mine).extracting(OaExportTaskResp::id)
                .containsExactly(vehicle.id(), expenseOnly.id(), created.id());
            assertThat(mine.get(2).rowCount()).isEqualTo(2);
            assertThat(mine.get(1).rowCount()).isEqualTo(1);
            assertThat(mine.get(0).rowCount()).isEqualTo(1);
            assertThat(mine.get(2).fileName()).startsWith("申请明细_" + from + "_" + to + "_").endsWith(".xlsx");
            assertThat(mine.get(2).expireTime()).isAfter(LocalDateTime.now().plusHours(23));

            OaExportService.DownloadFile applications = export.authorizeDownload(TENANT, ALICE, created.id());
            assertThat(applications.path()).startsWith(exportDir.toAbsolutePath().normalize());
            List<Map<Integer, String>> appRows = read(applications.path());
            assertThat(appRows).hasSize(2);
            assertThat(appRows.get(0).get(1)).isEqualTo("报销");
            assertThat(appRows.get(0).get(6)).isEqualTo("已通过");
            assertThat(appRows.get(0).get(9)).isEqualTo("2.0");
            assertThat(appRows.get(0).get(10)).isEqualTo("88.8");
            assertThat(appRows).noneMatch(row -> row.containsValue("他租户"));

            OaExportService.DownloadFile vehicles = export.authorizeDownload(TENANT, ALICE, vehicle.id());
            List<Map<Integer, String>> vehicleRows = read(vehicles.path());
            assertThat(vehicleRows).hasSize(1);
            assertThat(vehicleRows.get(0).get(1)).isEqualTo("Bob");
            assertThat(vehicleRows.get(0).get(7)).isEqualTo("已归还");
            assertThat(vehicleRows.get(0).get(8)).isEqualTo("粤A12345");
            assertThat(vehicleRows.get(0).get(10)).isEqualTo("138****5678");
            assertThat(vehicleRows).noneMatch(row -> row.containsValue("13812345678"));

            assertThatThrownBy(() -> export.authorizeDownload(TENANT, CAROL, created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权下载");
            assertThatThrownBy(() -> export.authorizeDownload(OTHER_TENANT, ALICE, created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权下载");

            fixture.jdbc.update("UPDATE oa_export_task SET expire_time = ? WHERE id = ?", Timestamp
                .valueOf(LocalDateTime.now().minusMinutes(1)), created.id());
            assertThatThrownBy(() -> export.authorizeDownload(TENANT, ALICE, created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期");
            assertThat(export.purgeExpired()).isEqualTo(1);
            assertThat(Files.exists(applications.path())).isFalse();
            assertThat(Files.exists(vehicles.path())).isTrue();
            assertThat(fixture.jdbc
                .queryForObject("SELECT status FROM oa_export_task WHERE id = ?", String.class, created.id()))
                .isEqualTo("EXPIRED");
            assertThatThrownBy(() -> export.authorizeDownload(TENANT, ALICE, created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已过期");
        });
    }

    @Test
    void exportFailsWhenRowsExceedLimitAndRejectsTamperedPath() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(5);
            LocalDateTime day = from.plusDays(1).atTime(10, 0);
            fixture.app(1, TENANT, "EXPENSE", "报销一", ALICE, "研发部", "APPROVED", day, day.plusHours(2), null);
            fixture.app(2, TENANT, "EXPENSE", "报销二", ALICE, "研发部", "APPROVED", day, day.plusHours(2), null);
            OaExportService export = fixture.export(1);

            OaExportTaskResp tooMany = export.create(TENANT, ALICE, req("big", "APPLICATION", from, to, null));
            OaExportTaskResp pending = export.create(TENANT, ALICE, req("pending", "APPLICATION", from, to, null));
            assertThatThrownBy(() -> export.authorizeDownload(TENANT, ALICE, pending.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未生成");
            export.runPending();
            OaExportTaskResp failed = export.mine(TENANT, ALICE)
                .stream()
                .filter(task -> task.id() == tooMany.id())
                .findFirst()
                .orElseThrow();
            assertThat(failed.status()).isEqualTo("FAILED");
            assertThat(failed.errorMessage()).contains("超过上限 1");
            assertThat(export.create(TENANT, ALICE, req("small", "APPLICATION", from, from.plusDays(1), null)).status())
                .isEqualTo("PENDING");

            fixture.jdbc
                .update("""
                    UPDATE oa_export_task SET status = 'DONE', file_name = 'x.xlsx', file_path = '../outside.xlsx', expire_time = ?
                     WHERE id = ?
                    """, Timestamp
                    .valueOf(LocalDateTime.now().plusHours(1)), tooMany.id());
            Files.writeString(exportDir.resolve("outside.xlsx"), "secret");
            assertThatThrownBy(() -> export.authorizeDownload(TENANT, ALICE, tooMany.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或已被清理");
        });
    }

    private static OaExportCreateReq req(String requestId,
                                         String type,
                                         LocalDate from,
                                         LocalDate to,
                                         String businessType) {
        return new OaExportCreateReq(requestId, type, from, to, businessType);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<Integer, String>> read(Path file) {
        List<?> rows = FastExcel.read(file.toFile()).sheet().headRowNumber(1).doReadSync();
        return (List<Map<Integer, String>>)rows;
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-stats-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        createTables(jdbc);
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, parent_id) VALUES (1, 101, '总部', 0), (10, 101, '研发部', 1), (20, 101, '市场部', 1), (90, 202, '外部', 0)");
        user(jdbc, ALICE, TENANT, "alice", "Alice", 10L);
        user(jdbc, BOB, TENANT, "bob", "Bob", 10L);
        user(jdbc, CAROL, TENANT, "carol", "Carol", 20L);
        user(jdbc, OLGA, OTHER_TENANT, "olga", "Olga", 90L);
        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        for (long tenant : new long[] {TENANT, OTHER_TENANT}) {
            repositoryService.createDeployment()
                .name("oa-stats")
                .tenantId(Long.toString(tenant))
                .addString("oa-stats.bpmn20.xml", BPMN)
                .deploy();
        }
        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        HistoryService historyService = context.getBean(HistoryService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, historyService, repositoryService);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaNotificationService notificationService = new OaNotificationService(new OaNotificationRepository(jdbc), objectMapper);
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), new OaLaunchRepository(jdbc), outboxService);
        OaCalendarService calendar = new OaCalendarService(new OaCollabRepository(jdbc), outboxService, transactionManager);
        OaStatsRepository statsRepository = new OaStatsRepository(jdbc);
        OaStatsService stats = new OaStatsService(statsRepository, engine, notificationService, ccService, calendar, 24);
        return new Fixture(jdbc, runtimeService, taskService, calendar, stats, statsRepository, exportDir);
    }

    private static void user(JdbcTemplate jdbc, long id, long tenantId, String username, String nickname, Long deptId) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (?, ?, ?, ?, ?, 1, 0)", id, tenantId, username, nickname, deptId);
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, employment_status, deleted) VALUES (?, ?, ?, 'ACTIVE', 0)", id, tenantId, id);
    }

    private static void createTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL, nickname VARCHAR(64), dept_id BIGINT, status INT NOT NULL, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE sys_dept (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, parent_id BIGINT NOT NULL DEFAULT 0, deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE oa_employee (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL, supervisor_id BIGINT, dept_leader BOOLEAN NOT NULL DEFAULT FALSE, employment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', deleted BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("""
            CREATE TABLE oa_outbox (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              event_id VARCHAR(64) NOT NULL, aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(128) NOT NULL,
              event_type VARCHAR(64) NOT NULL, payload CLOB NOT NULL, dedup_key VARCHAR(160),
              status VARCHAR(20) NOT NULL DEFAULT 'PENDING', retry_count INT NOT NULL DEFAULT 0,
              next_retry_time TIMESTAMP, last_error VARCHAR(1000), create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP, sent_time TIMESTAMP,
              CONSTRAINT uk_event_id UNIQUE (event_id), CONSTRAINT uk_outbox_dedup UNIQUE (tenant_id, dedup_key)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_calendar_event (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
              request_id VARCHAR(64), scope VARCHAR(20) NOT NULL, dept_id BIGINT, title VARCHAR(200) NOT NULL,
              location VARCHAR(200), description VARCHAR(2000), start_time TIMESTAMP NOT NULL, end_time TIMESTAMP NOT NULL,
              all_day BOOLEAN NOT NULL DEFAULT FALSE, remind_minutes INT, remind_at TIMESTAMP, reminded_at TIMESTAMP,
              status VARCHAR(20) NOT NULL, version INT NOT NULL DEFAULT 0, create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP, CONSTRAINT uk_event_request UNIQUE (tenant_id, owner_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_calendar_member (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, event_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_event_member UNIQUE (tenant_id, event_id, user_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_work_report (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
              dept_id BIGINT, report_type VARCHAR(20) NOT NULL, period_start DATE NOT NULL, period_end DATE NOT NULL,
              done_content VARCHAR(5000) NOT NULL, plan_content VARCHAR(5000), issue_content VARCHAR(2000),
              status VARCHAR(20) NOT NULL, submit_time TIMESTAMP, version INT NOT NULL DEFAULT 0,
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_notification (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, title VARCHAR(200) NOT NULL, content VARCHAR(1000),
              application_id BIGINT, task_id VARCHAR(64), dedup_key VARCHAR(160) NOT NULL,
              status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', read_time TIMESTAMP, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_notification_dedup UNIQUE (tenant_id, user_id, dedup_key)
            )
            """);
        jdbc.execute("CREATE TABLE oa_cc_receipt (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT NOT NULL, process_instance_id VARCHAR(64), receiver_id BIGINT NOT NULL, receiver_name VARCHAR(64), source_task_id VARCHAR(64), cc_by BIGINT, status VARCHAR(20) NOT NULL DEFAULT 'UNREAD', read_time TIMESTAMP, revoke_by BIGINT, revoke_time TIMESTAMP, create_time TIMESTAMP NOT NULL, CONSTRAINT uk_cc UNIQUE (tenant_id, application_id, receiver_id))");
        jdbc.execute("""
            CREATE TABLE oa_application (
              id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL,
              business_type VARCHAR(64) NOT NULL, business_key VARCHAR(128) NOT NULL, title VARCHAR(200) NOT NULL,
              applicant_id BIGINT NOT NULL, applicant_name VARCHAR(64), applicant_dept_id BIGINT,
              applicant_dept_name VARCHAR(100), release_id BIGINT NOT NULL, form_version_id BIGINT,
              form_version INT, form_data CLOB, process_instance_id VARCHAR(64), status VARCHAR(20) NOT NULL,
              submitted_time TIMESTAMP, finished_time TIMESTAMP, version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              deleted BIGINT NOT NULL DEFAULT 0
            )
            """);
        jdbc.execute("CREATE TABLE oa_process_model (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, business_type VARCHAR(32))");
        jdbc.execute("CREATE TABLE oa_process_release (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, model_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE oa_expense (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT NOT NULL, total_amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE oa_loan (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT NOT NULL, amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE oa_purchase (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT, total_amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE oa_contract_version (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT, amount DECIMAL(14,2))");
        jdbc.execute("CREATE TABLE oa_travel (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id BIGINT, budget_amount DECIMAL(14,2))");
        OaMeetingVehicleServiceTest.createResourceTables(jdbc);
        jdbc.execute("""
            CREATE TABLE oa_export_task (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
              request_id VARCHAR(64) NOT NULL, export_type VARCHAR(32) NOT NULL, date_from DATE NOT NULL,
              date_to DATE NOT NULL, business_type VARCHAR(64), status VARCHAR(20) NOT NULL, file_name VARCHAR(200),
              file_path VARCHAR(500), row_count INT, error_message VARCHAR(500), start_time TIMESTAMP,
              finish_time TIMESTAMP, expire_time TIMESTAMP, create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_tenant_user_request UNIQUE (tenant_id, user_id, request_id)
            )
            """);
    }

    private record Fixture(JdbcTemplate jdbc, RuntimeService runtimeService, TaskService taskService,
                           OaCalendarService calendar, OaStatsService stats, OaStatsRepository statsRepository,
                           Path exportDir) {

        String startProcess(long tenant, long assignee) {
            return runtimeService.startProcessInstanceByKeyAndTenantId("oaApproval", Map.of("manager", Long
                .toString(assignee)), Long.toString(tenant)).getId();
        }

        void backdate(String processInstanceId, int hours) {
            Task task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
            jdbc.update("UPDATE ACT_RU_TASK SET CREATE_TIME_ = ? WHERE ID_ = ?", Timestamp.valueOf(LocalDateTime.now()
                .minusHours(hours)), task.getId());
        }

        void app(long id,
                 long tenant,
                 String type,
                 String title,
                 long applicant,
                 String deptName,
                 String status,
                 LocalDateTime submitted,
                 LocalDateTime finished,
                 String processInstanceId) {
            long releaseId = Math.abs((tenant + ":" + type).hashCode());
            jdbc.update("MERGE INTO oa_process_model KEY (id) VALUES (?, ?, ?)", releaseId, tenant, "GENERIC"
                .equals(type) ? null : type);
            jdbc.update("MERGE INTO oa_process_release KEY (id) VALUES (?, ?, ?)", releaseId, tenant, releaseId);
            jdbc.update("""
                INSERT INTO oa_application
                  (id, tenant_id, business_type, business_key, title, applicant_id, applicant_name, applicant_dept_name,
                   release_id, form_data, process_instance_id, status, submitted_time, finished_time, create_user, create_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{"idCard":"440101199001011234"}', ?, ?, ?, ?, ?, ?)
                """, id, tenant, type
                .toLowerCase() + "_flow", "BK-" + id, title, applicant, applicant == ALICE
                    ? "Alice"
                    : applicant == CAROL
                        ? "Carol"
                        : "Olga", deptName, releaseId, processInstanceId, status, submitted, finished, applicant, LocalDateTime
                            .now());
        }

        void vehicleRequest(long id,
                            long tenant,
                            long applicant,
                            String status,
                            LocalDateTime start,
                            Long vehicleId,
                            Long driverId) {
            jdbc.update("""
                INSERT INTO oa_vehicle_request
                  (id, tenant_id, application_id, applicant_id, purpose, destination, passengers, start_time, end_time,
                   status, vehicle_id, driver_id, start_mileage, end_mileage, fuel_cost, create_time)
                VALUES (?, ?, ?, ?, '客户拜访', '天河', 2, ?, ?, ?, ?, ?, 100, 160, 45.50, ?)
                """, id, tenant, 900 + id, applicant, start, start
                .plusHours(4), status, vehicleId, driverId, LocalDateTime.now());
        }

        OaExportService export(int maxRows) {
            return new OaExportService(statsRepository, exportDir.resolve("exports").toString(), maxRows, 24);
        }
    }
}
