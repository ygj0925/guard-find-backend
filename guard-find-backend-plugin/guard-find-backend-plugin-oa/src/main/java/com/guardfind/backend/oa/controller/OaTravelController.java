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
import com.guardfind.backend.oa.model.OaLoanDetailResp;
import com.guardfind.backend.oa.model.OaLoanDisburseReq;
import com.guardfind.backend.oa.model.OaLoanRepayReq;
import com.guardfind.backend.oa.model.OaLoanResp;
import com.guardfind.backend.oa.model.OaTravelDetailResp;
import com.guardfind.backend.oa.model.OaTravelResp;
import com.guardfind.backend.oa.model.OaTravelTemplateResp;
import com.guardfind.backend.oa.service.OaTravelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;
import top.continew.starter.log.annotation.Log;

import java.util.List;

/**
 * OA 出差借款 API：后台出差与借款台账、财务放款与还款登记，员工查询自己的出差与借款。
 */
@Tag(name = "OA 出差借款 API")
@Log(module = "OA 出差借款")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/oa/travel")
public class OaTravelController {

    private final OaTravelService service;

    @Operation(summary = "出差台账分页")
    @SaCheckPermission("system:oa:travel:list")
    @GetMapping("/records")
    public PageResp<OaTravelResp> travels(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword,
                                          PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaTravelResp> resp = new PageResp<>();
        resp.setList(service.travels(tenantId, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countTravels(tenantId, status, keyword, null));
        return resp;
    }

    @Operation(summary = "出差详情")
    @SaCheckPermission("system:oa:travel:list")
    @GetMapping("/records/{id}")
    public OaTravelDetailResp travelDetail(@PathVariable Long id) {
        return service.travelDetail(tenantId(), null, id);
    }

    @Operation(summary = "借款台账分页")
    @SaCheckPermission(value = {"system:oa:travel:list", "system:oa:loan:pay"}, mode = SaMode.OR)
    @GetMapping("/loans")
    public PageResp<OaLoanResp> loans(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String keyword,
                                      PageQuery pageQuery) {
        long tenantId = tenantId();
        PageResp<OaLoanResp> resp = new PageResp<>();
        resp.setList(service.loans(tenantId, status, keyword, null, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countLoans(tenantId, status, keyword, null));
        return resp;
    }

    @Operation(summary = "借款详情与台账")
    @SaCheckPermission(value = {"system:oa:travel:list", "system:oa:loan:pay"}, mode = SaMode.OR)
    @GetMapping("/loans/{id}")
    public OaLoanDetailResp loanDetail(@PathVariable Long id) {
        return service.loanDetail(tenantId(), null, id);
    }

    @Operation(summary = "申请人待还款借款（报销核销用）")
    @SaCheckPermission(value = {"system:oa:expense:pay", "system:oa:loan:pay"}, mode = SaMode.OR)
    @GetMapping("/loans/outstanding")
    public List<OaLoanResp> outstanding(@RequestParam Long applicantId) {
        return service.outstandingLoans(tenantId(), applicantId);
    }

    @Operation(summary = "登记放款")
    @SaCheckPermission("system:oa:loan:pay")
    @PostMapping("/loans/{id}/disburse")
    public OaLoanResp disburse(@PathVariable Long id, @Valid @RequestBody OaLoanDisburseReq req) {
        return service.disburse(tenantId(), userId(), id, req);
    }

    @Operation(summary = "登记还款")
    @SaCheckPermission("system:oa:loan:pay")
    @PostMapping("/loans/{id}/repay")
    public OaLoanResp repay(@PathVariable Long id, @Valid @RequestBody OaLoanRepayReq req) {
        return service.repay(tenantId(), userId(), id, req);
    }

    @Operation(summary = "初始化出差与借款模板")
    @SaCheckPermission("system:oa:travel:manage")
    @PostMapping("/template")
    public OaTravelTemplateResp template() {
        return service.template(tenantId(), userId());
    }

    @Operation(summary = "我的出差")
    @SaCheckPermission("system:oa:travel:mine")
    @GetMapping("/mine")
    public PageResp<OaTravelResp> myTravels(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaTravelResp> resp = new PageResp<>();
        resp.setList(service.travels(tenantId, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countTravels(tenantId, status, null, userId));
        return resp;
    }

    @Operation(summary = "我的出差详情")
    @SaCheckPermission("system:oa:travel:mine")
    @GetMapping("/mine/{id}")
    public OaTravelDetailResp myTravelDetail(@PathVariable Long id) {
        return service.travelDetail(tenantId(), userId(), id);
    }

    @Operation(summary = "我的借款")
    @SaCheckPermission("system:oa:travel:mine")
    @GetMapping("/mine/loans")
    public PageResp<OaLoanResp> myLoans(@RequestParam(required = false) String status, PageQuery pageQuery) {
        long tenantId = tenantId();
        long userId = userId();
        PageResp<OaLoanResp> resp = new PageResp<>();
        resp.setList(service.loans(tenantId, status, null, userId, pageQuery.getPage(), pageQuery.getSize()));
        resp.setTotal(service.countLoans(tenantId, status, null, userId));
        return resp;
    }

    @Operation(summary = "我的借款详情")
    @SaCheckPermission("system:oa:travel:mine")
    @GetMapping("/mine/loans/{id}")
    public OaLoanDetailResp myLoanDetail(@PathVariable Long id) {
        return service.loanDetail(tenantId(), userId(), id);
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
