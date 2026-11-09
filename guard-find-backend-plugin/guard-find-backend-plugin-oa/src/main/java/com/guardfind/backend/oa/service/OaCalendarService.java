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
import com.guardfind.backend.oa.model.OaCalendarEventReq;
import com.guardfind.backend.oa.model.OaCalendarEventResp;
import com.guardfind.backend.oa.model.OaCalendarMemberResp;
import com.guardfind.backend.oa.model.OaDeptOptionResp;
import com.guardfind.backend.oa.repository.OaCollabRepository;
import com.guardfind.backend.oa.repository.OaCollabRepository.DueReminder;
import com.guardfind.backend.oa.repository.OaCollabRepository.EventRow;
import com.guardfind.backend.oa.repository.OaCollabRepository.EventValues;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.guardfind.backend.oa.service.OaPurchaseService.blankToNull;

/**
 * 日程：个人日程可邀请成员，部门日程发布到本部门或下级部门，对该部门及其下级部门的在职员工可见；到点按提醒时间经 outbox 通知。
 */
@Service
public class OaCalendarService {

    public static final String SCOPE_PERSONAL = "PERSONAL";
    public static final String SCOPE_DEPT = "DEPT";
    public static final String AGGREGATE_EVENT = "CALENDAR_EVENT";
    public static final String EVENT_INVITED = "CALENDAR_INVITED";
    public static final String EVENT_CANCELLED = "CALENDAR_CANCELLED";
    public static final String EVENT_REMIND = "CALENDAR_REMIND";
    static final int MAX_EVENT_DAYS = 31;
    static final int MAX_RANGE_DAYS = 62;
    private static final int RANGE_LIMIT = 1000;
    private static final int REMIND_SCAN_LIMIT = 500;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OaCollabRepository repository;
    private final OaOutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public OaCalendarService(OaCollabRepository repository,
                             OaOutboxService outboxService,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaCalendarEventResp> events(long tenantId, long userId, LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new BusinessException("查询区间无效");
        }
        if (Duration.between(from, to).toDays() > MAX_RANGE_DAYS) {
            throw new BusinessException("查询区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        OaOrgScope org = repository.orgScope(tenantId);
        List<OaCalendarEventResp> events = repository
            .visibleEvents(tenantId, userId, viewerDepts(org, userId), from, to, RANGE_LIMIT);
        return withMembers(tenantId, events);
    }

    public OaCalendarEventResp detail(long tenantId, long userId, long id) {
        OaCalendarEventResp event = repository.event(tenantId, id);
        if (event == null || !canView(tenantId, userId, event)) {
            throw new BusinessException("日程不存在或无权查看");
        }
        return withMembers(tenantId, List.of(event)).get(0);
    }

    /**
     * 可发布部门日程的部门：本人所在部门及其下级部门。
     */
    public List<OaDeptOptionResp> publishableDepts(long tenantId, long userId) {
        OaOrgScope org = repository.orgScope(tenantId);
        OaOrgScope.Employee self = org.employee(userId);
        if (self == null || self.deptId() == null) {
            return List.of();
        }
        List<OaDeptOptionResp> result = new ArrayList<>();
        for (Long deptId : org.subtree(self.deptId())) {
            result.add(new OaDeptOptionResp(deptId, org.deptName(deptId)));
        }
        return result;
    }

    public OaCalendarEventResp create(long tenantId, long userId, boolean canPublishDept, OaCalendarEventReq req) {
        String requestId = blankToNull(req.requestId());
        if (requestId == null) {
            throw new BusinessException("缺少请求号");
        }
        Long id = transactionTemplate.execute(status -> {
            Long replayed = repository.eventByRequest(tenantId, userId, requestId);
            if (replayed != null) {
                return replayed;
            }
            OaOrgScope org = repository.orgScope(tenantId);
            LocalDateTime now = LocalDateTime.now();
            EventValues values = values(org, userId, canPublishDept, req, now);
            Set<Long> members = members(org, userId, req.memberIds());
            long eventId;
            try {
                eventId = repository.insertEvent(tenantId, userId, requestId, values, now);
            } catch (DuplicateKeyException e) {
                Long existing = repository.eventByRequest(tenantId, userId, requestId);
                if (existing == null) {
                    throw new BusinessException("日程保存冲突，请重试");
                }
                return existing;
            }
            repository.replaceMembers(tenantId, eventId, members, now);
            notifyInvited(tenantId, eventId, values, members);
            return eventId;
        });
        return detail(tenantId, userId, id);
    }

    public OaCalendarEventResp update(long tenantId,
                                      long userId,
                                      boolean canPublishDept,
                                      long id,
                                      OaCalendarEventReq req) {
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        transactionTemplate.executeWithoutResult(status -> {
            EventRow event = requireOwned(tenantId, userId, id);
            OaOrgScope org = repository.orgScope(tenantId);
            LocalDateTime now = LocalDateTime.now();
            EventValues values = values(org, userId, canPublishDept, req, now);
            if (repository.updateEvent(tenantId, event.id(), req.version(), values, now) != 1) {
                throw new BusinessException("日程已被修改，请刷新后重试");
            }
            Set<Long> members = members(org, userId, req.memberIds());
            Set<Long> added = new LinkedHashSet<>(members);
            repository.memberIds(tenantId, id).forEach(added::remove);
            repository.replaceMembers(tenantId, id, members, now);
            notifyInvited(tenantId, id, values, added);
        });
        return detail(tenantId, userId, id);
    }

    public OaCalendarEventResp cancel(long tenantId, long userId, long id) {
        transactionTemplate.executeWithoutResult(status -> {
            EventRow event = requireOwned(tenantId, userId, id);
            LocalDateTime now = LocalDateTime.now();
            if (repository.cancelEvent(tenantId, id, now) != 1) {
                throw new BusinessException("日程状态已变化，请刷新后重试");
            }
            if (event.end().isAfter(now)) {
                OaOrgScope org = repository.orgScope(tenantId);
                Set<Long> recipients = audience(org, tenantId, id, userId, event.scope(), event.deptId());
                recipients.remove(userId);
                outboxService.recordNotification(tenantId, AGGREGATE_EVENT, Long
                    .toString(id), EVENT_CANCELLED, EVENT_CANCELLED + ":" + id, null, null, EVENT_CANCELLED, "日程已取消", "「" + event
                        .title() + "」（" + TIME_FORMAT.format(event.start()) + "）已取消", recipients);
            }
        });
        return repository.event(tenantId, id).withMembers(repository.members(tenantId, List.of(id)));
    }

    /**
     * 到点提醒：先条件标记已提醒再写 outbox，同一提醒时间只发一次；提醒时间被修改后按新时间重新提醒。
     */
    public int remindDue(LocalDateTime now) {
        int reminded = 0;
        for (DueReminder due : repository.dueReminders(now, REMIND_SCAN_LIMIT)) {
            Boolean done = transactionTemplate.execute(status -> {
                if (repository.markReminded(due.tenantId(), due.id(), due.remindAt(), now) != 1) {
                    return false;
                }
                if (!due.start().isAfter(now.minusMinutes(1))) {
                    return true;
                }
                OaOrgScope org = repository.orgScope(due.tenantId());
                Set<Long> recipients = audience(org, due.tenantId(), due.id(), due.ownerId(), due.scope(), due
                    .deptId());
                String where = due.location() == null ? "" : "，地点：" + due.location();
                outboxService.recordNotification(due.tenantId(), AGGREGATE_EVENT, Long.toString(due
                    .id()), EVENT_REMIND, EVENT_REMIND + ":" + due.id() + ":" + due
                        .remindAt(), null, null, EVENT_REMIND, "日程提醒", "「" + due.title() + "」将于 " + TIME_FORMAT
                            .format(due.start()) + " 开始" + where, recipients);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                reminded++;
            }
        }
        return reminded;
    }

    private EventRow requireOwned(long tenantId, long userId, long id) {
        EventRow event = repository.lockEvent(tenantId, id);
        if (event == null || event.ownerId() != userId) {
            throw new BusinessException("日程不存在或无权操作");
        }
        if (!"ACTIVE".equals(event.status())) {
            throw new BusinessException("日程已取消");
        }
        return event;
    }

    private boolean canView(long tenantId, long userId, OaCalendarEventResp event) {
        if (event.ownerId() == userId || repository.isMember(tenantId, event.id(), userId)) {
            return true;
        }
        if (!"ACTIVE".equals(event.status()) || !SCOPE_DEPT.equals(event.scope()) || event.deptId() == null) {
            return false;
        }
        return viewerDepts(repository.orgScope(tenantId), userId).contains(event.deptId());
    }

    /**
     * 部门日程对发布部门及其下级部门可见，因此查看人能看到的发布部门是本部门及全部上级部门。
     */
    private static Set<Long> viewerDepts(OaOrgScope org, long userId) {
        OaOrgScope.Employee self = org.employee(userId);
        return self == null ? Set.of() : org.ancestorsOrSelf(self.deptId());
    }

    private Set<Long> audience(OaOrgScope org, long tenantId, long eventId, long ownerId, String scope, Long deptId) {
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(ownerId);
        recipients.addAll(repository.memberIds(tenantId, eventId));
        if (SCOPE_DEPT.equals(scope) && deptId != null) {
            recipients.addAll(org.usersInDepts(org.subtree(deptId)));
        }
        return recipients;
    }

    private void notifyInvited(long tenantId, long eventId, EventValues values, Set<Long> invited) {
        for (Long userId : invited) {
            outboxService.recordNotification(tenantId, AGGREGATE_EVENT, Long
                .toString(eventId), EVENT_INVITED, EVENT_INVITED + ":" + eventId + ":" + userId, null, null, EVENT_INVITED, "日程邀请", "你被邀请参加「" + values
                    .title() + "」，时间 " + TIME_FORMAT.format(values.start()) + " 至 " + TIME_FORMAT.format(values
                        .end()), List.of(userId));
        }
    }

    private static Set<Long> members(OaOrgScope org, long ownerId, List<Long> memberIds) {
        Set<Long> members = new LinkedHashSet<>();
        if (memberIds == null) {
            return members;
        }
        for (Long memberId : memberIds) {
            if (memberId == null || memberId == ownerId) {
                continue;
            }
            if (org.employee(memberId) == null) {
                throw new BusinessException("成员不存在或已离职");
            }
            members.add(memberId);
        }
        return members;
    }

    private static EventValues values(OaOrgScope org,
                                      long userId,
                                      boolean canPublishDept,
                                      OaCalendarEventReq req,
                                      LocalDateTime now) {
        boolean allDay = Boolean.TRUE.equals(req.allDay());
        LocalDateTime start;
        LocalDateTime end;
        if (allDay) {
            start = req.startTime().toLocalDate().atStartOfDay();
            end = req.endTime().toLocalDate().plusDays(1).atStartOfDay();
        } else {
            start = req.startTime().truncatedTo(ChronoUnit.MINUTES);
            end = req.endTime().truncatedTo(ChronoUnit.MINUTES);
        }
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (Duration.between(start, end).toDays() > MAX_EVENT_DAYS) {
            throw new BusinessException("单个日程不能超过 " + MAX_EVENT_DAYS + " 天");
        }
        String scope = req.scope() == null || req.scope().isBlank() ? SCOPE_PERSONAL : req.scope();
        Long deptId = null;
        if (SCOPE_DEPT.equals(scope)) {
            if (!canPublishDept) {
                throw new BusinessException("没有发布部门日程的权限");
            }
            OaOrgScope.Employee self = org.employee(userId);
            if (req.deptId() == null || self == null || self.deptId() == null || !org.subtree(self.deptId())
                .contains(req.deptId())) {
                throw new BusinessException("只能向本部门或下级部门发布日程");
            }
            deptId = req.deptId();
        } else if (!SCOPE_PERSONAL.equals(scope)) {
            throw new BusinessException("日程范围无效: " + scope);
        }
        Integer remindMinutes = req.remindMinutes();
        LocalDateTime remindAt = remindMinutes == null ? null : start.minusMinutes(remindMinutes);
        LocalDateTime remindedAt = remindAt != null && !remindAt.isAfter(now) ? now : null;
        return new EventValues(scope, deptId, req.title().trim(), blankToNull(req.location()), blankToNull(req
            .description()), start, end, allDay, remindMinutes, remindAt, remindedAt);
    }

    private List<OaCalendarEventResp> withMembers(long tenantId, List<OaCalendarEventResp> events) {
        if (events.isEmpty()) {
            return events;
        }
        Map<Long, List<OaCalendarMemberResp>> members = repository.members(tenantId, events.stream()
            .map(OaCalendarEventResp::id)
            .toList()).stream().collect(Collectors.groupingBy(OaCalendarMemberResp::eventId));
        return events.stream().map(event -> event.withMembers(members.getOrDefault(event.id(), List.of()))).toList();
    }
}
