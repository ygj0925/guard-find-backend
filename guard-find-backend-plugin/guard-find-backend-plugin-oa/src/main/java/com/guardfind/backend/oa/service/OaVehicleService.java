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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.model.OaContractTemplateResp;
import com.guardfind.backend.oa.model.OaDriverReq;
import com.guardfind.backend.oa.model.OaDriverResp;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaResourceCancelReq;
import com.guardfind.backend.oa.model.OaVehicleCandidatesResp;
import com.guardfind.backend.oa.model.OaVehicleDepartReq;
import com.guardfind.backend.oa.model.OaVehicleDispatchReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceCompleteReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceReq;
import com.guardfind.backend.oa.model.OaVehicleMaintenanceResp;
import com.guardfind.backend.oa.model.OaVehicleReq;
import com.guardfind.backend.oa.model.OaVehicleRequestResp;
import com.guardfind.backend.oa.model.OaVehicleResp;
import com.guardfind.backend.oa.model.OaVehicleReturnReq;
import com.guardfind.backend.oa.repository.OaVehicleRepository;
import com.guardfind.backend.oa.repository.OaVehicleRepository.DriverRow;
import com.guardfind.backend.oa.repository.OaVehicleRepository.ExpiringRequest;
import com.guardfind.backend.oa.repository.OaVehicleRepository.MaintenanceRow;
import com.guardfind.backend.oa.repository.OaVehicleRepository.RequestFilter;
import com.guardfind.backend.oa.repository.OaVehicleRepository.RequestRow;
import com.guardfind.backend.oa.repository.OaVehicleRepository.VehicleRow;
import com.guardfind.backend.oa.resource.OaVehicleRequestHook;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.guardfind.backend.oa.service.OaContractService.withOptions;
import static com.guardfind.backend.oa.service.OaMeetingService.checked;
import static com.guardfind.backend.oa.service.OaPurchaseService.blankToNull;
import static com.guardfind.backend.oa.service.OaPurchaseService.field;
import static com.guardfind.backend.oa.service.OaPurchaseService.offset;
import static com.guardfind.backend.oa.service.OaPurchaseService.option;
import static com.guardfind.backend.oa.service.OaPurchaseService.safeSize;

/**
 * 车辆调度：车辆/司机/维保档案，审批通过的用车申请由调度员指派车辆与司机，出车与归还单独登记且按请求号幂等。
 * 冲突判定按"申请 → 车辆 → 司机"的顺序加锁后执行，同一车辆或司机的调度串行。
 */
@Service
public class OaVehicleService {

    public static final String FORM_KEY = "vehicle_use";
    public static final String MODEL_KEY = "vehicle_pilot";
    public static final String EVENT_DISPATCHED = "VEHICLE_DISPATCHED";
    public static final String EVENT_CANCELLED = "VEHICLE_CANCELLED";
    public static final String EVENT_EXPIRED = "VEHICLE_EXPIRED";
    private static final String COL_DISPATCH = "dispatch_request_id";
    private static final String COL_DEPART = "depart_request_id";
    private static final String COL_RETURN = "return_request_id";
    private static final int EXPIRY_SCAN_LIMIT = 2000;
    private static final Set<String> RESOURCE_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> MAINTENANCE_STATUSES = Set.of("ACTIVE", "DONE");
    private static final Set<String> REQUEST_STATUSES = Set
        .of(OaVehicleRequestHook.STATUS_APPROVING, OaVehicleRequestHook.STATUS_PENDING_DISPATCH, OaVehicleRequestHook.STATUS_DISPATCHED, OaVehicleRequestHook.STATUS_IN_USE, OaVehicleRequestHook.STATUS_RETURNED, OaVehicleRequestHook.STATUS_CANCELLED, OaVehicleRequestHook.STATUS_CLOSED, OaVehicleRequestHook.STATUS_EXPIRED);
    private static final Set<String> CANCELLABLE = Set
        .of(OaVehicleRequestHook.STATUS_PENDING_DISPATCH, OaVehicleRequestHook.STATUS_DISPATCHED);

