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

package com.guardfind.backend.oa.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.guardfind.backend.common.context.UserContextHolder;
import com.guardfind.backend.oa.model.OaAssetActionReq;
import com.guardfind.backend.oa.model.OaAssetCreateReq;
import com.guardfind.backend.oa.model.OaAssetDetailResp;
import com.guardfind.backend.oa.model.OaAssetResp;
import com.guardfind.backend.oa.model.OaAssetScrapResp;
import com.guardfind.backend.oa.model.OaAssetTemplateResp;
import com.guardfind.backend.oa.model.OaOperationReq;
import com.guardfind.backend.oa.model.OaStocktakeCountReq;
import com.guardfind.backend.oa.model.OaStocktakeCreateReq;
import com.guardfind.backend.oa.model.OaStocktakeDetailResp;
import com.guardfind.backend.oa.model.OaStocktakeResp;
import com.guardfind.backend.oa.model.OaSupplyItemReq;
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.model.OaSupplyLedgerResp;
import com.guardfind.backend.oa.model.OaSupplyRequestDetailResp;
import com.guardfind.backend.oa.model.OaSupplyRequestResp;
import com.guardfind.backend.oa.model.OaSupplyStockReq;
import com.guardfind.backend.oa.service.OaAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import java.util.List;

/**
 * OA 资产用品 API：用品档案与出入库、领用发放、资产卡片与操作、报废执行与盘点，员工查询自己的资产与领用。
 */
@Tag(name = "OA 资产用品 API")
@Log(module = "OA 资产用品")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/asset")
public class OaAssetController {

    private static final String LIST = "system:oa:asset:list";
    private static final String MANAGE = "system:oa:asset:manage";
    private static final String OPERATE = "system:oa:asset:operate";
    private static final String MINE = "system:oa:asset:mine";

    private final OaAssetService service;

    @Operation(summary = "用品档案")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/supplies")
    public List<OaSupplyItemResp> supplies(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String keyword) {
        return service.supplyItems(tenantId(), status, keyword);
    }

    @Operation(summary = "新建用品")
    @SaCheckPermission(MANAGE)
    @PostMapping("/supplies")
    public OaSupplyItemResp createSupply(@Valid @RequestBody OaSupplyItemReq req) {
        return service.createSupplyItem(tenantId(), userId(), req);
    }

    @Operation(summary = "修改用品")
    @SaCheckPermission(MANAGE)
    @PutMapping("/supplies/{id}")
    public OaSupplyItemResp updateSupply(@PathVariable Long id, @Valid @RequestBody OaSupplyItemReq req) {
        return service.updateSupplyItem(tenantId(), userId(), id, req);
    }

    @Operation(summary = "用品入库")
    @SaCheckPermission(OPERATE)
    @PostMapping("/supplies/{id}/stock-in")
    public OaSupplyItemResp stockIn(@PathVariable Long id, @Valid @RequestBody OaSupplyStockReq req) {
        return service.stockIn(tenantId(), userId(), id, req);
    }

    @Operation(summary = "用品出库")
    @SaCheckPermission(OPERATE)
    @PostMapping("/supplies/{id}/stock-out")
    public OaSupplyItemResp stockOut(@PathVariable Long id, @Valid @RequestBody OaSupplyStockReq req) {
        return service.stockOut(tenantId(), userId(), id, req);
    }

    @Operation(summary = "用品库存台账")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/supplies/{id}/ledger")
    public List<OaSupplyLedgerResp> supplyLedger(@PathVariable Long id) {
        return service.supplyLedger(tenantId(), id);
    }

