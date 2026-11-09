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

import com.guardfind.backend.oa.collab.OaOrgScope;
import com.guardfind.backend.oa.model.OaWorkReportCommentReq;
import com.guardfind.backend.oa.model.OaWorkReportDetailResp;
import com.guardfind.backend.oa.model.OaWorkReportMemberStat;
import com.guardfind.backend.oa.model.OaWorkReportRemindReq;
import com.guardfind.backend.oa.model.OaWorkReportResp;
import com.guardfind.backend.oa.model.OaWorkReportSaveReq;
import com.guardfind.backend.oa.model.OaWorkReportSummaryResp;
import com.guardfind.backend.oa.repository.OaCollabRepository;
import com.guardfind.backend.oa.repository.OaCollabRepository.ReportFilter;
import com.guardfind.backend.oa.repository.OaCollabRepository.ReportRow;
import com.guardfind.backend.oa.repository.OaCollabRepository.ReportValues;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.guardfind.backend.oa.service.OaPurchaseService.blankToNull;
import static com.guardfind.backend.oa.service.OaPurchaseService.offset;
import static com.guardfind.backend.oa.service.OaPurchaseService.safeSize;

/**
 * 日报/周报：本人按周期保存草稿或提交，提交后只读；部门负责人看本部门及下级部门、主管看主管链下属，统计权限看全租户。
 */
@Service
public class OaWorkReportService {

    public static final String TYPE_DAILY = "DAILY";
    public static final String TYPE_WEEKLY = "WEEKLY";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_MISSING = "MISSING";
    public static final String AGGREGATE_REPORT = "WORK_REPORT";
    public static final String EVENT_COMMENTED = "REPORT_COMMENTED";
    public static final String EVENT_REMIND = "REPORT_REMIND";
    private static final Set<String> TYPES = Set.of(TYPE_DAILY, TYPE_WEEKLY);

