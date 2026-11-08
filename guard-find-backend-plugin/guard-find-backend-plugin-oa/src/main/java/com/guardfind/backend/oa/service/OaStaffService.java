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

import com.guardfind.backend.oa.model.OaEmployeeCreateReq;
import com.guardfind.backend.oa.model.OaEmployeeOptionResp;
import com.guardfind.backend.oa.model.OaEmployeeResp;
import com.guardfind.backend.oa.model.OaEmployeeUpdateReq;
import com.guardfind.backend.oa.model.OaPositionCreateReq;
import com.guardfind.backend.oa.model.OaPositionOptionResp;
import com.guardfind.backend.oa.model.OaPositionResp;
import com.guardfind.backend.oa.model.OaPositionUpdateReq;
import com.guardfind.backend.oa.model.OaHandoverResp;
import com.guardfind.backend.oa.repository.OaStaffRepository;
import com.guardfind.backend.oa.staff.OaTaskHandover;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 员工档案与岗位字典管理。
 *
 * <p>
 * 用户与部门沿用后台账号（sys_user），本服务只维护 OA 档案。员工更新带乐观锁并原子替换岗位；
 * 直属主管必须是在职员工且不能形成环路，离职时优先自动把存量待办转交直属主管。
 * </p>
 */
@Service
public class OaStaffService {

    private static final int MAX_OPTIONS = 500;

    private static final Set<String> EMPLOYMENT_STATUSES = Set.of("ACTIVE", "SUSPENDED", "RESIGNED");

    private static final Set<String> POSITION_STATUSES = Set.of("ENABLED", "DISABLED");

    private final OaStaffRepository repository;
    private final OaTaskHandover taskHandover;
    private final TransactionTemplate transactionTemplate;

