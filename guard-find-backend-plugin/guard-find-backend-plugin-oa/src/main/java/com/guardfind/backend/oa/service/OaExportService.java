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

import cn.idev.excel.FastExcel;
import com.guardfind.backend.oa.model.OaExportCreateReq;
import com.guardfind.backend.oa.model.OaExportTaskResp;
import com.guardfind.backend.oa.repository.OaStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * OA 异步导出：登记任务后由定时作业生成 xlsx，文件写在不对外映射的导出目录，仅发起人凭权限下载，过期即删。
 *
 * <p>
 * 申请明细不含表单数据快照；用车记录中的司机电话按“前 3 后 4”脱敏后才写入文件。
 * </p>
 */
@Slf4j
@Service
public class OaExportService {

    public static final String TYPE_APPLICATION = "APPLICATION";
    public static final String TYPE_VEHICLE_USE = "VEHICLE_USE";
    private static final int MAX_ACTIVE_PER_USER = 3;
    private static final int LIST_LIMIT = 20;
    private static final int BATCH = 5;
    private static final long STALE_MINUTES = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final Map<String, String> BUSINESS_LABELS = Map.ofEntries(Map.entry("GENERIC", "通用审批"), Map
        .entry("LEAVE", "请假"), Map.entry("EXPENSE", "报销"), Map.entry("TRAVEL", "出差"), Map.entry("LOAN", "借款"), Map
            .entry("OVERTIME", "加班"), Map.entry("OUTING", "外出"), Map.entry("ATTENDANCE_CORRECTION", "补卡"), Map
                .entry("PURCHASE", "采购"), Map.entry("SUPPLY_REQUEST", "用品领用"), Map.entry("ASSET_SCRAP", "资产报废"), Map
                    .entry("CONTRACT", "合同"), Map.entry("SEAL_USE", "用印"), Map.entry("DOCUMENT", "公文知识"), Map
                        .entry("MEETING_BOOKING", "会议室预订"), Map.entry("VEHICLE_USE", "用车"));
    private static final Map<String, String> APPLICATION_STATUS = Map
        .of("RUNNING", "审批中", "APPROVED", "已通过", "REJECTED", "已驳回", "WITHDRAWN", "已撤回", "CANCELLED", "已终止");
    private static final Map<String, String> VEHICLE_STATUS = Map
        .of("APPROVING", "审批中", "PENDING_DISPATCH", "待调度", "DISPATCHED", "已调度", "IN_USE", "用车中", "RETURNED", "已归还", "CANCELLED", "已取消", "CLOSED", "已关闭", "EXPIRED", "已过期");

    private final OaStatsRepository repository;
    private final Path exportDir;
    private final int maxRows;
    private final long expireHours;

    public OaExportService(OaStatsRepository repository,
                           @Value("${oa.export.dir:./data/oa-export}") String exportDir,
                           @Value("${oa.export.max-rows:50000}") int maxRows,
                           @Value("${oa.export.expire-hours:24}") long expireHours) {
        this.repository = repository;
        this.exportDir = Paths.get(exportDir).toAbsolutePath().normalize();
        this.maxRows = Math.max(1, maxRows);
        this.expireHours = Math.max(1, expireHours);
    }

    public OaExportTaskResp create(long tenantId, long userId, OaExportCreateReq req) {
        String type = req.exportType().trim();
        if (!TYPE_APPLICATION.equals(type) && !TYPE_VEHICLE_USE.equals(type)) {
            throw new BusinessException("不支持的导出类型");
        }
        OaStatsService.requireRange(req.from(), req.to());
        String businessType = TYPE_APPLICATION.equals(type) ? OaStatsService.blankToNull(req.businessType()) : null;
        String requestId = req.requestId().trim();
        OaExportTaskResp existing = repository.exportByRequest(tenantId, userId, requestId);
        if (existing != null) {
            return requireSameParams(existing, type, req.from(), req.to(), businessType);
        }
        if (repository.countActiveExports(tenantId, userId) >= MAX_ACTIVE_PER_USER) {
            throw new BusinessException("已有 " + MAX_ACTIVE_PER_USER + " 个导出任务在排队，请稍后再试");
        }
        repository.insertExport(tenantId, userId, requestId, type, req.from(), req.to(), businessType, LocalDateTime
            .now());
        return requireSameParams(repository.exportByRequest(tenantId, userId, requestId), type, req.from(), req
            .to(), businessType);
    }

    public List<OaExportTaskResp> mine(long tenantId, long userId) {
        return repository.myExports(tenantId, userId, LIST_LIMIT);
    }

    /**
     * 下载鉴权：只有发起人本人、同租户、已完成且未过期的任务可下载，路径必须落在导出目录内。
     */
    public DownloadFile authorizeDownload(long tenantId, long userId, long id) {
        OaStatsRepository.ExportTask task = repository.exportTask(id);
        if (task == null || task.tenantId() != tenantId || task.userId() != userId) {
            throw new BusinessException("导出任务不存在或无权下载");
        }
        if ("EXPIRED".equals(task.status()) || task.expireTime() != null && !task.expireTime()
            .isAfter(LocalDateTime.now())) {
            throw new BusinessException("导出文件已过期，请重新导出");
        }
        if (!"DONE".equals(task.status()) || task.filePath() == null) {
            throw new BusinessException("导出文件尚未生成");
        }
        Path file = exportDir.resolve(task.filePath()).normalize();
        if (!file.startsWith(exportDir) || !Files.isRegularFile(file)) {
            throw new BusinessException("导出文件不存在或已被清理");
        }
        return new DownloadFile(file, task.fileName());
    }

