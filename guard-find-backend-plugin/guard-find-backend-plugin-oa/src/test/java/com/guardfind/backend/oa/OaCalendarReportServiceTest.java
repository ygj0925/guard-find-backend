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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaCalendarEventReq;
import com.guardfind.backend.oa.model.OaCalendarEventResp;
import com.guardfind.backend.oa.model.OaWorkReportCommentReq;
import com.guardfind.backend.oa.model.OaWorkReportDetailResp;
import com.guardfind.backend.oa.model.OaWorkReportMemberStat;
import com.guardfind.backend.oa.model.OaWorkReportRemindReq;
import com.guardfind.backend.oa.model.OaWorkReportResp;
import com.guardfind.backend.oa.model.OaWorkReportSaveReq;
import com.guardfind.backend.oa.model.OaWorkReportSummaryResp;
import com.guardfind.backend.oa.repository.OaCollabRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.service.OaCalendarService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.service.OaWorkReportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 15.3 日程与工作报告：部门日程按部门树可见、提醒只发一次，报告提交后只读，团队范围按负责部门与主管链计算。
 */
class OaCalendarReportServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long OTHER_TENANT = 202;
    private static final long ALICE = 101;
    private static final long BOB = 102;
    private static final long CAROL = 103;
    private static final long DAVE = 104;
    private static final long EVE = 105;
    private static final long OUTSIDER = 201;

    @Test
    void calendarVisibilityFollowsMembersAndDeptTree() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaCalendarService service = fixture.calendar;
            LocalDateTime start = LocalDate.now().plusDays(1).atTime(9, 0);

            OaCalendarEventResp personal = service.create(TENANT, ALICE, false, req("r-1", "评审会", start, start
                .plusHours(1), null, null, null, List.of(CAROL, ALICE), null));
            assertThat(personal.scope()).isEqualTo(OaCalendarService.SCOPE_PERSONAL);
            assertThat(personal.members()).extracting("userId").containsExactly(CAROL);
            assertThat(service.create(TENANT, ALICE, false, req("r-1", "重复提交", start, start
                .plusHours(2), null, null, null, null, null)).id()).isEqualTo(personal.id());
            assertThat(fixture.outbox("CALENDAR_INVITED:" + personal.id() + ":" + CAROL))
                .contains("\"recipients\":[" + CAROL + "]");

            assertThat(ids(service.events(TENANT, CAROL, start.minusDays(1), start.plusDays(1))))
                .containsExactly(personal.id());
            assertThat(ids(service.events(TENANT, DAVE, start.minusDays(1), start.plusDays(1)))).isEmpty();
            assertThatThrownBy(() -> service.detail(TENANT, DAVE, personal.id())).hasMessageContaining("无权查看");

            assertThatThrownBy(() -> service.create(TENANT, ALICE, false, req("r-2", "部门会", start, start
                .plusHours(1), "DEPT", 10L, null, null, null))).hasMessageContaining("没有发布部门日程的权限");
            assertThatThrownBy(() -> service.create(TENANT, ALICE, true, req("r-2", "部门会", start, start
                .plusHours(1), "DEPT", 20L, null, null, null))).hasMessageContaining("只能向本部门或下级部门");
            OaCalendarEventResp dept = service.create(TENANT, ALICE, true, req("r-2", "研发周会", start, start
                .plusHours(1), "DEPT", 10L, null, null, null));
            assertThat(dept.deptName()).isEqualTo("研发部");
            assertThat(service.publishableDepts(TENANT, ALICE)).extracting("id").containsExactly(10L, 11L);

            assertThat(ids(service.events(TENANT, BOB, start.minusDays(1), start.plusDays(1)))).containsExactly(dept
                .id());
            assertThat(service.detail(TENANT, BOB, dept.id()).title()).isEqualTo("研发周会");
            assertThat(ids(service.events(TENANT, CAROL, start.minusDays(1), start.plusDays(1))))
                .containsExactly(personal.id());
            assertThat(ids(service.events(TENANT, ALICE, start.minusDays(1), start.plusDays(1))))
                .containsExactlyInAnyOrder(personal.id(), dept.id());

            OaCalendarEventResp allDay = service.create(TENANT, BOB, false, req("r-3", "外出培训", start.plusHours(1), start
                .plusDays(1), null, null, null, null, true));
            assertThat(allDay.startTime()).isEqualTo(start.toLocalDate().atStartOfDay());
            assertThat(allDay.endTime()).isEqualTo(start.toLocalDate().plusDays(2).atStartOfDay());

            assertThatThrownBy(() -> service.update(TENANT, CAROL, false, personal.id(), req(null, "改", start, start
                .plusHours(1), null, null, null, null, null, personal.version()))).hasMessageContaining("无权操作");
            assertThatThrownBy(() -> service.update(TENANT, ALICE, false, personal.id(), req(null, "改", start, start
                .plusHours(1), null, null, null, null, null, personal.version() + 5))).hasMessageContaining("已被修改");
            assertThatThrownBy(() -> service.create(TENANT, ALICE, false, req("r-4", "倒挂", start, start
                .minusHours(1), null, null, null, null, null))).hasMessageContaining("结束时间必须晚于开始时间");
            assertThatThrownBy(() -> service.create(TENANT, ALICE, false, req("r-5", "离职成员", start, start
                .plusHours(1), null, null, null, List.of(OUTSIDER), null))).hasMessageContaining("成员不存在");
            assertThatThrownBy(() -> service.events(TENANT, ALICE, start, start.plusDays(70)))
                .hasMessageContaining("不能超过");

            OaCalendarEventResp updated = service.update(TENANT, ALICE, false, personal.id(), req(null, "评审会（改期）", start
                .plusHours(2), start.plusHours(3), null, null, null, List.of(CAROL, DAVE), null, personal.version()));
            assertThat(updated.title()).isEqualTo("评审会（改期）");
            assertThat(updated.members()).extracting("userId").containsExactly(CAROL, DAVE);
            assertThat(fixture.countOutbox("CALENDAR_INVITED:" + personal.id() + ":%")).isEqualTo(2);

            assertThat(service.events(OTHER_TENANT, OUTSIDER, start.minusDays(1), start.plusDays(1))).isEmpty();
            assertThatThrownBy(() -> service.detail(OTHER_TENANT, OUTSIDER, personal.id()))
                .hasMessageContaining("无权查看");

            OaCalendarEventResp cancelled = service.cancel(TENANT, ALICE, personal.id());
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(fixture.outbox("CALENDAR_CANCELLED:" + personal.id())).contains(String.valueOf(CAROL), String
                .valueOf(DAVE));
            assertThat(ids(service.events(TENANT, CAROL, start.minusDays(1), start.plusDays(1)))).isEmpty();
            assertThatThrownBy(() -> service.cancel(TENANT, ALICE, personal.id())).hasMessageContaining("日程已取消");
        });
    }

    @Test
    void reminderIsSentOncePerRemindTime() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaCalendarService service = fixture.calendar;
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
            LocalDateTime start = now.plusMinutes(60);

            OaCalendarEventResp event = service.create(TENANT, ALICE, false, req("rem-1", "客户拜访", start, start
                .plusHours(1), null, null, 15, List.of(BOB), null));
            assertThat(event.remindAt()).isEqualTo(start.minusMinutes(15));
            assertThat(service.remindDue(now)).isZero();
            assertThat(service.remindDue(now.plusMinutes(46))).isEqualTo(1);
            assertThat(service.remindDue(now.plusMinutes(47))).isZero();
            String payload = fixture.outbox("CALENDAR_REMIND:" + event.id() + ":" + event.remindAt());
            assertThat(payload).contains(String.valueOf(ALICE), String.valueOf(BOB));

            OaCalendarEventResp moved = service.update(TENANT, ALICE, false, event.id(), req(null, "客户拜访", start
                .plusHours(2), start.plusHours(3), null, null, 15, List.of(BOB), null, event.version()));
            assertThat(service.remindDue(now.plusMinutes(47))).isZero();
            assertThat(service.remindDue(now.plusMinutes(166))).isEqualTo(1);
            assertThat(fixture.countOutbox("CALENDAR_REMIND:" + moved.id() + ":%")).isEqualTo(2);

            OaCalendarEventResp late = service.create(TENANT, ALICE, false, req("rem-2", "马上开始", now.plusMinutes(5), now
                .plusMinutes(30), null, null, 30, null, null));
            assertThat(service.remindDue(now.plusMinutes(1))).isZero();
            assertThat(fixture.countOutbox("CALENDAR_REMIND:" + late.id() + ":%")).isZero();

            service.create(TENANT, ALICE, true, req("rem-3", "全员会", start, start
                .plusHours(1), "DEPT", 10L, 10, null, null));
            assertThat(service.remindDue(start.minusMinutes(9))).isEqualTo(1);
            assertThat(fixture.jdbc
                .queryForObject("SELECT payload FROM oa_outbox WHERE dedup_key LIKE 'CALENDAR_REMIND:%' ORDER BY id DESC LIMIT 1", String.class))
                .contains(String.valueOf(ALICE), String.valueOf(BOB), String.valueOf(EVE))
                .doesNotContain(String.valueOf(CAROL));
        });
    }

    @Test
    void reportDraftSubmitIsReadOnlyAndVisibleByScope() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaWorkReportService service = fixture.report;
            LocalDate today = LocalDate.now();

            OaWorkReportResp draft = service.save(TENANT, BOB, save("DAILY", today, "写接口", false, null));
            assertThat(draft.status()).isEqualTo("DRAFT");
            assertThat(draft.deptId()).isEqualTo(11L);
            assertThat(service.minePeriod(TENANT, BOB, "DAILY", today).id()).isEqualTo(draft.id());
            assertThat(service.team(TENANT, ALICE, false, null, null, null, null, 1, 20)).isEmpty();
            assertThatThrownBy(() -> service.detail(TENANT, ALICE, false, draft.id())).hasMessageContaining("无权查看");
            assertThatThrownBy(() -> service.save(TENANT, BOB, save("DAILY", today, "覆盖", false, null)))
                .hasMessageContaining("已有草稿");
            assertThatThrownBy(() -> service.save(TENANT, BOB, save("DAILY", today.plusDays(1), "未来", false, null)))
                .hasMessageContaining("未来周期");

            OaWorkReportResp submitted = service.save(TENANT, BOB, save("DAILY", today, "完成接口联调", true, draft
                .version()));
            assertThat(submitted.id()).isEqualTo(draft.id());
            assertThat(submitted.status()).isEqualTo("SUBMITTED");
            assertThat(submitted.late()).isFalse();
            assertThatThrownBy(() -> service.save(TENANT, BOB, save("DAILY", today, "再改", true, submitted.version())))
                .hasMessageContaining("已提交的报告不能修改");
            assertThatThrownBy(() -> service.deleteDraft(TENANT, BOB, submitted.id())).hasMessageContaining("不能删除");

            assertThat(service.team(TENANT, ALICE, false, "DAILY", today, null, null, 1, 20)).extracting("id")
                .containsExactly(submitted.id());
            assertThat(service.countTeam(TENANT, ALICE, false, "DAILY", today, 20L, null)).isZero();
            assertThat(service.team(TENANT, DAVE, false, null, null, null, null, 1, 20)).isEmpty();
            assertThatThrownBy(() -> service.detail(TENANT, DAVE, false, submitted.id())).hasMessageContaining("无权查看");
            assertThat(service.detail(TENANT, DAVE, true, submitted.id()).report().authorName()).isEqualTo("Bob");
            assertThatThrownBy(() -> service.detail(OTHER_TENANT, OUTSIDER, true, submitted.id()))
                .hasMessageContaining("无权查看");

            OaWorkReportDetailResp commented = service.comment(TENANT, ALICE, false, submitted
                .id(), new OaWorkReportCommentReq("联调记录补到文档"));
            assertThat(commented.comments()).extracting("content").containsExactly("联调记录补到文档");
            assertThat(commented.report().commentCount()).isEqualTo(1);
            assertThat(fixture.countOutbox("REPORT_COMMENTED:" + submitted.id() + ":%")).isEqualTo(1);
            assertThat(service.detail(TENANT, BOB, false, submitted.id()).comments()).hasSize(1);

            OaWorkReportResp lateReport = service.save(TENANT, BOB, save("DAILY", today
                .minusDays(1), "补交", true, null));
            assertThat(lateReport.late()).isTrue();

            OaWorkReportResp weekly = service.save(TENANT, EVE, save("WEEKLY", today, "本周总结", true, null));
            assertThat(weekly.periodStart()).isEqualTo(today.with(DayOfWeek.MONDAY));
            assertThat(weekly.periodEnd()).isEqualTo(today.with(DayOfWeek.MONDAY).plusDays(6));
            assertThat(service.mine(TENANT, BOB, null, 1, 20)).hasSize(2);
            assertThat(service.countMine(TENANT, BOB, "WEEKLY")).isZero();

            OaWorkReportResp eveDraft = service.save(TENANT, EVE, save("DAILY", today, "草稿", false, null));
            service.deleteDraft(TENANT, EVE, eveDraft.id());
            assertThat(service.minePeriod(TENANT, EVE, "DAILY", today)).isNull();
            assertThatThrownBy(() -> service.save(TENANT, OUTSIDER, save("DAILY", today, "外租户", false, null)))
                .hasMessageContaining("不是在职员工");
        });
    }

    @Test
    void summaryAndRemindUseTeamScope() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaWorkReportService service = fixture.report;
            LocalDate today = LocalDate.now();
            service.save(TENANT, BOB, save("DAILY", today, "完成", true, null));
            service.save(TENANT, EVE, save("DAILY", today, "未交草稿", false, null));

            OaWorkReportSummaryResp summary = service.summary(TENANT, ALICE, false, "DAILY", today, null);
            assertThat(summary.total()).isEqualTo(2);
            assertThat(summary.submitted()).isEqualTo(1);
            assertThat(summary.members()).extracting(OaWorkReportMemberStat::userId).containsExactly(EVE, BOB);
            assertThat(summary.members()).extracting(OaWorkReportMemberStat::status)
                .containsExactly("MISSING", "SUBMITTED");

            assertThat(service.remind(TENANT, ALICE, false, new OaWorkReportRemindReq("DAILY", today, null)))
                .isEqualTo(1);
            assertThat(service.remind(TENANT, ALICE, false, new OaWorkReportRemindReq("DAILY", today, null))).isZero();
            assertThat(fixture.outbox("REPORT_REMIND:DAILY:" + today + ":" + EVE))
                .contains("\"recipients\":[" + EVE + "]");
            assertThatThrownBy(() -> service.remind(TENANT, ALICE, false, new OaWorkReportRemindReq("DAILY", today
                .plusDays(1), null))).hasMessageContaining("尚未开始");

            assertThatThrownBy(() -> service.summary(TENANT, CAROL, false, "DAILY", today, null))
                .hasMessageContaining("没有可查看的团队成员");
            assertThat(service.summary(TENANT, DAVE, false, "DAILY", today, null).members())
                .extracting(OaWorkReportMemberStat::userId)
                .containsExactly(CAROL);
            assertThat(service.summary(TENANT, CAROL, true, "DAILY", today, 20L).members())
                .extracting(OaWorkReportMemberStat::userId)
                .containsExactly(CAROL, DAVE);
            assertThat(service.summary(TENANT, CAROL, true, "DAILY", today, null).total()).isEqualTo(5);
            OaWorkReportSummaryResp other = service.summary(OTHER_TENANT, OUTSIDER, true, "DAILY", today, null);
            assertThat(other.members()).extracting(OaWorkReportMemberStat::userId).containsExactly(OUTSIDER);
            assertThat(other.submitted()).isZero();
        });
    }

    private static List<Long> ids(List<OaCalendarEventResp> events) {
        return events.stream().map(OaCalendarEventResp::id).toList();
    }

    private static OaCalendarEventReq req(String requestId,
                                          String title,
                                          LocalDateTime start,
                                          LocalDateTime end,
                                          String scope,
                                          Long deptId,
                                          Integer remindMinutes,
                                          List<Long> members,
                                          Boolean allDay) {
        return req(requestId, title, start, end, scope, deptId, remindMinutes, members, allDay, null);
    }

    private static OaCalendarEventReq req(String requestId,
                                          String title,
                                          LocalDateTime start,
                                          LocalDateTime end,
                                          String scope,
                                          Long deptId,
                                          Integer remindMinutes,
                                          List<Long> members,
                                          Boolean allDay,
                                          Integer version) {
        return new OaCalendarEventReq(requestId, title, null, null, start, end, allDay, scope, deptId, remindMinutes, members, version);
    }

    private static OaWorkReportSaveReq save(String type, LocalDate date, String done, boolean submit, Integer version) {
        return new OaWorkReportSaveReq(type, date, done, null, null, submit, version);
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-collab-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        createTables(jdbc);
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, parent_id) VALUES (1, 101, '总部', 0), (10, 101, '研发部', 1), (11, 101, '前端组', 10), (20, 101, '市场部', 1), (90, 202, '外部', 0)");
        user(jdbc, ALICE, TENANT, "alice", "Alice", 10L, null, true);
        user(jdbc, BOB, TENANT, "bob", "Bob", 11L, ALICE, false);
        user(jdbc, CAROL, TENANT, "carol", "Carol", 20L, DAVE, false);
        user(jdbc, DAVE, TENANT, "dave", "Dave", 20L, null, true);
        user(jdbc, EVE, TENANT, "eve", "Eve", 10L, null, false);
        user(jdbc, OUTSIDER, OTHER_TENANT, "olga", "Olga", 90L, null, true);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        OaCollabRepository repository = new OaCollabRepository(jdbc);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), new ObjectMapper());
        return new Fixture(jdbc, new OaCalendarService(repository, outboxService, transactionManager), new OaWorkReportService(repository, outboxService, transactionManager));
    }

    private static void user(JdbcTemplate jdbc,
                             long id,
                             long tenantId,
                             String username,
                             String nickname,
                             Long deptId,
                             Long supervisorId,
                             boolean leader) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, nickname, dept_id, status, deleted) VALUES (?, ?, ?, ?, ?, 1, 0)", id, tenantId, username, nickname, deptId);
        jdbc.update("INSERT INTO oa_employee (id, tenant_id, user_id, supervisor_id, dept_leader, employment_status, deleted) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0)", id, tenantId, id, supervisorId, leader);
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
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_report_period UNIQUE (tenant_id, author_id, report_type, period_start)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_work_report_comment (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, report_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL, content VARCHAR(1000) NOT NULL, create_time TIMESTAMP NOT NULL
            )
            """);
    }

    private record Fixture(JdbcTemplate jdbc, OaCalendarService calendar, OaWorkReportService report) {

        String outbox(String dedupKey) {
            List<String> rows = jdbc
                .queryForList("SELECT payload FROM oa_outbox WHERE dedup_key = ?", String.class, dedupKey);
            assertThat(rows).as("outbox " + dedupKey).hasSize(1);
            return rows.get(0);
        }

        int countOutbox(String dedupPattern) {
            Integer count = jdbc
                .queryForObject("SELECT COUNT(*) FROM oa_outbox WHERE dedup_key LIKE ?", Integer.class, dedupPattern);
            return count == null ? 0 : count;
        }
    }
}