    private final OaCollabRepository repository;
    private final OaOutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public OaWorkReportService(OaCollabRepository repository,
                               OaOutboxService outboxService,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaWorkReportResp> mine(long tenantId, long userId, String type, int page, int size) {
        int limit = safeSize(size);
        return repository.reports(tenantId, mineFilter(userId, type), offset(page, limit), limit);
    }

    public long countMine(long tenantId, long userId, String type) {
        return repository.countReports(tenantId, mineFilter(userId, type));
    }

    /**
     * 本人某周期的报告，没有时返回 null，前端据此新建。
     */
    public OaWorkReportResp minePeriod(long tenantId, long userId, String type, LocalDate date) {
        String reportType = type(type);
        return repository.periodReport(tenantId, userId, reportType, periodStart(reportType, date));
    }

    public OaWorkReportResp save(long tenantId, long userId, OaWorkReportSaveReq req) {
        String type = type(req.reportType());
        LocalDate periodStart = periodStart(type, req.date());
        LocalDate periodEnd = periodEnd(type, periodStart);
        if (periodStart.isAfter(LocalDate.now())) {
            throw new BusinessException("不能填写未来周期的报告");
        }
        boolean submit = Boolean.TRUE.equals(req.submit());
        Long id;
        try {
            id = transactionTemplate.execute(status -> {
                OaOrgScope org = repository.orgScope(tenantId);
                OaOrgScope.Employee self = org.employee(userId);
                if (self == null) {
                    throw new BusinessException("当前账号不是在职员工，不能填写工作报告");
                }
                LocalDateTime now = LocalDateTime.now();
                ReportValues values = new ReportValues(self.deptId(), type, periodStart, periodEnd, req.doneContent()
                    .trim(), blankToNull(req.planContent()), blankToNull(req.issueContent()), submit
                        ? STATUS_SUBMITTED
                        : STATUS_DRAFT, submit ? now : null);
                ReportRow existing = repository.lockReport(tenantId, userId, type, periodStart);
                if (existing == null) {
                    return repository.insertReport(tenantId, userId, values, now);
                }
                if (STATUS_SUBMITTED.equals(existing.status())) {
                    throw new BusinessException("已提交的报告不能修改");
                }
                if (req.version() == null) {
                    throw new BusinessException("该周期已有草稿，请刷新后重试");
                }
                if (repository.updateDraft(tenantId, existing.id(), req.version(), values, now) != 1) {
                    throw new BusinessException("报告已被修改，请刷新后重试");
                }
                return existing.id();
            });
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该周期已有报告，请刷新后重试");
        }
        return repository.report(tenantId, id);
    }

    public void deleteDraft(long tenantId, long userId, long id) {
        ReportRow row = repository.reportRow(tenantId, id);
        if (row == null || row.authorId() != userId) {
            throw new BusinessException("报告不存在或无权操作");
        }
        if (!STATUS_DRAFT.equals(row.status())) {
            throw new BusinessException("已提交的报告不能删除");
        }
        if (repository.deleteDraft(tenantId, id, userId) != 1) {
            throw new BusinessException("报告状态已变化，请刷新后重试");
        }
    }

    public List<OaWorkReportResp> team(long tenantId,
                                       long userId,
                                       boolean statAll,
                                       String type,
                                       LocalDate date,
                                       Long deptId,
                                       Long authorId,
                                       int page,
                                       int size) {
        int limit = safeSize(size);
        return repository
            .reports(tenantId, teamFilter(tenantId, userId, statAll, type, date, deptId, authorId), offset(page, limit), limit);
    }

    public long countTeam(long tenantId,
                          long userId,
                          boolean statAll,
                          String type,
                          LocalDate date,
                          Long deptId,
                          Long authorId) {
        return repository.countReports(tenantId, teamFilter(tenantId, userId, statAll, type, date, deptId, authorId));
    }

    public OaWorkReportDetailResp detail(long tenantId, long userId, boolean statAll, long id) {
        OaWorkReportResp report = requireVisible(tenantId, userId, statAll, id);
        return new OaWorkReportDetailResp(report, repository.comments(tenantId, id));
    }

    public OaWorkReportDetailResp comment(long tenantId,
                                          long userId,
                                          boolean statAll,
                                          long id,
                                          OaWorkReportCommentReq req) {
        OaWorkReportResp report = requireVisible(tenantId, userId, statAll, id);
        if (!STATUS_SUBMITTED.equals(report.status())) {
            throw new BusinessException("草稿不能批注");
        }
        String content = req.content().trim();
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            repository.insertComment(tenantId, id, userId, content, now);
            if (report.authorId() != userId) {
                outboxService.recordNotification(tenantId, AGGREGATE_REPORT, Long
                    .toString(id), EVENT_COMMENTED, EVENT_COMMENTED + ":" + id + ":" + userId + ":" + now, null, null, EVENT_COMMENTED, "工作报告收到批注", label(report
                        .reportType(), report.periodStart()) + "收到新的批注：" + abbreviate(content), List.of(report
                            .authorId()));
            }
        });
        return new OaWorkReportDetailResp(repository.report(tenantId, id), repository.comments(tenantId, id));
    }

    public OaWorkReportSummaryResp summary(long tenantId,
                                           long userId,
                                           boolean statAll,
                                           String type,
                                           LocalDate date,
                                           Long deptId) {
        String reportType = type(type);
        LocalDate periodStart = periodStart(reportType, date);
        LocalDate periodEnd = periodEnd(reportType, periodStart);
        OaOrgScope org = repository.orgScope(tenantId);
        List<Long> scope = scopeUsers(org, userId, statAll, deptId);
        Map<Long, ReportRow> submitted = new HashMap<>();
        for (ReportRow row : repository.periodReports(tenantId, reportType, periodStart)) {
            submitted.put(row.authorId(), row);
        }
        List<OaWorkReportMemberStat> members = new ArrayList<>();
        int submittedCount = 0;
        int lateCount = 0;
        for (Long memberId : scope) {
            OaOrgScope.Employee employee = org.employee(memberId);
            ReportRow row = submitted.get(memberId);
            boolean late = row != null && OaCollabRepository.isLate(row.submitTime(), row.periodEnd());
            if (row != null) {
                submittedCount++;
                if (late) {
                    lateCount++;
                }
            }
            members.add(new OaWorkReportMemberStat(memberId, employee.name(), employee.deptId(), org.deptName(employee
                .deptId()), row == null ? null : row.id(), row == null ? STATUS_MISSING : STATUS_SUBMITTED, row == null
                    ? null
                    : row.submitTime(), late));
        }
        members.sort(Comparator.comparing((OaWorkReportMemberStat stat) -> STATUS_SUBMITTED.equals(stat.status()))
            .thenComparing(OaWorkReportMemberStat::userId));
        return new OaWorkReportSummaryResp(reportType, periodStart, periodEnd, scope
            .size(), submittedCount, lateCount, members);
    }

    /**
     * 向范围内本周期未提交的人发提醒，按人和周期去重，返回本次新写入的提醒数。
     */
    public int remind(long tenantId, long userId, boolean statAll, OaWorkReportRemindReq req) {
        String reportType = type(req.reportType());
        LocalDate periodStart = periodStart(reportType, req.date());
        if (periodStart.isAfter(LocalDate.now())) {
            throw new BusinessException("周期尚未开始，不能催交");
        }
        OaOrgScope org = repository.orgScope(tenantId);
        List<Long> scope = scopeUsers(org, userId, statAll, req.deptId());
        Set<Long> done = new LinkedHashSet<>();
        for (ReportRow row : repository.periodReports(tenantId, reportType, periodStart)) {
            done.add(row.authorId());
        }
        String label = label(reportType, periodStart);
        Integer count = transactionTemplate.execute(status -> {
            int recorded = 0;
            for (Long memberId : scope) {
                if (done.contains(memberId) || memberId == userId) {
                    continue;
                }
                if (outboxService
                    .recordNotification(tenantId, AGGREGATE_REPORT, reportType + ":" + periodStart, EVENT_REMIND, EVENT_REMIND + ":" + reportType + ":" + periodStart + ":" + memberId, null, null, EVENT_REMIND, "请提交工作报告", "你的" + label + "尚未提交，请及时填写", List
                        .of(memberId))) {
                    recorded++;
                }
            }
            return recorded;
        });
        return count == null ? 0 : count;
    }

    static LocalDate periodStart(String type, LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        return TYPE_WEEKLY.equals(type) ? day.with(DayOfWeek.MONDAY) : day;
    }

    static LocalDate periodEnd(String type, LocalDate periodStart) {
        return TYPE_WEEKLY.equals(type) ? periodStart.plusDays(6) : periodStart;
    }

    private OaWorkReportResp requireVisible(long tenantId, long userId, boolean statAll, long id) {
        OaWorkReportResp report = repository.report(tenantId, id);
        if (report == null) {
            throw new BusinessException("报告不存在或无权查看");
        }
        if (report.authorId() == userId) {
            return report;
        }
        if (STATUS_SUBMITTED.equals(report.status())) {
            if (statAll) {
                return report;
            }
            OaOrgScope org = repository.orgScope(tenantId);
            if ((report.deptId() != null && org.ledDepts(userId).contains(report.deptId())) || org.subordinates(userId)
                .contains(report.authorId())) {
                return report;
            }
        }
        throw new BusinessException("报告不存在或无权查看");
    }

    private ReportFilter mineFilter(long userId, String type) {
        return new ReportFilter(userId, false, null, null, optionalType(type), null, null, null);
    }

    private ReportFilter teamFilter(long tenantId,
                                    long userId,
                                    boolean statAll,
                                    String type,
                                    LocalDate date,
                                    Long deptId,
                                    Long authorId) {
        String reportType = optionalType(type);
        LocalDate periodStart = date == null ? null : periodStart(reportType == null ? TYPE_DAILY : reportType, date);
        OaOrgScope org = repository.orgScope(tenantId);
        Set<Long> deptFilter = deptId == null ? null : org.subtree(deptId);
        if (statAll) {
            return new ReportFilter(null, true, null, null, reportType, periodStart, authorId, deptFilter);
        }
        return new ReportFilter(null, false, org.ledDepts(userId), org
            .subordinates(userId), reportType, periodStart, authorId, deptFilter);
    }

    /**
     * 统计/催交范围：统计权限为全部在职员工（可按部门子树过滤），否则为负责部门内员工与主管链下属。
     */
    private static List<Long> scopeUsers(OaOrgScope org, long userId, boolean statAll, Long deptId) {
        Set<Long> users = new LinkedHashSet<>();
        if (statAll) {
            org.employees().forEach(employee -> users.add(employee.userId()));
        } else {
            users.addAll(org.usersInDepts(org.ledDepts(userId)));
            users.addAll(org.subordinates(userId));
            users.remove(userId);
            if (users.isEmpty()) {
                throw new BusinessException("没有可查看的团队成员");
            }
        }
        if (deptId != null) {
            Set<Long> depts = org.subtree(deptId);
            users.removeIf(id -> {
                OaOrgScope.Employee employee = org.employee(id);
                return employee == null || employee.deptId() == null || !depts.contains(employee.deptId());
            });
        }
        users.removeIf(id -> org.employee(id) == null);
        return users.stream().sorted().toList();
    }

    private static String type(String type) {
        if (type == null || !TYPES.contains(type)) {
            throw new BusinessException("报告类型无效");
        }
        return type;
    }

    private static String optionalType(String type) {
        return type == null || type.isBlank() ? null : type(type);
    }

    private static String label(String type, LocalDate periodStart) {
        return TYPE_WEEKLY.equals(type) ? periodStart + " 当周周报" : periodStart + " 日报";
    }

    private static String abbreviate(String content) {
        return content.length() <= 60 ? content : content.substring(0, 60) + "…";
    }
}