    /**
     * 生成待处理任务；返回本次完成（成功或失败）的任务数。
     */
    public int runPending() {
        LocalDateTime now = LocalDateTime.now();
        int stale = repository.failStaleRunning(now.minusMinutes(STALE_MINUTES), now);
        if (stale > 0) {
            log.warn("OA 导出任务超时 {} 条，已置为失败", stale);
        }
        int handled = 0;
        for (Long id : repository.pendingExportIds(BATCH)) {
            if (repository.claimExport(id, LocalDateTime.now())) {
                generate(repository.exportTask(id));
                handled++;
            }
        }
        return handled;
    }

    public int purgeExpired() {
        int purged = 0;
        for (OaStatsRepository.ExportTask task : repository.expiredExports(LocalDateTime.now(), 100)) {
            if (task.filePath() != null) {
                Path file = exportDir.resolve(task.filePath()).normalize();
                try {
                    if (file.startsWith(exportDir)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    log.warn("删除过期导出文件失败 taskId={}", task.id(), e);
                    continue;
                }
            }
            if (repository.markExpired(task.id())) {
                purged++;
            }
        }
        return purged;
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String value = phone.trim();
        if (value.length() < 7) {
            return "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private void generate(OaStatsRepository.ExportTask task) {
        LocalDateTime start = task.from().atStartOfDay();
        LocalDateTime end = task.to().plusDays(1).atStartOfDay();
        try {
            List<List<String>> head;
            List<List<Object>> data = new ArrayList<>();
            String title;
            if (TYPE_VEHICLE_USE.equals(task.exportType())) {
                title = "用车记录";
                head = head("编号", "申请人", "事由", "目的地", "人数", "用车开始", "用车结束", "状态", "车牌号", "司机", "司机电话", "出车里程", "归还里程", "油费/电费", "路桥停车费", "其他费用");
                List<OaStatsRepository.VehicleExportRow> rows = repository.exportVehicleRequests(task
                    .tenantId(), start, end, maxRows + 1);
                requireWithinLimit(rows.size());
                for (OaStatsRepository.VehicleExportRow row : rows) {
                    data.add(cells(row.id(), row.applicantName(), row.purpose(), row.destination(), row
                        .passengers(), time(row.startTime()), time(row.endTime()), VEHICLE_STATUS.getOrDefault(row
                            .status(), row.status()), row.plateNo(), row.driverName(), maskPhone(row.driverPhone()), row
                                .startMileage(), row.endMileage(), row.fuelCost(), row.tollCost(), row.otherCost()));
                }
            } else {
                title = "申请明细";
                head = head("编号", "业务类型", "业务单号", "标题", "申请人", "部门", "状态", "提交时间", "结束时间", "耗时(小时)", "金额");
                List<OaStatsRepository.ApplicationExportRow> rows = repository.exportApplications(task
                    .tenantId(), start, end, task.businessType(), maxRows + 1);
                requireWithinLimit(rows.size());
                for (OaStatsRepository.ApplicationExportRow row : rows) {
                    data.add(cells(row.id(), BUSINESS_LABELS.getOrDefault(row.businessType(), row.businessType()), row
                        .businessKey(), row.title(), row.applicantName(), row.deptName(), APPLICATION_STATUS
                            .getOrDefault(row.status(), row.status()), time(row.submittedTime()), time(row
                                .finishedTime()), hours(row.submittedTime(), row.finishedTime()), row.amount()));
                }
            }
            Path dir = exportDir.resolve(Long.toString(task.tenantId()));
            Files.createDirectories(dir);
            String stored = task.id() + "-" + UUID.randomUUID() + ".xlsx";
            Path file = dir.resolve(stored);
            FastExcel.write(file.toFile()).head(head).sheet(title).doWrite(data);
            LocalDateTime now = LocalDateTime.now();
            String fileName = title + "_" + task.from() + "_" + task.to() + "_" + now.format(FILE_TIME) + ".xlsx";
            repository.finishExport(task.id(), fileName, task.tenantId() + "/" + stored, data.size(), now, now
                .plusHours(expireHours));
        } catch (BusinessException e) {
            repository.failExport(task.id(), e.getMessage(), LocalDateTime.now());
        } catch (IOException | RuntimeException e) {
            log.error("OA 导出任务生成失败 taskId={}", task.id(), e);
            repository.failExport(task.id(), "生成文件失败，请稍后重试", LocalDateTime.now());
        }
    }

    private void requireWithinLimit(int size) {
        if (size > maxRows) {
            throw new BusinessException("导出行数超过上限 " + maxRows + "，请缩小日期范围或按业务类型筛选");
        }
    }

    private static OaExportTaskResp requireSameParams(OaExportTaskResp task,
                                                      String type,
                                                      LocalDate from,
                                                      LocalDate to,
                                                      String businessType) {
        if (!type.equals(task.exportType()) || !from.equals(task.dateFrom()) || !to.equals(task.dateTo()) || !Objects
            .equals(businessType, task.businessType())) {
            throw new BusinessException("请求号已用于其他导出");
        }
        return task;
    }

    private static List<List<String>> head(String... titles) {
        List<List<String>> head = new ArrayList<>();
        for (String title : titles) {
            head.add(List.of(title));
        }
        return head;
    }

    private static List<Object> cells(Object... values) {
        List<Object> row = new ArrayList<>(values.length);
        for (Object value : values) {
            row.add(value == null ? "" : value);
        }
        return row;
    }

    private static String time(LocalDateTime value) {
        return value == null ? "" : value.format(TIME);
    }

    private static String hours(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return "";
        }
        return BigDecimal.valueOf(Duration.between(from, to).toMinutes())
            .divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP)
            .toPlainString();
    }

    public record DownloadFile(Path path, String fileName) {
    }
}
