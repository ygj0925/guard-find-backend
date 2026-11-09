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

package com.guardfind.backend.oa.service;

import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.model.OaCalendarEventResp;
import com.guardfind.backend.oa.model.OaStatsDeptRow;
import com.guardfind.backend.oa.model.OaStatsOverdueTask;
import com.guardfind.backend.oa.model.OaStatsResp;
import com.guardfind.backend.oa.model.OaStatsTypeRow;
import com.guardfind.backend.oa.model.OaWorkbenchItem;
import com.guardfind.backend.oa.model.OaWorkbenchResp;
import com.guardfind.backend.oa.repository.OaStatsRepository;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * OA 工作台与统计。
 *
 * <p>
 * 工作台只汇总当前用户本人的数据；统计按租户全量计算，由独立权限控制。超期口径与超期提醒一致：任务创建超过 oa.overdue.hours 小时仍未处理。
 * </p>
 */
@Service
public class OaStatsService {

    public static final long MAX_RANGE_DAYS = 366;
    private static final int RECENT_LIMIT = 5;
    private static final int OVERDUE_LIST_LIMIT = 20;
    private static final int DEPT_LIMIT = 20;

    private final OaStatsRepository repository;
    private final OaApprovalEngine engine;
    private final OaNotificationService notificationService;
    private final OaCcService ccService;
    private final OaCalendarService calendarService;
    private final long overdueHours;

    public OaStatsService(OaStatsRepository repository,
                          OaApprovalEngine engine,
                          OaNotificationService notificationService,
                          OaCcService ccService,
                          OaCalendarService calendarService,
                          @Value("${oa.overdue.hours:24}") long overdueHours) {
        this.repository = repository;
        this.engine = engine;
        this.notificationService = notificationService;
        this.ccService = ccService;
        this.calendarService = calendarService;
        this.overdueHours = Math.max(1, overdueHours);
    }

    public OaWorkbenchResp workbench(long tenantId, long userId) {
        LocalDateTime now = LocalDateTime.now();
        String tenant = Long.toString(tenantId);
        String user = Long.toString(userId);
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        List<OaWorkbenchItem> events = calendarService.events(tenantId, userId, dayStart, dayStart.plusDays(1))
            .stream()
            .filter(event -> !"CANCELLED".equals(event.status()))
            .map(OaStatsService::eventItem)
            .toList();
        return new OaWorkbenchResp(engine.countTodo(tenant, user, null), engine
            .countTodo(tenant, user, threshold(now)), overdueHours, repository
                .countMyApplications(tenantId, userId, "RUNNING"), notificationService
                    .unreadCount(tenantId, userId), ccService.count(tenantId, userId, null, "UNREAD"), repository
                        .dailyReportSubmitted(tenantId, userId, now.toLocalDate()), repository
                            .recentApplications(tenantId, userId, RECENT_LIMIT), events, repository
                                .upcomingBookings(tenantId, userId, now, RECENT_LIMIT), repository
                                    .activeVehicleRequests(tenantId, userId, RECENT_LIMIT));
    }

    public OaStatsResp overview(long tenantId, LocalDate from, LocalDate to, String businessType) {
        requireRange(from, to);
        String type = blankToNull(businessType);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<OaStatsRepository.TypeStatusRow> rows = repository.typeStatusRows(tenantId, start, end, type);
        Map<String, List<OaStatsRepository.TypeStatusRow>> byType = rows.stream()
            .collect(Collectors.groupingBy(OaStatsRepository.TypeStatusRow::businessType, TreeMap::new, Collectors
                .toList()));
        List<OaStatsTypeRow> typeRows = new ArrayList<>();
        byType.forEach((key, group) -> typeRows.add(aggregate(key, group)));
        typeRows.sort((a, b) -> Long.compare(b.total(), a.total()));
        List<OaStatsDeptRow> deptRows = repository.deptRows(tenantId, start, end, type, DEPT_LIMIT)
            .stream()
            .map(row -> new OaStatsDeptRow(row.deptName(), row.count(), row.approved(), money(row.approvedAmount())))
            .toList();
        LocalDateTime now = LocalDateTime.now();
        Date threshold = threshold(now);
        String tenant = Long.toString(tenantId);
        return new OaStatsResp(from, to, aggregate(null, rows), typeRows, deptRows, overdueHours, engine
            .countTasksCreatedBefore(tenant, threshold), overdueTasks(tenantId, engine
                .tasksCreatedBefore(tenant, threshold, OVERDUE_LIST_LIMIT), now));
    }

