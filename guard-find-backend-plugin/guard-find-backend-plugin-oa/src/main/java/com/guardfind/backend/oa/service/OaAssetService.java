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
import com.guardfind.backend.oa.asset.OaAssetScrapHook;
import com.guardfind.backend.oa.asset.OaAssetStatus;
import com.guardfind.backend.oa.asset.OaSupplyRequestHook;
import com.guardfind.backend.oa.model.OaAssetActionReq;
import com.guardfind.backend.oa.model.OaAssetCreateReq;
import com.guardfind.backend.oa.model.OaAssetDetailResp;
import com.guardfind.backend.oa.model.OaAssetResp;
import com.guardfind.backend.oa.model.OaAssetScrapResp;
import com.guardfind.backend.oa.model.OaAssetTemplateResp;
import com.guardfind.backend.oa.model.OaFormCreateReq;
import com.guardfind.backend.oa.model.OaFormPublishResp;
import com.guardfind.backend.oa.model.OaFormResp;
import com.guardfind.backend.oa.model.OaOperationReq;
import com.guardfind.backend.oa.model.OaProcessModelCreateReq;
import com.guardfind.backend.oa.model.OaProcessModelResp;
import com.guardfind.backend.oa.model.OaStocktakeCount;
import com.guardfind.backend.oa.model.OaStocktakeCountReq;
import com.guardfind.backend.oa.model.OaStocktakeCreateReq;
import com.guardfind.backend.oa.model.OaStocktakeDetailResp;
import com.guardfind.backend.oa.model.OaStocktakeLineResp;
import com.guardfind.backend.oa.model.OaStocktakeResp;
import com.guardfind.backend.oa.model.OaSupplyItemReq;
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.model.OaSupplyLedgerResp;
import com.guardfind.backend.oa.model.OaSupplyRequestDetailResp;
import com.guardfind.backend.oa.model.OaSupplyRequestItemResp;
import com.guardfind.backend.oa.model.OaSupplyRequestResp;
import com.guardfind.backend.oa.model.OaSupplyStockReq;
import com.guardfind.backend.oa.repository.OaAssetRepository;
import com.guardfind.backend.oa.repository.OaAssetRepository.AssetRow;
import com.guardfind.backend.oa.repository.OaAssetRepository.ScrapRow;
import com.guardfind.backend.oa.repository.OaAssetRepository.SupplyRequestRow;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.guardfind.backend.oa.service.OaPurchaseService.blankToNull;
import static com.guardfind.backend.oa.service.OaPurchaseService.column;
import static com.guardfind.backend.oa.service.OaPurchaseService.field;
import static com.guardfind.backend.oa.service.OaPurchaseService.offset;
import static com.guardfind.backend.oa.service.OaPurchaseService.option;
import static com.guardfind.backend.oa.service.OaPurchaseService.safeSize;

/**
 * 资产与用品：用品档案与出入库台账、领用发放（与审批分离）、资产卡片领用/归还/调拨/维修、报废执行与盘点调整。
 */
@Service
public class OaAssetService {

    public static final String SUPPLY_FORM_KEY = "supply_request";
    public static final String SUPPLY_MODEL_KEY = "supply_pilot";
    public static final String SCRAP_FORM_KEY = "asset_scrap";
    public static final String SCRAP_MODEL_KEY = "asset_scrap_pilot";
    public static final String EVENT_SUPPLY_ISSUED = "SUPPLY_ISSUED";
    public static final String EVENT_ASSET_SCRAPPED = "ASSET_SCRAPPED";
    public static final String REF_MANUAL = "MANUAL";
    public static final String REF_ISSUE = "ISSUE";
    public static final String REF_SCRAP = "SCRAP";
    public static final String REF_STOCKTAKE = "STOCKTAKE";
    public static final String STOCKTAKE_OPEN = "OPEN";

    private static final Set<String> SUPPLY_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> REQUEST_STATUSES = Set
        .of(OaSupplyRequestHook.STATUS_APPROVING, OaSupplyRequestHook.STATUS_PENDING_ISSUE, OaSupplyRequestHook.STATUS_ISSUED, OaSupplyRequestHook.STATUS_CLOSED);
    private static final Set<String> ASSET_STATUSES = Set
        .of(OaAssetStatus.IN_STOCK, OaAssetStatus.IN_USE, OaAssetStatus.REPAIR, OaAssetStatus.SCRAPPED);
    private static final Set<String> SCRAP_STATUSES = Set
        .of(OaAssetScrapHook.STATUS_APPROVING, OaAssetScrapHook.STATUS_PENDING_SCRAP, OaAssetScrapHook.STATUS_SCRAPPED, OaAssetScrapHook.STATUS_CLOSED);