    private final OaVehicleRepository repository;
    private final OaStaffDirectory directory;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaVehicleService(OaVehicleRepository repository,
                            OaStaffDirectory directory,
                            OaFormService formService,
                            OaProcessModelService modelService,
                            OaOutboxService outboxService,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.directory = directory;
        this.formService = formService;
        this.modelService = modelService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<OaVehicleResp> vehicles(long tenantId, String status) {
        return repository.vehicles(tenantId, checked(status, RESOURCE_STATUSES, "车辆"));
    }

    public OaVehicleResp createVehicle(long tenantId, long userId, OaVehicleReq req) {
        try {
            long id = repository.insertVehicle(tenantId, req.plateNo().trim(), req.model().trim(), req.seats(), req
                .mileage() == null ? 0 : req.mileage(), status(req.status()), blankToNull(req
                    .remark()), userId, LocalDateTime.now());
            return repository.vehicle(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("车牌号已存在");
        }
    }

    public OaVehicleResp updateVehicle(long tenantId, long userId, long id, OaVehicleReq req) {
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        if (repository.vehicle(tenantId, id) == null) {
            throw new BusinessException("车辆不存在");
        }
        try {
            if (repository.updateVehicle(tenantId, id, req.version(), req.plateNo().trim(), req.model().trim(), req
                .seats(), req.mileage(), status(req.status()), blankToNull(req.remark()), userId, LocalDateTime
                    .now()) != 1) {
                throw new BusinessException("车辆已被修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("车牌号已存在");
        }
        return repository.vehicle(tenantId, id);
    }

    public List<OaDriverResp> drivers(long tenantId, String status) {
        return repository.drivers(tenantId, checked(status, RESOURCE_STATUSES, "司机"));
    }

    public OaDriverResp createDriver(long tenantId, long userId, OaDriverReq req) {
        requireDriverAccount(tenantId, req.userId());
        try {
            long id = repository.insertDriver(tenantId, req.name().trim(), blankToNull(req.phone()), req
                .userId(), status(req.status()), blankToNull(req.remark()), userId, LocalDateTime.now());
            return repository.driver(tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该员工已登记为司机");
        }
    }

    public OaDriverResp updateDriver(long tenantId, long userId, long id, OaDriverReq req) {
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        if (repository.driver(tenantId, id) == null) {
            throw new BusinessException("司机不存在");
        }
        requireDriverAccount(tenantId, req.userId());
        try {
            if (repository.updateDriver(tenantId, id, req.version(), req.name().trim(), blankToNull(req.phone()), req
                .userId(), status(req.status()), blankToNull(req.remark()), userId, LocalDateTime.now()) != 1) {
                throw new BusinessException("司机已被修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该员工已登记为司机");
        }
        return repository.driver(tenantId, id);
    }

    public List<OaVehicleMaintenanceResp> maintenances(long tenantId, Long vehicleId, String status) {
        return repository.maintenances(tenantId, vehicleId, checked(status, MAINTENANCE_STATUSES, "维保"), 200);
    }

    /**
     * 登记维保停用区间：区间内已有调度行程或其他维保时拒绝，需先改派。
     */
    public OaVehicleMaintenanceResp createMaintenance(long tenantId, long userId, OaVehicleMaintenanceReq req) {
        String requestId = req.requestId().trim();
        LocalDateTime start = req.startTime().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime end = req.endTime().truncatedTo(ChronoUnit.MINUTES);
        Long id = transactionTemplate.execute(status -> {
            VehicleRow vehicle = repository.lockVehicle(tenantId, req.vehicleId());
            if (vehicle == null) {
                throw new BusinessException("车辆不存在");
            }
            MaintenanceRow replayed = repository.maintenanceByRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed.vehicleId() != vehicle.id() || !replayed.start().equals(start) || !replayed.end()
                    .equals(end)) {
                    throw new BusinessException("请求号已被使用");
                }
                return replayed.id();
            }
            if (!end.isAfter(start)) {
                throw new BusinessException("结束时间必须晚于开始时间");
            }
            LocalDateTime now = LocalDateTime.now();
            if (repository.countVehicleTrips(tenantId, vehicle.id(), 0, start, end, now) > 0) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」在该时段已有调度行程，请先改派或取消");
            }
            if (repository.countMaintenances(tenantId, vehicle.id(), start, end, now) > 0) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」在该时段已有维保登记");
            }
            try {
                return repository.insertMaintenance(tenantId, vehicle.id(), requestId, req
                    .maintType(), start, end, blankToNull(req.remark()), userId, now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
        });
        return repository.maintenance(tenantId, id);
    }

    /**
     * 完工：提前完工时把结束时间截到当前，车辆随即恢复可调度。
     */
    public OaVehicleMaintenanceResp completeMaintenance(long tenantId,
                                                        long userId,
                                                        long id,
                                                        OaVehicleMaintenanceCompleteReq req) {
        transactionTemplate.executeWithoutResult(status -> {
            MaintenanceRow maintenance = repository.lockMaintenance(tenantId, id);
            if (maintenance == null) {
                throw new BusinessException("维保记录不存在");
            }
            if ("DONE".equals(maintenance.status())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime endTime = maintenance.end();
            if (now.isBefore(endTime)) {
                endTime = now.isBefore(maintenance.start()) ? maintenance.start() : now;
            }
            if (repository.completeMaintenance(tenantId, id, endTime, req == null ? null : req.cost(), req == null
                ? null
                : blankToNull(req.remark()), userId, now) != 1) {
                throw new BusinessException("维保状态已变化，请刷新后重试");
            }
        });
        return repository.maintenance(tenantId, id);
    }

    public List<OaVehicleRequestResp> requests(long tenantId,
                                               String status,
                                               Long applicantId,
                                               Long vehicleId,
                                               int page,
                                               int size) {
        int limit = safeSize(size);
        return repository
            .requests(tenantId, new RequestFilter(checked(status, REQUEST_STATUSES, "用车申请"), applicantId, vehicleId), offset(page, limit), limit);
    }

    public long countRequests(long tenantId, String status, Long applicantId, Long vehicleId) {
        return repository
            .countRequests(tenantId, new RequestFilter(checked(status, REQUEST_STATUSES, "用车申请"), applicantId, vehicleId));
    }

    public OaVehicleRequestResp request(long tenantId, Long applicantId, long id) {
        OaVehicleRequestResp request = repository.request(tenantId, id);
        if (request == null || applicantId != null && request.applicantId() != applicantId) {
            throw new BusinessException("用车申请不存在或无权查看");
        }
        return request;
    }

    /**
     * 待调度申请可指派的车辆（启用、座位够、时段内无行程与维保）与司机（启用、时段内无行程）。
     */
    public OaVehicleCandidatesResp candidates(long tenantId, long id) {
        OaVehicleRequestResp request = request(tenantId, null, id);
        if (!OaVehicleRequestHook.STATUS_PENDING_DISPATCH.equals(request.status())) {
            throw new BusinessException("只有待调度的申请可以选择车辆");
        }
        LocalDateTime now = LocalDateTime.now();
        int seats = requiredSeats(request.passengers(), request.needDriver());
        List<OaVehicleResp> vehicles = repository.vehicles(tenantId, "ENABLED")
            .stream()
            .filter(v -> v.seats() >= seats)
            .filter(v -> repository.countVehicleTrips(tenantId, v.id(), id, request.startTime(), request
                .endTime(), now) == 0 && repository.countMaintenances(tenantId, v.id(), request.startTime(), request
                    .endTime(), now) == 0)
            .toList();
        List<OaDriverResp> drivers = repository.drivers(tenantId, "ENABLED")
            .stream()
            .filter(d -> repository.countDriverTrips(tenantId, d.id(), id, request.startTime(), request
                .endTime(), now) == 0)
            .toList();
        return new OaVehicleCandidatesResp(vehicles, drivers);
    }

    public OaVehicleRequestResp dispatch(long tenantId, long operatorId, long id, OaVehicleDispatchReq req) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            // 锁序固定为 车辆 → 司机 → 申请，与出车/归还一致，避免并发调度互相等待行锁形成死锁
            VehicleRow vehicle = repository.lockVehicle(tenantId, req.vehicleId());
            DriverRow driver = req.driverId() == null ? null : repository.lockDriver(tenantId, req.driverId());
            RequestRow request = requireRequest(tenantId, id);
            if (replayed(tenantId, COL_DISPATCH, requestId, id)) {
                return;
            }
            requireStatus(request, OaVehicleRequestHook.STATUS_PENDING_DISPATCH);
            LocalDateTime now = LocalDateTime.now();
            if (!request.end().isAfter(now)) {
                throw new BusinessException("用车时段已过，不能调度");
            }
            if (vehicle == null) {
                throw new BusinessException("车辆不存在");
            }
            if (!"ENABLED".equals(vehicle.status())) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」已停用");
            }
            int seats = requiredSeats(request.passengers(), request.needDriver());
            if (vehicle.seats() < seats) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」座位数不足，至少需要 " + seats + " 座");
            }
            if (req.driverId() != null) {
                if (driver == null) {
                    throw new BusinessException("司机不存在");
                }
                if (!"ENABLED".equals(driver.status())) {
                    throw new BusinessException("司机「" + driver.name() + "」已停用");
                }
            }
            if (request.needDriver() && driver == null) {
                throw new BusinessException("该申请需要司机，请指派司机");
            }
            if (repository.countVehicleTrips(tenantId, vehicle.id(), id, request.start(), request.end(), now) > 0) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」该时段已被调度");
            }
            if (repository.countMaintenances(tenantId, vehicle.id(), request.start(), request.end(), now) > 0) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」该时段在维保中");
            }
            if (driver != null && repository.countDriverTrips(tenantId, driver.id(), id, request.start(), request
                .end(), now) > 0) {
                throw new BusinessException("司机「" + driver.name() + "」该时段已有出车任务");
            }
            try {
                if (repository.markDispatched(tenantId, id, requestId, vehicle.id(), driver == null
                    ? null
                    : driver.id(), blankToNull(req.remark()), operatorId, now) != 1) {
                    throw new BusinessException("用车申请状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            List<Long> recipients = new ArrayList<>();
            recipients.add(request.applicantId());
            if (driver != null && driver.userId() != null) {
                recipients.add(driver.userId());
            }
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(request
                .applicationId()), EVENT_DISPATCHED, "VEHICLE_DISPATCHED:" + id, request
                    .applicationId(), null, EVENT_DISPATCHED, "用车已派车", "车辆 " + vehicle.plateNo() + (driver == null
                        ? "（自驾）"
                        : "，司机 " + driver.name()) + "，用车时间 " + request.start() + " 至 " + request.end(), recipients);
        });
        return repository.request(tenantId, id);
    }

