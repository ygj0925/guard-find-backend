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

package com.guardfind.backend.oa.attendance;

import com.guardfind.backend.oa.business.OaBusinessHook;
import com.guardfind.backend.oa.business.OaBusinessPreparation;
import com.guardfind.backend.oa.repository.OaAttendanceRepository;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.DateSpan;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.LeaveSpan;
import com.guardfind.backend.oa.repository.OaAttendanceRepository.RequestRow;
import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import top.continew.starter.core.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 补卡/加班/外出钩子的公共部分：默认 HR 岗位审核人、提交时写申请单、通过/关闭时流转状态。
 */
public abstract class OaAttendanceHookSupport implements OaBusinessHook {

    public static final String VAR_HR_REVIEWER = "hrReviewer";
    public static final String HR_POSITION = "HR";
    public static final String STATUS_APPROVING = "APPROVING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final int MAX_BACKDATE_DAYS = 30;
    public static final int MAX_AHEAD_DAYS = 30;

    static final Set<String> CLOSE_STATUSES = Set.of("REJECTED", "WITHDRAWN", "CANCELLED");
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    protected final OaAttendanceRepository repository;

    protected OaAttendanceHookSupport(OaAttendanceRepository repository) {
        this.repository = repository;
    }

    protected abstract String kind();

    @Override
    public Map<String, OaAssigneeSpec> defaultAssignees() {
        return Map.of(VAR_HR_REVIEWER, OaAssigneeSpec.position(HR_POSITION));
    }

    @Override
    public void submitted(long tenantId,
                          long applicationId,
                          long applicantId,
                          OaBusinessPreparation preparation,
                          LocalDateTime now) {
        AttendancePlan plan = (AttendancePlan)preparation.state();
        repository.insertRequest(tenantId, applicationId, applicantId, kind(), plan.workDate(), plan.start(), plan
            .end(), plan.hours(), plan.checkType(), plan.destination(), STATUS_APPROVING, now);
    }

    @Override
    public void finished(long tenantId, long applicationId, String status, long operatorId, LocalDateTime now) {
        RequestRow request = repository.lockRequestByApplication(tenantId, applicationId);
        if (request == null || !STATUS_APPROVING.equals(request.status())) {
            return;
        }
        if ("APPROVED".equals(status)) {
            if (repository.transitionRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_APPROVED, now) == 1) {
                approved(tenantId, request, now);
            }
        } else if (CLOSE_STATUSES.contains(status)) {
            repository.transitionRequest(tenantId, request.id(), STATUS_APPROVING, STATUS_CLOSED, now);
        }
    }

    protected void approved(long tenantId, RequestRow request, LocalDateTime now) {
    }

    /**
     * 时间段校验：结束晚于开始、开始不早于 30 天前且不晚于 30 天后、时长不超过上限；返回保留一位小数的小时数。
     */
    protected static BigDecimal span(LocalDateTime start, LocalDateTime end, BigDecimal maxHours, String label) {
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        LocalDate today = LocalDate.now();
        if (start.toLocalDate().isBefore(today.minusDays(MAX_BACKDATE_DAYS))) {
            throw new BusinessException("开始时间不能早于 " + MAX_BACKDATE_DAYS + " 天前");
        }
        if (start.toLocalDate().isAfter(today.plusDays(MAX_AHEAD_DAYS))) {
            throw new BusinessException("开始时间不能晚于 " + MAX_AHEAD_DAYS + " 天后");
        }
        BigDecimal hours = BigDecimal.valueOf(Duration.between(start, end).toMinutes())
            .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
        if (hours.compareTo(maxHours) > 0) {
            throw new BusinessException("单次" + label + "不能超过 " + maxHours.stripTrailingZeros().toPlainString() + " 小时");
        }
        return hours;
    }

    /**
     * 拒绝与本人未关闭的加班/外出、有效请假（以及可选的未关闭出差）时间重叠；调用前须已锁定申请人。
     */
    protected void rejectOverlaps(long tenantId,
                                  long applicantId,
                                  LocalDateTime start,
                                  LocalDateTime end,
                                  boolean includeTravel) {
        List<RequestRow> requests = repository.overlappingRequests(tenantId, applicantId, start, end);
        if (!requests.isEmpty()) {
            RequestRow other = requests.get(0);
            throw new BusinessException("与已有" + kindLabel(other.kind()) + "申请（" + other.start()
                .format(DISPLAY) + " ~ " + other.end().format(DISPLAY) + "）时间重叠");
        }
        for (LeaveSpan leave : repository.activeLeaves(tenantId, applicantId, start.toLocalDate(), end.minusNanos(1)
            .toLocalDate())) {
            if (leave.startTime().isBefore(end) && leave.endTime().isAfter(start)) {
                throw new BusinessException("与请假（" + leave.typeName() + " " + leave.startDate() + " ~ " + leave
                    .endDate() + "）时间重叠");
            }
        }
        if (includeTravel) {
            List<DateSpan> travels = repository.activeTravels(tenantId, applicantId, start.toLocalDate(), end
                .minusNanos(1)
                .toLocalDate());
            if (!travels.isEmpty()) {
                DateSpan travel = travels.get(0);
                throw new BusinessException("与出差（" + travel.label() + " " + travel.startDate() + " ~ " + travel
                    .endDate() + "）时间重叠");
            }
        }
    }

    public static String kindLabel(String kind) {
        return switch (kind) {
            case OaAttendanceRepository.KIND_OVERTIME -> "加班";
            case OaAttendanceRepository.KIND_OUTING -> "外出";
            default -> "补卡";
        };
    }

    public record AttendancePlan(LocalDate workDate, LocalDateTime start, LocalDateTime end, BigDecimal hours,
                                 String checkType, String destination) {
    }
}