    private final OaAssetRepository repository;
    private final OaStaffDirectory directory;
    private final OaFormService formService;
    private final OaProcessModelService modelService;
    private final OaOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OaAssetService(OaAssetRepository repository,
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

    public List<OaSupplyItemResp> supplyItems(long tenantId, String status, String keyword) {
        return repository.supplyItems(tenantId, checked(status, SUPPLY_STATUSES, "用品"), keyword);
    }

    public OaSupplyItemResp createSupplyItem(long tenantId, long userId, OaSupplyItemReq req) {
        String status = req.status() == null ? "ENABLED" : req.status();
        try {
            long id = repository.insertSupplyItem(tenantId, req.code().trim(), req.name().trim(), blankToNull(req
                .spec()), req.unit().trim(), req.safetyStock(), status, userId, LocalDateTime.now());
            return repository.supplyItem(tenantId, id, false);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用品编码已存在");
        }
    }

    public OaSupplyItemResp updateSupplyItem(long tenantId, long userId, long id, OaSupplyItemReq req) {
        if (req.version() == null) {
            throw new BusinessException("缺少版本号，请刷新后重试");
        }
        if (repository.supplyItem(tenantId, id, false) == null) {
            throw new BusinessException("用品不存在");
        }
        String status = req.status() == null ? "ENABLED" : req.status();
        int updated;
        try {
            updated = repository.updateSupplyItem(tenantId, id, req.version(), req.code().trim(), req.name()
                .trim(), blankToNull(req.spec()), req.unit().trim(), req.safetyStock(), status, userId, LocalDateTime
                    .now());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用品编码已存在");
        }
        if (updated != 1) {
            throw new BusinessException("用品已被修改，请刷新后重试");
        }
        return repository.supplyItem(tenantId, id, false);
    }

    public OaSupplyItemResp stockIn(long tenantId, long operatorId, long id, OaSupplyStockReq req) {
        return changeStockManually(tenantId, operatorId, id, req, true);
    }

    public OaSupplyItemResp stockOut(long tenantId, long operatorId, long id, OaSupplyStockReq req) {
        return changeStockManually(tenantId, operatorId, id, req, false);
    }

    public List<OaSupplyLedgerResp> supplyLedger(long tenantId, long itemId) {
        if (repository.supplyItem(tenantId, itemId, false) == null) {
            throw new BusinessException("用品不存在");
        }
        return repository.supplyLedger(tenantId, itemId, null, null, 200);
    }

    public List<OaSupplyRequestResp> supplyRequests(long tenantId,
                                                    String status,
                                                    String keyword,
                                                    Long applicantId,
                                                    int page,
                                                    int size) {
        int safeSize = safeSize(size);
        return repository
            .supplyRequests(tenantId, checked(status, REQUEST_STATUSES, "领用单"), keyword, applicantId, offset(page, safeSize), safeSize);
    }

    public long countSupplyRequests(long tenantId, String status, String keyword, Long applicantId) {
        return repository.countSupplyRequests(tenantId, checked(status, REQUEST_STATUSES, "领用单"), keyword, applicantId);
    }

    public OaSupplyRequestDetailResp supplyRequestDetail(long tenantId, Long viewerId, long id) {
        OaSupplyRequestResp request = repository.supplyRequest(tenantId, id);
        if (request == null || viewerId != null && request.applicantId() != viewerId) {
            throw new BusinessException("领用单不存在");
        }
        return new OaSupplyRequestDetailResp(request, repository.supplyRequestItems(tenantId, id));
    }

    /**
     * 领用发放：按请求号幂等；逐项条件扣减库存，任一项不足整单回滚，库存不会被扣成负数。
     */
    public OaSupplyRequestDetailResp issue(long tenantId, long operatorId, long id, OaOperationReq req) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            SupplyRequestRow request = repository.lockSupplyRequest(tenantId, id);
            if (request == null) {
                throw new BusinessException("领用单不存在");
            }
            Long replayed = repository.supplyRequestIdByIssueRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            if (request.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的领用登记发放");
            }
            if (!OaSupplyRequestHook.STATUS_PENDING_ISSUE.equals(request.status())) {
                throw new BusinessException(switch (request.status()) {
                    case OaSupplyRequestHook.STATUS_APPROVING -> "领用尚未审批通过，不能发放";
                    case OaSupplyRequestHook.STATUS_CLOSED -> "领用已关闭，不能发放";
                    default -> "该领用已发放";
                });
            }
            LocalDateTime now = LocalDateTime.now();
            String remark = blankToNull(req.remark());
            for (OaSupplyRequestItemResp item : repository.supplyRequestItems(tenantId, id)) {
                if (repository.changeStock(tenantId, item.itemId(), -item.quantity(), now) != 1) {
                    OaSupplyItemResp current = repository.supplyItem(tenantId, item.itemId(), false);
                    throw new BusinessException(item.name() + " 库存不足：当前 " + current.stock() + "，需发放 " + item
                        .quantity());
                }
                OaSupplyItemResp after = repository.supplyItem(tenantId, item.itemId(), false);
                repository.insertSupplyLedger(tenantId, item.itemId(), "OUT", item.quantity(), after
                    .stock(), REF_ISSUE, id, null, operatorId, remark, now);
            }
            try {
                if (repository.markIssued(tenantId, id, requestId, operatorId, now) != 1) {
                    throw new BusinessException("领用状态已变化，请刷新后重试");
                }
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            OaSupplyRequestResp issued = repository.supplyRequest(tenantId, id);
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(request
                .applicationId()), EVENT_SUPPLY_ISSUED, "ISSUED:" + id, request
                    .applicationId(), null, "ISSUED", "用品已发放", "您的领用「" + issued.title() + "」已发放，请到行政处领取", List
                        .of(request.applicantId()));
        });
        return supplyRequestDetail(tenantId, null, id);
    }

    public List<OaAssetResp> assets(long tenantId, String status, String keyword, Long holderId, int page, int size) {
        int safeSize = safeSize(size);
        return repository
            .assets(tenantId, checked(status, ASSET_STATUSES, "资产"), keyword, holderId, null, offset(page, safeSize), safeSize);
    }

    public long countAssets(long tenantId, String status, String keyword, Long holderId) {
        return repository.countAssets(tenantId, checked(status, ASSET_STATUSES, "资产"), keyword, holderId, null);
    }

    public OaAssetDetailResp assetDetail(long tenantId, Long holderId, long id) {
        OaAssetResp asset = repository.asset(tenantId, id);
        if (asset == null || holderId != null && !holderId.equals(asset.holderId())) {
            throw new BusinessException("资产不存在");
        }
        return new OaAssetDetailResp(asset, repository.assetLogs(tenantId, id), repository
            .scraps(tenantId, null, null, id, 0, 50));
    }

    /**
     * 手工建卡（存量资产登记），按请求号幂等。
     */
    public OaAssetResp createAsset(long tenantId, long operatorId, OaAssetCreateReq req) {
        String requestId = req.requestId().trim();
        LocalDate acquired = req.acquiredDate() == null ? LocalDate.now() : req.acquiredDate();
        OaPurchaseService.requireNotFuture(acquired, "购置日期");
        Long id = transactionTemplate.execute(status -> {
            Long replayed = repository.assetIdByLogRequest(tenantId, requestId);
            if (replayed != null) {
                return replayed;
            }
            LocalDateTime now = LocalDateTime.now();
            String location = blankToNull(req.location());
            long assetId = repository.insertAsset(tenantId, req.name().trim(), blankToNull(req
                .category()), blankToNull(req.spec()), OaAssetStatus.IN_STOCK, location, req.originalValue()
                    .setScale(2), null, acquired, operatorId, now);
            repository.assignAssetNo(tenantId, assetId, OaPurchaseService.assetNo(now, assetId));
            try {
                repository
                    .insertAssetLog(tenantId, assetId, "IN", null, OaAssetStatus.IN_STOCK, null, null, location, REF_MANUAL, null, requestId, operatorId, "手工建卡", now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            return assetId;
        });
        return repository.asset(tenantId, id);
    }

    /**
     * 资产领用/归还/调拨/送修/修复：以版本号条件改写并写操作日志，按请求号幂等。
     */
    public OaAssetResp operate(long tenantId, long operatorId, long id, OaAssetActionReq req) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            AssetRow asset = repository.lockAsset(tenantId, id);
            if (asset == null) {
                throw new BusinessException("资产不存在");
            }
            Long replayed = repository.assetIdByLogRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            if (OaAssetStatus.SCRAPPED.equals(asset.status())) {
                throw new BusinessException("资产已报废");
            }
            if (repository.activeScrapCount(tenantId, id) > 0) {
                throw new BusinessException("资产有进行中的报废申请，不能操作");
            }
            String location = req.location() == null ? asset.location() : blankToNull(req.location());
            String target;
            Long holder;
            switch (req.action()) {
                case "ASSIGN" -> {
                    requireStatus(asset, "领用", OaAssetStatus.IN_STOCK);
                    holder = requireHolder(tenantId, req.holderId());
                    target = OaAssetStatus.IN_USE;
                }
                case "RETURN" -> {
                    requireStatus(asset, "归还", OaAssetStatus.IN_USE);
                    holder = null;
                    target = OaAssetStatus.IN_STOCK;
                }
                case "TRANSFER" -> {
                    requireStatus(asset, "调拨", OaAssetStatus.IN_USE);
                    holder = requireHolder(tenantId, req.holderId());
                    if (holder.equals(asset.holderId())) {
                        throw new BusinessException("调拨对象与当前使用人相同");
                    }
                    target = OaAssetStatus.IN_USE;
                }
                case "REPAIR" -> {
                    requireStatus(asset, "送修", OaAssetStatus.IN_STOCK, OaAssetStatus.IN_USE);
                    holder = asset.holderId();
                    target = OaAssetStatus.REPAIR;
                }
                case "REPAIRED" -> {
                    requireStatus(asset, "修复", OaAssetStatus.REPAIR);
                    holder = asset.holderId();
                    target = holder == null ? OaAssetStatus.IN_STOCK : OaAssetStatus.IN_USE;
                }
                default -> throw new BusinessException("不支持的资产操作: " + req.action());
            }
            Long deptId = holder == null ? null : directory.departmentId(tenantId, holder);
            LocalDateTime now = LocalDateTime.now();
            if (repository.moveAsset(tenantId, id, asset.version(), target, holder, deptId, location, now) != 1) {
                throw new BusinessException("资产已被修改，请刷新后重试");
            }
            try {
                repository.insertAssetLog(tenantId, id, req.action(), asset.status(), target, asset
                    .holderId(), holder, location, REF_MANUAL, null, requestId, operatorId, blankToNull(req
                        .remark()), now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
        });
        return repository.asset(tenantId, id);
    }

    public List<OaAssetScrapResp> scraps(long tenantId, String status, Long applicantId, int page, int size) {
        int safeSize = safeSize(size);
        return repository
            .scraps(tenantId, checked(status, SCRAP_STATUSES, "报废单"), applicantId, null, offset(page, safeSize), safeSize);
    }

    public long countScraps(long tenantId, String status, Long applicantId) {
        return repository.countScraps(tenantId, checked(status, SCRAP_STATUSES, "报废单"), applicantId, null);
    }

    /**
     * 报废执行：审批通过后由资产管理员执行，资产转为已报废并清空使用人，按请求号幂等。
     */
    public OaAssetScrapResp executeScrap(long tenantId, long operatorId, long id, OaOperationReq req) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            ScrapRow scrap = repository.lockScrap(tenantId, id);
            if (scrap == null) {
                throw new BusinessException("报废单不存在");
            }
            Long replayed = repository.assetIdByLogRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != scrap.assetId() || !OaAssetScrapHook.STATUS_SCRAPPED.equals(scrap.status())) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            if (scrap.applicantId() == operatorId) {
                throw new BusinessException("不能为本人的报废申请执行报废");
            }
            if (!OaAssetScrapHook.STATUS_PENDING_SCRAP.equals(scrap.status())) {
                throw new BusinessException(switch (scrap.status()) {
                    case OaAssetScrapHook.STATUS_APPROVING -> "报废尚未审批通过，不能执行";
                    case OaAssetScrapHook.STATUS_CLOSED -> "报废申请已关闭";
                    default -> "该报废已执行";
                });
            }
            AssetRow asset = repository.lockAsset(tenantId, scrap.assetId());
            if (OaAssetStatus.SCRAPPED.equals(asset.status())) {
                throw new BusinessException("资产已报废");
            }
            LocalDateTime now = LocalDateTime.now();
            if (repository.moveAsset(tenantId, asset.id(), asset.version(), OaAssetStatus.SCRAPPED, null, null, asset
                .location(), now) != 1) {
                throw new BusinessException("资产已被修改，请刷新后重试");
            }
            try {
                repository.insertAssetLog(tenantId, asset.id(), "SCRAP", asset.status(), OaAssetStatus.SCRAPPED, asset
                    .holderId(), null, asset.location(), REF_SCRAP, id, requestId, operatorId, blankToNull(req
                        .remark()), now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
            if (repository.markScrapped(tenantId, id, operatorId, now) != 1) {
                throw new BusinessException("报废状态已变化，请刷新后重试");
            }
            outboxService.recordNotification(tenantId, OaOutboxService.AGGREGATE_APPLICATION, Long.toString(scrap
                .applicationId()), EVENT_ASSET_SCRAPPED, "SCRAPPED:" + id, scrap
                    .applicationId(), null, "SCRAPPED", "资产已报废", "资产 " + asset.assetNo() + "「" + asset
                        .name() + "」已执行报废", List.of(scrap.applicantId()));
        });
        return repository.scrap(tenantId, id);
    }

    public List<OaStocktakeResp> stocktakes(long tenantId, int page, int size) {
        int safeSize = safeSize(size);
        return repository.stocktakes(tenantId, offset(page, safeSize), safeSize);
    }

    public long countStocktakes(long tenantId) {
        return repository.countStocktakes(tenantId);
    }

    public OaStocktakeDetailResp stocktakeDetail(long tenantId, long id) {
        OaStocktakeResp stocktake = repository.stocktake(tenantId, id);
        if (stocktake == null) {
            throw new BusinessException("盘点单不存在");
        }
        return new OaStocktakeDetailResp(stocktake, repository.stocktakeLines(tenantId, id));
    }

    /**
     * 发起盘点：同一时间只允许一张进行中的盘点，快照用品库存与未报废资产作为账面数。
     */
    public OaStocktakeDetailResp createStocktake(long tenantId, long userId, OaStocktakeCreateReq req) {
        Long id = transactionTemplate.execute(status -> {
            if (!repository.openStocktakes(tenantId).isEmpty()) {
                throw new BusinessException("已有进行中的盘点，请先完成");
            }
            long stocktakeId = repository.insertStocktake(tenantId, req.title().trim(), userId, LocalDateTime.now());
            if (repository.snapshotStocktake(tenantId, stocktakeId) == 0) {
                throw new BusinessException("没有可盘点的用品或资产");
            }
            return stocktakeId;
        });
        return stocktakeDetail(tenantId, id);
    }

    public OaStocktakeDetailResp countStocktake(long tenantId, long id, OaStocktakeCountReq req) {
        transactionTemplate.executeWithoutResult(status -> {
            requireOpen(tenantId, id);
            for (OaStocktakeCount count : req.counts()) {
                String type = repository.lineType(tenantId, id, count.lineId());
                if (type == null) {
                    throw new BusinessException("盘点明细不存在: " + count.lineId());
                }
                boolean asset = "ASSET".equals(type);
                if (asset && count.countedQty() > 1) {
                    throw new BusinessException("资产实盘数量只能是 0 或 1");
                }
                repository.countLine(tenantId, id, count.lineId(), count.countedQty(), asset);
            }
        });
        return stocktakeDetail(tenantId, id);
    }

    /**
     * 完成盘点：全部明细已盘后按差异调整用品库存并记台账，资产写盘点日志。
     */
    public OaStocktakeDetailResp closeStocktake(long tenantId, long userId, long id) {
        transactionTemplate.executeWithoutResult(status -> {
            requireOpen(tenantId, id);
            List<OaStocktakeLineResp> lines = repository.stocktakeLines(tenantId, id);
            long uncounted = lines.stream().filter(line -> line.countedQty() == null).count();
            if (uncounted > 0) {
                throw new BusinessException("还有 " + uncounted + " 项未盘点");
            }
            LocalDateTime now = LocalDateTime.now();
            List<OaStocktakeLineResp> supplies = new ArrayList<>();
            for (OaStocktakeLineResp line : lines) {
                if ("SUPPLY".equals(line.targetType())) {
                    supplies.add(line);
                }
            }
            supplies.sort((a, b) -> Long.compare(a.targetId(), b.targetId()));
            for (OaStocktakeLineResp line : supplies) {
                int delta = line.countedQty() - line.bookQty();
                if (delta != 0) {
                    if (repository.changeStock(tenantId, line.targetId(), delta, now) != 1) {
                        throw new BusinessException(line.targetName() + " 调整后库存为负，请复盘");
                    }
                    OaSupplyItemResp after = repository.supplyItem(tenantId, line.targetId(), false);
                    repository.insertSupplyLedger(tenantId, line.targetId(), "ADJUST", delta, after
                        .stock(), REF_STOCKTAKE, id, null, userId, "盘点差异调整", now);
                }
                repository.setAdjusted(tenantId, line.id(), delta);
            }
            for (OaStocktakeLineResp line : lines) {
                if (!"ASSET".equals(line.targetType())) {
                    continue;
                }
                boolean found = line.countedQty() == 1;
                AssetRow asset = repository.lockAsset(tenantId, line.targetId());
                repository.insertAssetLog(tenantId, line.targetId(), "CHECK", asset.status(), asset.status(), asset
                    .holderId(), asset.holderId(), asset.location(), REF_STOCKTAKE, id, null, userId, found
                        ? "盘点实物在位"
                        : "盘点未找到", now);
                repository.setAdjusted(tenantId, line.id(), line.countedQty() - line.bookQty());
            }
            if (repository.closeStocktake(tenantId, id, userId, now) != 1) {
                throw new BusinessException("盘点状态已变化，请刷新后重试");
            }
        });
        return stocktakeDetail(tenantId, id);
    }

    /**
     * 初始化领用与报废试点：两张已发布表单与 SUPPLY_REQUEST/ASSET_SCRAP 业务类型的草稿流程模型。
     */
    public OaAssetTemplateResp template(long tenantId, long userId) {
        List<OaSupplyItemResp> enabled = repository.supplyItems(tenantId, "ENABLED", null);
        if (enabled.isEmpty()) {
            throw new BusinessException("没有启用的用品，请先在用品档案中新建并启用");
        }
        return transactionTemplate.execute(status -> {
            long supplyFormId = publishForm(tenantId, userId, SUPPLY_FORM_KEY, "用品领用", supplySchema(enabled));
            OaProcessModelResp supplyModel = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(SUPPLY_MODEL_KEY, "用品领用", "asset", "直属上级审批；通过后由行政经办发放并扣减库存", SUPPLY_BPMN, OaSupplyRequestHook.BUSINESS_TYPE));
            long scrapFormId = publishForm(tenantId, userId, SCRAP_FORM_KEY, "资产报废", scrapSchema());
            OaProcessModelResp scrapModel = modelService
                .create(tenantId, userId, new OaProcessModelCreateReq(SCRAP_MODEL_KEY, "资产报废", "asset", "直属上级 → 财务岗位审核；原值超过 5000 元再由总经理岗位审批；通过后由资产管理员执行报废", SCRAP_BPMN, OaAssetScrapHook.BUSINESS_TYPE));
            return new OaAssetTemplateResp(supplyFormId, supplyModel.id(), supplyModel
                .modelKey(), scrapFormId, scrapModel.id(), scrapModel.modelKey());
        });
    }

    public String supplySchema(List<OaSupplyItemResp> items) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (OaSupplyItemResp item : items) {
            options.add(option(item.name() + "（" + item.unit() + "）", item.code()));
        }
        Map<String, Object> table = field(OaSupplyRequestHook.FIELD_ITEMS, "领用明细", "table", true);
        table.put("maxRows", 20);
        table.put("columns", List
            .of(column(OaSupplyRequestHook.COL_CODE, "用品", "radio", true, options), column(OaSupplyRequestHook.COL_QUANTITY, "数量", "number", true, null)));
        return schema(List.of(table, field(OaSupplyRequestHook.FIELD_REASON, "领用事由", "textarea", true)));
    }

    public String scrapSchema() {
        return schema(List
            .of(field(OaAssetScrapHook.FIELD_ASSET_NO, "资产编号", "text", true), field(OaAssetScrapHook.FIELD_REASON, "报废原因", "textarea", true)));
    }

    private OaSupplyItemResp changeStockManually(long tenantId,
                                                 long operatorId,
                                                 long id,
                                                 OaSupplyStockReq req,
                                                 boolean in) {
        String requestId = req.requestId().trim();
        transactionTemplate.executeWithoutResult(status -> {
            // 先锁行再查请求号：并发重放在锁释放后能看到已提交的台账，按重放返回而非重复扣减或报错
            OaSupplyItemResp item = repository.supplyItem(tenantId, id, true);
            if (item == null) {
                throw new BusinessException("用品不存在");
            }
            Long replayed = repository.supplyItemIdByRequest(tenantId, requestId);
            if (replayed != null) {
                if (replayed != id) {
                    throw new BusinessException("请求号已被使用");
                }
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            int quantity = req.quantity();
            if (repository.changeStock(tenantId, id, in ? quantity : -quantity, now) != 1) {
                OaSupplyItemResp current = repository.supplyItem(tenantId, id, false);
                throw new BusinessException("库存不足：当前 " + current.stock() + "，出库 " + quantity);
            }
            OaSupplyItemResp after = repository.supplyItem(tenantId, id, false);
            try {
                repository.insertSupplyLedger(tenantId, id, in ? "IN" : "OUT", quantity, after
                    .stock(), REF_MANUAL, null, requestId, operatorId, blankToNull(req.remark()), now);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("请求号已被使用");
            }
        });
        return repository.supplyItem(tenantId, id, false);
    }

    private Long requireHolder(long tenantId, Long holderId) {
        if (holderId == null) {
            throw new BusinessException("请选择使用人");
        }
        if (!directory.activeEmployee(tenantId, holderId)) {
            throw new BusinessException("使用人不是在职员工");
        }
        return holderId;
    }

    private static void requireStatus(AssetRow asset, String action, String... allowed) {
        for (String status : allowed) {
            if (Objects.equals(status, asset.status())) {
                return;
            }
        }
        throw new BusinessException("资产当前状态为 " + label(asset.status()) + "，不能" + action);
    }

    private static String label(String status) {
        return switch (status) {
            case OaAssetStatus.IN_STOCK -> "在库";
            case OaAssetStatus.IN_USE -> "在用";
            case OaAssetStatus.REPAIR -> "维修中";
            case OaAssetStatus.SCRAPPED -> "已报废";
            default -> status;
        };
    }

    private void requireOpen(long tenantId, long id) {
        String status = repository.lockStocktakeStatus(tenantId, id);
        if (status == null) {
            throw new BusinessException("盘点单不存在");
        }
        if (!STOCKTAKE_OPEN.equals(status)) {
            throw new BusinessException("盘点已完成");
        }
    }

    private long publishForm(long tenantId, long userId, String key, String name, String schema) {
        OaFormResp form = formService
            .create(tenantId, userId, new OaFormCreateReq(key, name, name + "试点模板，业务字段 key 不可修改", schema));
        OaFormPublishResp published = formService.publish(tenantId, userId, form.id());
        if (!published.valid()) {
            throw new BusinessException(name + "表单发布失败：" + String.join("；", published.errors()));
        }
        return form.id();
    }

    private String schema(List<Map<String, Object>> fields) {
        try {
            return objectMapper.writeValueAsString(Map.of("fields", fields));
        } catch (Exception e) {
            throw new IllegalStateException("模板 schema 序列化失败", e);
        }
    }

    private static String checked(String status, Set<String> allowed, String label) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if (!allowed.contains(status)) {
            throw new BusinessException(label + "状态无效: " + status);
        }
        return status;
    }

    public static final String SUPPLY_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          id="Definitions_supply" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="supply_pilot" name="用品领用" isExecutable="true">
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
          <bpmndi:BPMNDiagram id="BPMNDiagram_supply">
            <bpmndi:BPMNPlane id="BPMNPlane_supply" bpmnElement="supply_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="402" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToEnd_di" bpmnElement="flowToEnd">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="402" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;

    public static final String SCRAP_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_asset_scrap" targetNamespace="http://guardfind.com/oa/bpmn">
          <bpmn:process id="asset_scrap_pilot" name="资产报废" isExecutable="true">
            <bpmn:startEvent id="start" name="提交">
              <bpmn:outgoing>flowToSupervisor</bpmn:outgoing>
            </bpmn:startEvent>
            <bpmn:userTask id="supervisorApprove" name="直属上级审批" flowable:assignee="${supervisor}">
              <bpmn:incoming>flowToSupervisor</bpmn:incoming>
              <bpmn:outgoing>flowToFinance</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:userTask id="financeApprove" name="财务审核" flowable:candidateUsers="${financeReviewer}">
              <bpmn:incoming>flowToFinance</bpmn:incoming>
              <bpmn:outgoing>flowToGateway</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:exclusiveGateway id="valueGateway" name="原值超过 5000 元？" default="flowSmall">
              <bpmn:incoming>flowToGateway</bpmn:incoming>
              <bpmn:outgoing>flowLarge</bpmn:outgoing>
              <bpmn:outgoing>flowSmall</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:userTask id="gmApprove" name="总经理审批" flowable:candidateUsers="${finalApprover}">
              <bpmn:incoming>flowLarge</bpmn:incoming>
              <bpmn:outgoing>flowGmDone</bpmn:outgoing>
            </bpmn:userTask>
            <bpmn:endEvent id="end" name="结束">
              <bpmn:incoming>flowGmDone</bpmn:incoming>
              <bpmn:incoming>flowSmall</bpmn:incoming>
            </bpmn:endEvent>
            <bpmn:sequenceFlow id="flowToSupervisor" sourceRef="start" targetRef="supervisorApprove"/>
            <bpmn:sequenceFlow id="flowToFinance" sourceRef="supervisorApprove" targetRef="financeApprove"/>
            <bpmn:sequenceFlow id="flowToGateway" sourceRef="financeApprove" targetRef="valueGateway"/>
            <bpmn:sequenceFlow id="flowLarge" name="大于 5000 元" sourceRef="valueGateway" targetRef="gmApprove">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${assetValue &gt; 5000}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="flowSmall" name="5000 元及以内" sourceRef="valueGateway" targetRef="end"/>
            <bpmn:sequenceFlow id="flowGmDone" sourceRef="gmApprove" targetRef="end"/>
          </bpmn:process>
          <bpmndi:BPMNDiagram id="BPMNDiagram_asset_scrap">
            <bpmndi:BPMNPlane id="BPMNPlane_asset_scrap" bpmnElement="asset_scrap_pilot">
              <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="152" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="supervisorApprove_di" bpmnElement="supervisorApprove">
                <dc:Bounds x="240" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="financeApprove_di" bpmnElement="financeApprove">
                <dc:Bounds x="390" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="valueGateway_di" bpmnElement="valueGateway" isMarkerVisible="true">
                <dc:Bounds x="545" y="95" width="50" height="50"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="gmApprove_di" bpmnElement="gmApprove">
                <dc:Bounds x="650" y="80" width="100" height="80"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="812" y="102" width="36" height="36"/>
              </bpmndi:BPMNShape>
              <bpmndi:BPMNEdge id="flowToSupervisor_di" bpmnElement="flowToSupervisor">
                <di:waypoint x="188" y="120"/>
                <di:waypoint x="240" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToFinance_di" bpmnElement="flowToFinance">
                <di:waypoint x="340" y="120"/>
                <di:waypoint x="390" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowToGateway_di" bpmnElement="flowToGateway">
                <di:waypoint x="490" y="120"/>
                <di:waypoint x="545" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowLarge_di" bpmnElement="flowLarge">
                <di:waypoint x="595" y="120"/>
                <di:waypoint x="650" y="120"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowSmall_di" bpmnElement="flowSmall">
                <di:waypoint x="570" y="145"/>
                <di:waypoint x="570" y="220"/>
                <di:waypoint x="830" y="220"/>
                <di:waypoint x="830" y="138"/>
              </bpmndi:BPMNEdge>
              <bpmndi:BPMNEdge id="flowGmDone_di" bpmnElement="flowGmDone">
                <di:waypoint x="750" y="120"/>
                <di:waypoint x="812" y="120"/>
              </bpmndi:BPMNEdge>
            </bpmndi:BPMNPlane>
          </bpmndi:BPMNDiagram>
        </bpmn:definitions>
        """;
}