    public OaVehicleRequestResp depart(long tenantId, long operatorId, long id, OaVehicleDepartReq req) {
        String requestId = req.requestId().trim();
        Long assignedVehicleId = assignedVehicleId(tenantId, id);
        transactionTemplate.executeWithoutResult(status -> {
            VehicleRow locked = assignedVehicleId == null ? null : repository.lockVehicle(tenantId, assignedVehicleId);
            RequestRow request = requireRequest(tenantId, id);
            if (replayed(tenantId, COL_DEPART, requestId, id)) {
                return;
            }
            requireStatus(request, OaVehicleRequestHook.STATUS_DISPATCHED);
            VehicleRow vehicle = requireAssignedVehicle(request, locked);
            if (req.startMileage() < vehicle.mileage()) {
                throw new BusinessException("出车里程不能小于车辆当前里程 " + vehicle.mileage());
            }
            if (repository.countInUse(tenantId, vehicle.id(), id) > 0) {
                throw new BusinessException("车辆「" + vehicle.plateNo() + "」仍在出车中，请先登记上一趟归还");
            }
            try {
                if (repository.markDeparted(tenantId, id, requestId, req.startMileage(), operatorId, LocalDateTime
                    .now()) != 1) {
                    throw new BusinessException("用车申请状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
        });
        return repository.request(tenantId, id);
    }

    public OaVehicleRequestResp returnVehicle(long tenantId, long operatorId, long id, OaVehicleReturnReq req) {
        String requestId = req.requestId().trim();
        Long assignedVehicleId = assignedVehicleId(tenantId, id);
        transactionTemplate.executeWithoutResult(status -> {
            VehicleRow locked = assignedVehicleId == null ? null : repository.lockVehicle(tenantId, assignedVehicleId);
            RequestRow request = requireRequest(tenantId, id);
            if (replayed(tenantId, COL_RETURN, requestId, id)) {
                return;
            }
            requireStatus(request, OaVehicleRequestHook.STATUS_IN_USE);
            if (request.startMileage() != null && req.endMileage() < request.startMileage()) {
                throw new BusinessException("归还里程不能小于出车里程 " + request.startMileage());
            }
            VehicleRow vehicle = requireAssignedVehicle(request, locked);
            LocalDateTime now = LocalDateTime.now();
            try {
                if (repository.markReturned(tenantId, id, requestId, req.endMileage(), req.fuelCost(), req
                    .tollCost(), req.otherCost(), blankToNull(req.remark()), operatorId, now) != 1) {
                    throw new BusinessException("用车申请状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            repository.raiseMileage(tenantId, vehicle.id(), req.endMileage(), now);
        });
        return repository.request(tenantId, id);
    }

    /**
     * 调度员取消待调度或已派车的申请（车辆故障、行程取消等），必须填写原因并通知申请人与司机。
     */
    public OaVehicleRequestResp cancelByDispatcher(long tenantId, long operatorId, long id, OaResourceCancelReq req) {
        String reason = req == null ? null : blankToNull(req.reason());
        if (reason == null) {
            throw new BusinessException("请填写取消原因");
        }
        transactionTemplate
            .executeWithoutResult(status -> cancel(tenantId, operatorId, requireRequest(tenantId, id), reason, true));
        return repository.request(tenantId, id);
    }

    /**
     * 申请人取消本人尚未出车的申请；审批中的申请须撤回。
     */
    public OaVehicleRequestResp cancelMine(long tenantId, long userId, long id, OaResourceCancelReq req) {
        transactionTemplate.executeWithoutResult(status -> {
            RequestRow request = repository.lockRequest(tenantId, id);
            if (request == null || request.applicantId() != userId) {
                throw new BusinessException("用车申请不存在或无权操作");
            }
            cancel(tenantId, userId, request, req == null ? null : blankToNull(req.reason()), false);
        });
        return repository.request(tenantId, id);
    }

    /**
     * 用车结束仍未出车的申请（待调度或已派车未出车）置为过期，释放车辆与司机。
     */
    public int expireDispatches(LocalDateTime now) {
        int expired = 0;
        for (ExpiringRequest request : repository.expiredDispatches(now, EXPIRY_SCAN_LIMIT)) {
            Boolean done = transactionTemplate.execute(status -> {
                if (repository.transitionRequest(request.tenantId(), request.id(), request
                    .status(), OaVehicleRequestHook.STATUS_EXPIRED, now) != 1) {
                    return false;
                }
                outboxService.recordNotification(request.tenantId(), OaOutboxService.AGGREGATE_APPLICATION, Long
                    .toString(request.applicationId()), EVENT_EXPIRED, "VEHICLE_EXPIRED:" + request.id(), request
                        .applicationId(), null, EVENT_EXPIRED, "用车申请已过期", "前往「" + request
                            .destination() + "」的用车在结束时间前未出车，已自动过期", List.of(request.applicantId()));
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                expired++;
            }
        }
        return expired;
    }

    public OaContractTemplateResp template(long tenantId, long userId) {
        return transactionTemplate.execute(status -> {
            long formId = publishForm(tenantId, userId, "用车申请", schema());
            OaProcessModelResp model = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(MODEL_KEY, "用车申请", "resource", "直属上级审批；通过后由调度员指派车辆与司机，出车与归还单独登记", BPMN, OaVehicleRequestHook.BUSINESS_TYPE));
            return new OaContractTemplateResp(formId, model.id(), model.modelKey());
        });
    }

    public String schema() {
        try {
            return objectMapper.writeValueAsString(Map.of("fields", List
                .of(field(OaVehicleRequestHook.FIELD_PURPOSE, "用车事由", "textarea", true), field(OaVehicleRequestHook.FIELD_DESTINATION, "目的地", "text", true), field(OaVehicleRequestHook.FIELD_PASSENGERS, "乘车人数（含申请人）", "number", true), field(OaVehicleRequestHook.FIELD_START, "用车开始", "datetime", true), field(OaVehicleRequestHook.FIELD_END, "用车结束", "datetime", true), withOptions(field(OaVehicleRequestHook.FIELD_NEED_DRIVER, "是否需要司机", "radio", true), List
                    .of(option("需要司机", "Y"), option("自驾", "N"))))));
        } catch (Exception e) {
            throw new IllegalStateException("模板 schema 序列化失败", e);
        }
    }

    private void cancel(long tenantId, long operatorId, RequestRow request, String reason, boolean byDispatcher) {
        if (OaVehicleRequestHook.STATUS_CANCELLED.equals(request.status())) {
            return;
        }
        if (OaVehicleRequestHook.STATUS_APPROVING.equals(request.status())) {
            throw new BusinessException(byDispatcher ? "审批中的申请请在审批中驳回" : "审批中的申请请撤回");
        }
        if (!CANCELLABLE.contains(request.status())) {
            throw new BusinessException("用车申请当前状态为" + label(request.status()) + "，不能取消");
        }
        if (repository.cancelRequest(tenantId, request.id(), request.status(), operatorId, reason, LocalDateTime
            .now()) != 1) {
            throw new BusinessException("用车申请状态已变化，请刷新后重试");
        }
        List<Long> recipients = new ArrayList<>();
        if (request.applicantId() != operatorId) {
            recipients.add(request.applicantId());
        }
        if (request.driverId() != null) {
            OaDriverResp driver = repository.driver(tenantId, request.driverId());
            if (driver != null && driver.userId() != null && driver.userId() != operatorId) {
                recipients.add(driver.userId());
            }
        }
        outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(request
            .applicationId()), EVENT_CANCELLED, "VEHICLE_CANCELLED:" + request.id(), request
                .applicationId(), null, EVENT_CANCELLED, "用车已取消", "用车申请已取消" + (reason == null
                    ? ""
                    : "，原因：" + reason), recipients);
    }

    private RequestRow requireRequest(long tenantId, long id) {
        RequestRow request = repository.lockRequest(tenantId, id);
        if (request == null) {
            throw new BusinessException("用车申请不存在");
        }
        return request;
    }

    /**
     * 事务外读取已派车辆，供事务内先锁车辆再锁申请；事务内再与锁定的申请行核对。
     */
    private Long assignedVehicleId(long tenantId, long id) {
        OaVehicleRequestResp request = repository.request(tenantId, id);
        return request == null ? null : request.vehicleId();
    }

    private static VehicleRow requireAssignedVehicle(RequestRow request, VehicleRow locked) {
        if (locked == null || request.vehicleId() == null || locked.id() != request.vehicleId()) {
            throw new BusinessException("用车申请状态已变化，请刷新后重试");
        }
        return locked;
    }

    private boolean replayed(long tenantId, String column, String requestId, long id) {
        Long replayed = repository.requestIdByCommand(tenantId, column, requestId);
        if (replayed == null) {
            return false;
        }
        if (replayed != id) {
            throw new BusinessException("请求号已被使用");
        }
        return true;
    }

    private static void requireStatus(RequestRow request, String expected) {
        if (!expected.equals(request.status())) {
            throw new BusinessException("用车申请当前状态为" + label(request.status()) + "，不能执行该操作");
        }
    }

    private void requireDriverAccount(long tenantId, Long userId) {
        if (userId != null && !directory.activeEmployee(tenantId, userId)) {
            throw new BusinessException("关联账号不是在职员工");
        }
    }

    private static int requiredSeats(int passengers, boolean needDriver) {
        return passengers + (needDriver ? 1 : 0);
    }

    private static String status(String status) {
        return status == null ? "ENABLED" : status;
    }

    static String label(String status) {
        return switch (status) {
            case OaVehicleRequestHook.STATUS_APPROVING -> "审批中";
            case OaVehicleRequestHook.STATUS_PENDING_DISPATCH -> "待调度";
            case OaVehicleRequestHook.STATUS_DISPATCHED -> "已派车";
            case OaVehicleRequestHook.STATUS_IN_USE -> "出车中";
            case OaVehicleRequestHook.STATUS_RETURNED -> "已归还";
            case OaVehicleRequestHook.STATUS_CANCELLED -> "已取消";
            case OaVehicleRequestHook.STATUS_CLOSED -> "已关闭";
            case OaVehicleRequestHook.STATUS_EXPIRED -> "已过期";
            default -> status;
        };
    }

    private long publishForm(long tenantId, long userId, String name, String schema) {
        OaFormResp form = formService
            .create(tenantId, userId, new OaFormCreateReq(FORM_KEY, name, name + "试点模板，业务字段 key 不可修改", schema));
        OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
        if (!published.valid()) {
            throw new BusinessException(name + "表单发布失败：" + String.join("；", published.errors()));
        }
        return form.id();
    }

    public static final String BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          id="Definitions_vehicle" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="vehicle_pilot" name="用车申请" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToEnd</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowToEnd</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToEnd" sourceRef="supervisorApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_vehicle">
            <bpmndi:BPMNPlane id="BPMNPlane_vehicle" bpmnElement="vehicle_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="392" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToEnd_di" bpmnElement="flowToEnd">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="392" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;
}