    public OaStaffService(OaStaffRepository repository,
                          OaTaskHandover taskHandover,
                          PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.taskHandover = taskHandover;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaEmployeeResp> pageEmployees(long tenantId, String keyword, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.pageEmployees(tenantId, keyword, status, (safePage - 1) * safeSize, safeSize);
    }

    public long countEmployees(long tenantId, String keyword, String status) {
        return repository.countEmployees(tenantId, keyword, status);
    }

    public OaEmployeeResp detailEmployee(long tenantId, long id) {
        OaEmployeeResp employee = repository.detailEmployee(tenantId, id);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        return employee;
    }

    public OaEmployeeResp createEmployee(long tenantId, long operatorId, OaEmployeeCreateReq req) {
        return transactionTemplate.execute(status -> {
            if (!repository.activeUserInTenant(tenantId, req.userId())) {
                throw new BusinessException("后台用户不存在、已停用或不属于当前租户");
            }
            if (repository.employeeByUserId(tenantId, req.userId()) != null) {
                throw new BusinessException("该用户已有 OA 员工档案");
            }
            String employeeNo = normalizeEmployeeNo(req.employeeNo());
            requireUniqueEmployeeNo(tenantId, employeeNo, null);
            String employmentStatus = normalizeEmploymentStatus(req.employmentStatus());
            requireSupervisor(tenantId, req.supervisorId(), req.userId(), 0L);
            List<Long> positionIds = requireEnabledPositions(tenantId, req.positionIds());
            LocalDateTime now = LocalDateTime.now();
            try {
                long id = repository.insertEmployee(tenantId, req.userId(), employeeNo, req
                    .supervisorId(), employmentStatus, Boolean.TRUE.equals(req.deptLeader()), req
                        .hireDate(), normalizeLeaveDate(employmentStatus, req.leaveDate()), operatorId, now);
                repository.replacePositions(tenantId, req.userId(), positionIds, operatorId, now);
                return detailEmployee(tenantId, id);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("该用户或工号已有 OA 员工档案");
            }
        });
    }

    public OaEmployeeResp updateEmployee(long tenantId, long operatorId, long id, OaEmployeeUpdateReq req) {
        return transactionTemplate.execute(status -> {
            OaEmployeeResp employee = repository.lockEmployee(tenantId, id);
            if (employee == null) {
                throw new BusinessException("员工不存在");
            }
            String employeeNo = normalizeEmployeeNo(req.employeeNo());
            requireUniqueEmployeeNo(tenantId, employeeNo, id);
            String employmentStatus = normalizeEmploymentStatus(req.employmentStatus());
            requireSupervisor(tenantId, req.supervisorId(), employee.userId(), id);
            List<Long> positionIds = requireEnabledPositions(tenantId, req.positionIds());
            LocalDateTime now = LocalDateTime.now();
            int rows = repository.updateEmployee(tenantId, id, employeeNo, req
                .supervisorId(), employmentStatus, Boolean.TRUE.equals(req.deptLeader()), req
                    .hireDate(), normalizeLeaveDate(employmentStatus, req.leaveDate()), operatorId, now, req.version());
            if (rows == 0) {
                throw new BusinessException("员工档案已被其他人修改，请刷新后重试");
            }
            repository.replacePositions(tenantId, employee.userId(), positionIds, operatorId, now);
            if ("RESIGNED".equals(employmentStatus) && !"RESIGNED".equals(employee.employmentStatus())) {
                autoHandover(tenantId, operatorId, employee.userId(), req.supervisorId());
            }
            return detailEmployee(tenantId, id);
        });
    }

    /**
     * 离职员工手工转交全部运行中待办；没有有效直属主管时由管理员指定接收人。
     */
    public OaHandoverResp handover(long tenantId, long operatorId, long employeeId, long targetUserId, String reason) {
        OaEmployeeResp employee = repository.detailEmployee(tenantId, employeeId);
        if (employee == null) {
            throw new BusinessException("员工不存在");
        }
        if (employee.userId() == targetUserId) {
            throw new BusinessException("不能转交给员工本人");
        }
        try {
            int transferred = taskHandover.transferRunningTasks(tenantId, operatorId, employee
                .userId(), targetUserId, normalizeReason(reason));
            return new OaHandoverResp(transferred);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    private void autoHandover(long tenantId, long operatorId, long userId, Long supervisorId) {
        if (supervisorId == null || supervisorId == userId) {
            return;
        }
        try {
            taskHandover.transferRunningTasks(tenantId, operatorId, userId, supervisorId, "员工离职自动转交直属主管");
        } catch (IllegalArgumentException ignored) {
            // 主管也已离职等场景不阻塞离职办理，管理员可通过手工转交补做
        }
    }

    public List<OaEmployeeOptionResp> employeeOptions(long tenantId, String keyword) {
        return repository.employeeOptions(tenantId, keyword, MAX_OPTIONS);
    }

    public List<OaEmployeeOptionResp> userOptions(long tenantId, String keyword) {
        return repository.userOptions(tenantId, keyword, MAX_OPTIONS);
    }

    public List<OaPositionResp> pagePositions(long tenantId, String keyword, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return repository.pagePositions(tenantId, keyword, status, (safePage - 1) * safeSize, safeSize);
    }

    public long countPositions(long tenantId, String keyword, String status) {
        return repository.countPositions(tenantId, keyword, status);
    }

    public OaPositionResp detailPosition(long tenantId, long id) {
        OaPositionResp position = repository.detailPosition(tenantId, id);
        if (position == null) {
            throw new BusinessException("岗位不存在");
        }
        return position;
    }

    public OaPositionResp createPosition(long tenantId, long operatorId, OaPositionCreateReq req) {
        String code = req.code().trim();
        if (repository.positionCodeExists(tenantId, code, null)) {
            throw new BusinessException("岗位编码已存在");
        }
        try {
            long id = repository.insertPosition(tenantId, code, req.name().trim(), req.sort(), operatorId, LocalDateTime
                .now());
            return detailPosition(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("岗位编码已存在");
        }
    }

    public OaPositionResp updatePosition(long tenantId, long operatorId, long id, OaPositionUpdateReq req) {
        detailPosition(tenantId, id);
        if (!POSITION_STATUSES.contains(req.status())) {
            throw new BusinessException("岗位状态只能是 ENABLED 或 DISABLED");
        }
        int rows = repository.updatePosition(tenantId, id, req.name().trim(), req.sort(), req
            .status(), operatorId, LocalDateTime.now(), req.version());
        if (rows == 0) {
            throw new BusinessException("岗位已被其他人修改，请刷新后重试");
        }
        return detailPosition(tenantId, id);
    }

    public List<OaPositionOptionResp> positionOptions(long tenantId) {
        return repository.positionOptions(tenantId, MAX_OPTIONS);
    }

    private void requireUniqueEmployeeNo(long tenantId, String employeeNo, Long excludeId) {
        if (repository.employeeNoExists(tenantId, employeeNo, excludeId)) {
            throw new BusinessException("工号已被其他员工使用");
        }
    }

    private void requireSupervisor(long tenantId, Long supervisorId, long employeeUserId, long employeeId) {
        if (supervisorId == null) {
            return;
        }
        if (supervisorId == employeeUserId) {
            throw new BusinessException("直属主管不能是员工本人");
        }
        OaEmployeeResp supervisor = repository.employeeByUserId(tenantId, supervisorId);
        if (supervisor == null || !"ACTIVE".equals(supervisor.employmentStatus())) {
            throw new BusinessException("直属主管不是本租户在职员工");
        }
        if (employeeId > 0 && supervisor.id() == employeeId) {
            throw new BusinessException("直属主管不能是员工本人");
        }
        Set<Long> visited = new LinkedHashSet<>();
        Long cursor = supervisorId;
        while (cursor != null) {
            if (cursor == employeeUserId) {
                throw new BusinessException("直属主管不能形成环路");
            }
            if (!visited.add(cursor)) {
                throw new BusinessException("直属主管关系已存在环路，请先修正主管链");
            }
            cursor = repository.supervisorOf(tenantId, cursor);
        }
    }

    private List<Long> requireEnabledPositions(long tenantId, List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = positionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.size() != positionIds.size() || distinct.stream().anyMatch(id -> id <= 0)) {
            throw new BusinessException("岗位 ID 非法");
        }
        List<Long> enabled = repository.enabledPositionIds(tenantId, distinct);
        if (enabled.size() != distinct.size()) {
            throw new BusinessException("岗位不存在、已停用或不属于当前租户");
        }
        return distinct;
    }

    private static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "离职待办转交";
        }
        String reason = raw.trim();
        if (reason.length() > 500) {
            throw new BusinessException("转交原因不能超过 500 个字符");
        }
        return reason;
    }

    private static String normalizeEmployeeNo(String employeeNo) {
        return employeeNo == null || employeeNo.isBlank() ? null : employeeNo.trim();
    }

    private static String normalizeEmploymentStatus(String status) {
        String normalized = status == null || status.isBlank() ? "ACTIVE" : status.trim();
        if (!EMPLOYMENT_STATUSES.contains(normalized)) {
            throw new BusinessException("在职状态只能是 ACTIVE、SUSPENDED 或 RESIGNED");
        }
        return normalized;
    }

    private static LocalDate normalizeLeaveDate(String employmentStatus, LocalDate leaveDate) {
        if ("ACTIVE".equals(employmentStatus)) {
            return null;
        }
        if ("RESIGNED".equals(employmentStatus) && leaveDate == null) {
            return LocalDate.now();
        }
        return leaveDate;
    }
}