    static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException("请选择统计区间");
        }
        if (to.isBefore(from)) {
            throw new BusinessException("结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new BusinessException("统计区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Date threshold(LocalDateTime now) {
        return Date.from(now.minusHours(overdueHours).atZone(ZoneId.systemDefault()).toInstant());
    }

    private List<OaStatsOverdueTask> overdueTasks(long tenantId, List<Task> tasks, LocalDateTime now) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Map<String, OaStatsRepository.ApplicationBrief> briefs = repository
            .runningApplicationsByInstance(tenantId, tasks.stream()
                .map(Task::getProcessInstanceId)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<Task, List<Long>> recipients = new LinkedHashMap<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (Task task : tasks) {
            List<Long> ids = engine.recipientIds(task);
            recipients.put(task, ids);
            userIds.addAll(ids);
        }
        Map<Long, String> names = repository.userNames(tenantId, userIds);
        List<OaStatsOverdueTask> result = new ArrayList<>();
        for (Task task : tasks) {
            OaStatsRepository.ApplicationBrief brief = briefs.get(task.getProcessInstanceId());
            LocalDateTime created = LocalDateTime.ofInstant(task.getCreateTime().toInstant(), ZoneId.systemDefault());
            String assignees = recipients.get(task)
                .stream()
                .map(id -> names.getOrDefault(id, String.valueOf(id)))
                .collect(Collectors.joining("、"));
            result.add(new OaStatsOverdueTask(task.getId(), task.getName(), brief == null
                ? null
                : brief.id(), brief == null ? null : brief.title(), brief == null
                    ? null
                    : brief.businessType(), brief == null ? null : brief.applicantName(), assignees, created, Duration
                        .between(created, now)
                        .toHours()));
        }
        return result;
    }

    private static OaStatsTypeRow aggregate(String businessType, List<OaStatsRepository.TypeStatusRow> rows) {
        long total = 0;
        long running = 0;
        long approved = 0;
        long rejected = 0;
        long withdrawn = 0;
        long cancelled = 0;
        long finished = 0;
        double weightedSeconds = 0;
        BigDecimal requested = BigDecimal.ZERO;
        BigDecimal approvedAmount = BigDecimal.ZERO;
        for (OaStatsRepository.TypeStatusRow row : rows) {
            total += row.count();
            switch (row.status()) {
                case "RUNNING" -> running += row.count();
                case "APPROVED" -> approved += row.count();
                case "REJECTED" -> rejected += row.count();
                case "WITHDRAWN" -> withdrawn += row.count();
                case "CANCELLED" -> cancelled += row.count();
                default -> {
                }
            }
            if (row.avgSeconds() != null && row.finished() > 0) {
                weightedSeconds += row.avgSeconds() * row.finished();
                finished += row.finished();
            }
            if (row.amount() != null) {
                requested = requested.add(row.amount());
                if ("APPROVED".equals(row.status())) {
                    approvedAmount = approvedAmount.add(row.amount());
                }
            }
        }
        Double avgHours = finished == 0
            ? null
            : BigDecimal.valueOf(weightedSeconds / finished / 3600).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new OaStatsTypeRow(businessType, total, running, approved, rejected, withdrawn, cancelled, avgHours, money(requested), money(approvedAmount));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static OaWorkbenchItem eventItem(OaCalendarEventResp event) {
        return new OaWorkbenchItem(event.id(), event.title(), event.location(), event.startTime(), event
            .endTime(), event.status());
    }
}