    @Operation(summary = "领用台账分页")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/requests")
    public PageResp<OaSupplyRequestResp> requests(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword,
                                                  PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaSupplyRequestResp> resp = new PageResp<>();
        resp.setList(service.supplyRequests(tenantId, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countSupplyRequests(tenantId, status, keyword, null));
        return resp;
    }

    @Operation(summary = "领用详情")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/requests/{id}")
    public OaSupplyRequestDetailResp requestDetail(@PathVariable Long id) {
        return service.supplyRequestDetail(tenantId(), null, id);
    }

    @Operation(summary = "登记发放")
    @SaCheckPermission(OPERATE)
    @PostMapping("/requests/{id}/issue")
    public OaSupplyRequestDetailResp issue(@PathVariable Long id, @Valid @RequestBody OaOperationReq req) {
        return service.issue(tenantId(), userId(), id, req);
    }

    @Operation(summary = "资产卡片分页")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/assets")
    public PageResp<OaAssetResp> assets(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) String keyword,
                                        PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaAssetResp> resp = new PageResp<>();
        resp.setList(service.assets(tenantId, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countAssets(tenantId, status, keyword, null));
        return resp;
    }

    @Operation(summary = "资产详情与日志")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/assets/{id}")
    public OaAssetDetailResp assetDetail(@PathVariable Long id) {
        return service.assetDetail(tenantId(), null, id);
    }

    @Operation(summary = "手工建卡")
    @SaCheckPermission(MANAGE)
    @PostMapping("/assets")
    public OaAssetResp createAsset(@Valid @RequestBody OaAssetCreateReq req) {
        return service.createAsset(tenantId(), userId(), req);
    }

    @Operation(summary = "资产领用/归还/调拨/维修")
    @SaCheckPermission(OPERATE)
    @PostMapping("/assets/{id}/operate")
    public OaAssetResp operate(@PathVariable Long id, @Valid @RequestBody OaAssetActionReq req) {
        return service.operate(tenantId(), userId(), id, req);
    }

    @Operation(summary = "报废台账分页")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/scraps")
    public PageResp<OaAssetScrapResp> scraps(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaAssetScrapResp> resp = new PageResp<>();
        resp.setList(service.scraps(tenantId, status, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countScraps(tenantId, status, null));
        return resp;
    }

    @Operation(summary = "执行报废")
    @SaCheckPermission(OPERATE)
    @PostMapping("/scraps/{id}/execute")
    public OaAssetScrapResp executeScrap(@PathVariable Long id, @Valid @RequestBody OaOperationReq req) {
        return service.executeScrap(tenantId(), userId(), id, req);
    }

    @Operation(summary = "盘点分页")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/stocktakes")
    public PageResp<OaStocktakeResp> stocktakes(PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaStocktakeResp> resp = new PageResp<>();
        resp.setList(service.stocktakes(tenantId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countStocktakes(tenantId));
        return resp;
    }

    @Operation(summary = "盘点详情")
    @SaCheckPermission(value = {LIST, OPERATE}, mode = SaMode.OR)
    @GetMapping("/stocktakes/{id}")
    public OaStocktakeDetailResp stocktakeDetail(@PathVariable Long id) {
        return service.stocktakeDetail(tenantId(), id);
    }

    @Operation(summary = "发起盘点")
    @SaCheckPermission(OPERATE)
    @PostMapping("/stocktakes")
    public OaStocktakeDetailResp createStocktake(@Valid @RequestBody OaStocktakeCreateReq req) {
        return service.createStocktake(tenantId(), userId(), req);
    }

    @Operation(summary = "录入实盘数量")
    @SaCheckPermission(OPERATE)
    @PostMapping("/stocktakes/{id}/count")
    public OaStocktakeDetailResp count(@PathVariable Long id, @Valid @RequestBody OaStocktakeCountReq req) {
        return service.countStocktake(tenantId(), id, req);
    }

    @Operation(summary = "完成盘点并调整")
    @SaCheckPermission(OPERATE)
    @PostMapping("/stocktakes/{id}/close")
    public OaStocktakeDetailResp close(@PathVariable Long id) {
        return service.closeStocktake(tenantId(), userId(), id);
    }

    @Operation(summary = "初始化领用与报废模板")
    @SaCheckPermission(MANAGE)
    @PostMapping("/template")
    public OaAssetTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的资产")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/assets")
    public PageResp<OaAssetResp> myAssets(PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaAssetResp> resp = new PageResp<>();
        resp.setList(service.assets(tenantId, null, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countAssets(tenantId, null, null, userId));
        return resp;
    }

    @Operation(summary = "我的资产详情")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/assets/{id}")
    public OaAssetDetailResp myAssetDetail(@PathVariable Long id) {
        return service.assetDetail(tenantId(), userId(), id);
    }

    @Operation(summary = "我的领用")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/requests")
    public PageResp<OaSupplyRequestResp> myRequests(@RequestParam(required = false) String status,
                                                    PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaSupplyRequestResp> resp = new PageResp<>();
        resp.setList(service.supplyRequests(tenantId, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countSupplyRequests(tenantId, status, null, userId));
        return resp;
    }

    @Operation(summary = "我的领用详情")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/requests/{id}")
    public OaSupplyRequestDetailResp myRequestDetail(@PathVariable Long id) {
        return service.supplyRequestDetail(tenantId(), userId(), id);
    }

    @Operation(summary = "我的报废申请")
    @SaCheckPermission(MINE)
    @GetMapping("/mine/scraps")
    public PageResp<OaAssetScrapResp> myScraps(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaAssetScrapResp> resp = new PageResp<>();
        resp.setList(service.scraps(tenantId, status, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countScraps(tenantId, status, userId));
        return resp;
    }

    private static long tenantId() {
        Long tenantId = UserContextHolder.getTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    private static long userId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new IllegalStateException("当前用户未登录");
        }
        return userId;
    }
}
