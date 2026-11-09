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

package com.guardfind.backend.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.oa.asset.OaAssetScrapHook;
import com.guardfind.backend.oa.asset.OaAssetStatus;
import com.guardfind.backend.oa.asset.OaSupplyRequestHook;
import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.business.OaBusinessHooks;
import com.guardfind.backend.oa.config.OaFlowableConfiguration;
import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.form.OaFormSchemaValidator;
import com.guardfind.backend.oa.form.OaFormValueValidator;
import com.guardfind.backend.oa.model.OaApprovalActionReq;
import com.guardfind.backend.oa.model.OaAssetActionReq;
import com.guardfind.backend.oa.model.OaAssetCreateReq;
import com.guardfind.backend.oa.model.OaAssetDetailResp;
import com.guardfind.backend.oa.model.OaAssetLogResp;
import com.guardfind.backend.oa.model.OaAssetResp;
import com.guardfind.backend.oa.model.OaAssetScrapResp;
import com.guardfind.backend.oa.model.OaLaunchSubmitReq;
import com.guardfind.backend.oa.model.OaOperationReq;
import com.guardfind.backend.oa.model.OaPurchaseBudgetLedgerResp;
import com.guardfind.backend.oa.model.OaPurchaseBudgetReq;
import com.guardfind.backend.oa.model.OaPurchaseBudgetResp;
import com.guardfind.backend.oa.model.OaPurchaseDetailResp;
import com.guardfind.backend.oa.model.OaPurchaseItemResp;
import com.guardfind.backend.oa.model.OaPurchaseOrderReq;
import com.guardfind.backend.oa.model.OaPurchaseReceiveLine;
import com.guardfind.backend.oa.model.OaPurchaseReceiveReq;
import com.guardfind.backend.oa.model.OaPurchaseResp;
import com.guardfind.backend.oa.model.OaStocktakeCount;
import com.guardfind.backend.oa.model.OaStocktakeCountReq;
import com.guardfind.backend.oa.model.OaStocktakeCreateReq;
import com.guardfind.backend.oa.model.OaStocktakeDetailResp;
import com.guardfind.backend.oa.model.OaStocktakeLineResp;
import com.guardfind.backend.oa.model.OaSupplyItemReq;
import com.guardfind.backend.oa.model.OaSupplyItemResp;
import com.guardfind.backend.oa.model.OaSupplyLedgerResp;
import com.guardfind.backend.oa.model.OaSupplyStockReq;
import com.guardfind.backend.oa.purchase.OaPurchaseHook;
import com.guardfind.backend.oa.repository.OaApprovalRepository;
import com.guardfind.backend.oa.repository.OaAssetRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaCcRepository;
import com.guardfind.backend.oa.repository.OaDelegateRepository;
import com.guardfind.backend.oa.repository.OaDraftRepository;
import com.guardfind.backend.oa.repository.OaLaunchRepository;
import com.guardfind.backend.oa.repository.OaOutboxRepository;
import com.guardfind.backend.oa.repository.OaPurchaseRepository;
import com.guardfind.backend.oa.service.OaApprovalService;
import com.guardfind.backend.oa.service.OaAssetService;
import com.guardfind.backend.oa.service.OaAttachmentService;
import com.guardfind.backend.oa.service.OaCcService;
import com.guardfind.backend.oa.service.OaLaunchService;
import com.guardfind.backend.oa.service.OaOutboxService;
import com.guardfind.backend.oa.service.OaPurchaseService;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.starter.core.exception.BusinessException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 13.x 采购与资产：预算占用/转用/释放、下单与分批验收入库建卡、领用发放不超卖、资产操作、报废与盘点调整。
 */
class OaPurchaseAssetServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();
    private static final long TENANT = 101;
    private static final long APPLICANT = 101;
    private static final long SUPERVISOR = 102;
    private static final long FINANCE = 103;
    private static final long GM = 104;
    private static final long DEPT = 10;
    private static final long PURCHASE_RELEASE = 31;
    private static final long SUPPLY_RELEASE = 32;
    private static final long SCRAP_RELEASE = 33;
    private static final int YEAR = LocalDate.now().getYear();
    private static final LocalDate TODAY = LocalDate.now();

    @Test
    void templatesPassPublishValidation() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            String purchaseSchema = fixture.purchaseService.templateSchema();
            fixture.supplyItem("PEN", "签字笔");
            String supplySchema = fixture.assetService.supplySchema(fixture.assetService
                .supplyItems(TENANT, "ENABLED", null));
            String scrapSchema = fixture.assetService.scrapSchema();
            for (String schema : List.of(purchaseSchema, supplySchema, scrapSchema)) {
                assertThat(new OaFormSchemaValidator().validate(schema, true).valid()).isTrue();
            }
            assertThat(fixture.hooks.validateForm(OaPurchaseHook.BUSINESS_TYPE, purchaseSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaSupplyRequestHook.BUSINESS_TYPE, supplySchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaAssetScrapHook.BUSINESS_TYPE, scrapSchema)).isEmpty();
            assertThat(fixture.hooks.validateForm(OaPurchaseHook.BUSINESS_TYPE, scrapSchema)).isNotEmpty();
            assertThat(fixture.hooks.validateForm(OaAssetScrapHook.BUSINESS_TYPE, purchaseSchema)).isNotEmpty();

            OaBpmnValidationResult purchase = new OaBpmnValidator().validate(OaPurchaseService.BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(purchase.errors()).isEmpty();
            assertThat(purchase.processKey()).isEqualTo(OaPurchaseService.MODEL_KEY);
            assertThat(purchase.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaPurchaseHook.VAR_FINANCE_REVIEWER, OaPurchaseHook.VAR_FINAL_APPROVER);
            OaBpmnValidationResult supply = new OaBpmnValidator().validate(OaAssetService.SUPPLY_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(supply.errors()).isEmpty();
            assertThat(supply.processKey()).isEqualTo(OaAssetService.SUPPLY_MODEL_KEY);
            assertThat(supply.assigneeVariables()).containsExactly("supervisor");
            OaBpmnValidationResult scrap = new OaBpmnValidator().validate(OaAssetService.SCRAP_BPMN
                .getBytes(StandardCharsets.UTF_8));
            assertThat(scrap.errors()).isEmpty();
            assertThat(scrap.processKey()).isEqualTo(OaAssetService.SCRAP_MODEL_KEY);
            assertThat(scrap.assigneeVariables())
                .containsExactlyInAnyOrder("supervisor", OaAssetScrapHook.VAR_FINANCE_REVIEWER, OaAssetScrapHook.VAR_FINAL_APPROVER);
        });
    }

    @Test
    void purchaseBudgetOrderAndBatchReceive() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThat(fixture.purchaseService.depts(TENANT)).extracting(d -> d.name())
                .containsExactly("研发部", "研发部 / 研发一组", "财务部");
            assertThatThrownBy(() -> fixture.purchaseService.saveBudget(TENANT, GM, budget(99, "100", null)))
                .hasMessageContaining("部门不存在");
            OaPurchaseBudgetResp granted = fixture.purchaseService.saveBudget(TENANT, GM, budget(DEPT, "25000", null));
            assertThat(granted.availableAmount()).isEqualByComparingTo("25000");
            assertThatThrownBy(() -> fixture.purchaseService.saveBudget(TENANT, GM, budget(DEPT, "30000", 5)))
                .hasMessageContaining("预算已被修改");
            OaPurchaseBudgetResp adjusted = fixture.purchaseService.saveBudget(TENANT, GM, budget(DEPT, "30000", granted
                .version()));
            assertThat(adjusted.amount()).isEqualByComparingTo("30000");
            OaSupplyItemResp pen = fixture.supplyItem("PEN", "签字笔");

            assertThatThrownBy(() -> fixture.submitPurchase("p0", row("签字笔", "SUPPLY", "NOPE", 1, "1")))
                .hasMessageContaining("用品编码 NOPE 不存在或已停用");
            long app = fixture
                .submitPurchase("p1", row("签字笔", "SUPPLY", "PEN", 10, "2.5"), row("笔记本电脑", "ASSET", null, 2, "12000"), row("安装服务", "OTHER", null, 1, "100"));
            long purchaseId = fixture.purchaseId(app);
            OaPurchaseResp approving = fixture.purchaseService.detail(TENANT, APPLICANT, purchaseId).purchase();
            assertThat(approving.status()).isEqualTo(OaPurchaseHook.STATUS_APPROVING);
            assertThat(approving.totalAmount()).isEqualByComparingTo("24125");
            assertThat(approving.purchaseNo()).matches("PO\\d{6}\\d{4,}");
            assertThat(fixture.variable(app, OaPurchaseHook.VAR_AMOUNT)).isEqualTo(24125.0);
            OaPurchaseBudgetResp occupied = fixture.budget();
            assertThat(occupied.occupiedAmount()).isEqualByComparingTo("24125");
            assertThat(occupied.availableAmount()).isEqualByComparingTo("5875");
            assertThatThrownBy(() -> fixture.purchaseService.saveBudget(TENANT, GM, budget(DEPT, "24000", occupied
                .version()))).hasMessageContaining("预算不能低于已用与占用合计 24125 元");
            assertThatThrownBy(() -> fixture.submitPurchase("p2", row("显示器", "ASSET", null, 1, "6000")))
                .hasMessageContaining("部门采购预算不足：可用 5875 元，本单 6000 元");

            assertThatThrownBy(() -> fixture.purchaseService.order(TENANT, FINANCE, purchaseId, order(TODAY)))
                .hasMessageContaining("采购尚未审批通过，不能下单");
            fixture.approve(SUPERVISOR, app, "a1");
            fixture.approve(FINANCE, app, "a2");
            Task gmTask = fixture.task(app);
            assertThat(gmTask.getTaskDefinitionKey()).isEqualTo("gmApprove");
            fixture.approvalService.approve(TENANT, GM, gmTask.getId(), action("a3"));
            assertThat(fixture.applicationStatus(app)).isEqualTo("APPROVED");
            assertThat(fixture.purchaseStatus(purchaseId)).isEqualTo(OaPurchaseHook.STATUS_PENDING_ORDER);
            OaPurchaseBudgetResp used = fixture.budget();
            assertThat(used.usedAmount()).isEqualByComparingTo("24125");
            assertThat(used.occupiedAmount()).isEqualByComparingTo("0");
            assertThat(fixture.stock(pen.id())).isZero();
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_asset")).isZero();

            assertThatThrownBy(() -> fixture.purchaseService.receive(TENANT, FINANCE, purchaseId, receive("r0")))
                .hasMessageContaining("采购尚未下单，不能验收");
            assertThatThrownBy(() -> fixture.purchaseService.order(TENANT, APPLICANT, purchaseId, order(TODAY)))
                .hasMessageContaining("不能为本人的采购登记下单");
            assertThatThrownBy(() -> fixture.purchaseService.order(TENANT, FINANCE, purchaseId, order(TODAY
                .plusDays(1)))).hasMessageContaining("下单日期不能晚于今天");
            OaPurchaseResp ordered = fixture.purchaseService.order(TENANT, FINANCE, purchaseId, order(TODAY));
            assertThat(ordered.status()).isEqualTo(OaPurchaseHook.STATUS_ORDERED);
            assertThat(ordered.supplier()).isEqualTo("京东企业购");
            assertThat(ordered.ordererName()).isEqualTo("Carol");
            assertThatThrownBy(() -> fixture.purchaseService.order(TENANT, FINANCE, purchaseId, order(TODAY)))
                .hasMessageContaining("该采购已登记下单");

            List<OaPurchaseItemResp> items = fixture.purchaseService.detail(TENANT, null, purchaseId).items();
            long penLine = items.get(0).id();
            long laptopLine = items.get(1).id();
            long serviceLine = items.get(2).id();
            assertThatThrownBy(() -> fixture.purchaseService
                .receive(TENANT, APPLICANT, purchaseId, receive("r1", line(penLine, 1))))
                .hasMessageContaining("不能为本人的采购登记验收");
            assertThatThrownBy(() -> fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r1", line(penLine, 11))))
                .hasMessageContaining("第 1 行验收数量超过未验收数量 10");
            assertThatThrownBy(() -> fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r1", line(penLine, 1), line(penLine, 1))))
                .hasMessageContaining("第 2 行明细重复");
            assertThatThrownBy(() -> fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r1", line(-1, 1))))
                .hasMessageContaining("第 1 行明细不属于该采购");

            OaPurchaseDetailResp partial = fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r1", line(penLine, 4), line(laptopLine, 1)));
            assertThat(partial.purchase().status()).isEqualTo(OaPurchaseHook.STATUS_PARTIAL);
            assertThat(fixture.stock(pen.id())).isEqualTo(4);
            assertThat(partial.assets()).singleElement().satisfies(asset -> {
                assertThat(asset.status()).isEqualTo(OaAssetStatus.IN_STOCK);
                assertThat(asset.assetNo()).matches("AS\\d{6}\\d{4,}");
                assertThat(asset.originalValue()).isEqualByComparingTo("12000");
                assertThat(asset.location()).isEqualTo("仓库 A");
            });
            fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r1", line(penLine, 4), line(laptopLine, 1)));
            assertThat(fixture.stock(pen.id())).isEqualTo(4);
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_asset")).isEqualTo(1);
            assertThatThrownBy(() -> fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r2", line(penLine, 7))))
                .hasMessageContaining("第 1 行验收数量超过未验收数量 6");

            OaPurchaseDetailResp received = fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r2", line(penLine, 6), line(laptopLine, 1), line(serviceLine, 1)));
            assertThat(received.purchase().status()).isEqualTo(OaPurchaseHook.STATUS_RECEIVED);
            assertThat(received.purchase().receivedTime()).isNotNull();
            assertThat(received.items()).allSatisfy(item -> assertThat(item.receivedQty()).isEqualTo(item.quantity()));
            assertThat(received.receipts()).hasSize(2);
            assertThat(received.receipts().get(1).lines()).hasSize(3);
            assertThat(received.assets()).hasSize(2)
                .allSatisfy(asset -> assertThat(asset.purchaseNo()).isEqualTo(approving.purchaseNo()));
            assertThat(fixture.stock(pen.id())).isEqualTo(10);
            assertThat(fixture.assetService.supplyLedger(TENANT, pen.id())).extracting(OaSupplyLedgerResp::quantity)
                .containsExactly(6, 4);
            assertThat(fixture.assetService.supplyLedger(TENANT, pen.id())).allSatisfy(ledger -> assertThat(ledger
                .refType()).isEqualTo(OaPurchaseService.REF_PURCHASE));
            assertThat(fixture
                .count("SELECT COUNT(*) FROM oa_outbox WHERE event_type = '" + OaPurchaseService.EVENT_RECEIVED + "'"))
                .isEqualTo(2);
            assertThatThrownBy(() -> fixture.purchaseService
                .receive(TENANT, FINANCE, purchaseId, receive("r3", line(penLine, 1)))).hasMessageContaining("采购已全部验收");
            assertThatThrownBy(() -> fixture.purchaseService.detail(TENANT, SUPERVISOR, purchaseId))
                .hasMessageContaining("采购单不存在");
            assertThat(fixture.purchaseService.purchases(TENANT, "RECEIVED", "京东", null, 1, 10)).hasSize(1);
            assertThat(fixture.purchaseService.countPurchases(TENANT, null, null, APPLICANT)).isEqualTo(1);
            assertThatThrownBy(() -> fixture.purchaseService.purchases(TENANT, "BOGUS", null, null, 1, 10))
                .hasMessageContaining("状态无效");

            assertThat(fixture.purchaseService.budgetLedger(TENANT, granted.id()))
                .extracting(OaPurchaseBudgetLedgerResp::action)
                .containsExactly(OaPurchaseHook.LEDGER_USE, OaPurchaseHook.LEDGER_OCCUPY, OaPurchaseHook.LEDGER_ADJUST, OaPurchaseHook.LEDGER_GRANT);
            assertThat(fixture.purchaseService.budgetLedger(TENANT, granted.id()).get(2).amount())
                .isEqualByComparingTo("5000");
        });
    }

    @Test
    void rejectReleasesBudgetAndConcurrentSubmitsRespectIt() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaPurchaseBudgetResp granted = fixture.purchaseService.saveBudget(TENANT, GM, budget(DEPT, "1000", null));
            long rejected = fixture.submitPurchase("p1", row("打印机", "OTHER", null, 1, "600"));
            fixture.approvalService.reject(TENANT, SUPERVISOR, fixture.task(rejected)
                .getId(), new OaApprovalActionReq("r1", "暂缓"));
            assertThat(fixture.purchaseStatus(fixture.purchaseId(rejected))).isEqualTo(OaPurchaseHook.STATUS_CLOSED);
            assertThat(fixture.budget().occupiedAmount()).isEqualByComparingTo("0");
            assertThatThrownBy(() -> fixture.purchaseService.order(TENANT, FINANCE, fixture
                .purchaseId(rejected), order(TODAY))).hasMessageContaining("采购已关闭，不能下单");

            long withdrawn = fixture.submitPurchase("p2", row("打印机", "OTHER", null, 1, "600"));
            fixture.approvalService.withdraw(TENANT, APPLICANT, withdrawn, action("w1"));
            assertThat(fixture.purchaseStatus(fixture.purchaseId(withdrawn))).isEqualTo(OaPurchaseHook.STATUS_CLOSED);
            assertThat(fixture.budget().availableAmount()).isEqualByComparingTo("1000");

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "race-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        fixture.submitPurchase(requestId, row("打印机", "OTHER", null, 1, "600"));
                        return true;
                    } catch (BusinessException e) {
                        assertThat(e.getMessage()).contains("部门采购预算不足");
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            pool.shutdown();
            assertThat(succeeded).isEqualTo(1);
            assertThat(fixture.budget().occupiedAmount()).isEqualByComparingTo("600");
            assertThat(fixture.purchaseService.budgetLedger(TENANT, granted.id()))
                .extracting(OaPurchaseBudgetLedgerResp::action)
                .containsExactly("OCCUPY", "RELEASE", "OCCUPY", "RELEASE", "OCCUPY", "GRANT");
        });
    }

    @Test
    void supplyStockAndConcurrentIssueNeverGoesNegative() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            OaSupplyItemResp paper = fixture.supplyItem("PAPER", "A4 纸");
            OaSupplyItemResp pen = fixture.supplyItem("PEN", "签字笔");
            assertThatThrownBy(() -> fixture.supplyItem("PAPER", "重复")).hasMessageContaining("用品编码已存在");
            assertThatThrownBy(() -> fixture.assetService.updateSupplyItem(TENANT, GM, paper
                .id(), new OaSupplyItemReq("PAPER", "A4 纸", null, "包", 2, "ENABLED", 9)))
                .hasMessageContaining("用品已被修改");
            OaSupplyItemResp renamed = fixture.assetService.updateSupplyItem(TENANT, GM, paper
                .id(), new OaSupplyItemReq("PAPER", "A4 复印纸", "70g", "包", 2, "ENABLED", paper.version()));
            assertThat(renamed.name()).isEqualTo("A4 复印纸");

            assertThat(fixture.assetService.stockIn(TENANT, GM, paper.id(), stock("s1", 5)).stock()).isEqualTo(5);
            assertThat(fixture.assetService.stockIn(TENANT, GM, paper.id(), stock("s1", 5)).stock()).isEqualTo(5);
            assertThatThrownBy(() -> fixture.assetService.stockIn(TENANT, GM, pen.id(), stock("s1", 5)))
                .hasMessageContaining("请求号已被使用");
            assertThatThrownBy(() -> fixture.assetService.stockOut(TENANT, GM, paper.id(), stock("s2", 6)))
                .hasMessageContaining("库存不足：当前 5，出库 6");
            assertThat(fixture.assetService.stockIn(TENANT, GM, pen.id(), stock("s3", 1)).stock()).isEqualTo(1);
            assertThat(fixture.assetService.stockOut(TENANT, GM, pen.id(), stock("s4", 1)).stock()).isZero();

            ExecutorService replayPool = Executors.newFixedThreadPool(3);
            CountDownLatch replayStart = new CountDownLatch(1);
            List<Future<Integer>> replays = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                replays.add(replayPool.submit(() -> {
                    replayStart.await();
                    return fixture.assetService.stockIn(TENANT, GM, pen.id(), stock("s-replay", 2)).stock();
                }));
            }
            replayStart.countDown();
            for (Future<Integer> replay : replays) {
                assertThat(replay.get(60, TimeUnit.SECONDS)).isEqualTo(2);
            }
            replayPool.shutdown();
            assertThat(fixture.stock(pen.id())).isEqualTo(2);
            assertThat(fixture.assetService.stockOut(TENANT, GM, pen.id(), stock("s4b", 2)).stock()).isZero();

            fixture.seedSupplyRelease();
            assertThatThrownBy(() -> fixture.submitSupply("q0", "PAPER", 6)).hasMessageContaining("库存不足：当前 5，申领 6");
            long appA = fixture.submitSupply("qa", "PAPER", 3);
            long appB = fixture.submitSupply("qb", "PAPER", 3);
            long requestA = fixture.supplyRequestId(appA);
            long requestB = fixture.supplyRequestId(appB);
            assertThatThrownBy(() -> fixture.assetService.issue(TENANT, FINANCE, requestA, op("i0")))
                .hasMessageContaining("领用尚未审批通过，不能发放");
            fixture.approve(SUPERVISOR, appA, "a1");
            fixture.approve(SUPERVISOR, appB, "a2");
            assertThat(fixture.supplyStatus(requestA)).isEqualTo(OaSupplyRequestHook.STATUS_PENDING_ISSUE);
            assertThat(fixture.stock(paper.id())).isEqualTo(5);
            assertThatThrownBy(() -> fixture.assetService.issue(TENANT, APPLICANT, requestA, op("i0")))
                .hasMessageContaining("不能为本人的领用登记发放");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (long requestId : List.of(requestA, requestB)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        fixture.assetService.issue(TENANT, FINANCE, requestId, op("issue-" + requestId));
                        return true;
                    } catch (BusinessException e) {
                        assertThat(e.getMessage()).contains("库存不足：当前 2，需发放 3");
                        return false;
                    }
                }));
            }
            start.countDown();
            int issued = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    issued++;
                }
            }
            pool.shutdown();
            assertThat(issued).isEqualTo(1);
            assertThat(fixture.stock(paper.id())).isEqualTo(2);
            assertThat(fixture.count("SELECT COUNT(*) FROM oa_supply_ledger WHERE ref_type = 'ISSUE'")).isEqualTo(1);
            long done = OaSupplyRequestHook.STATUS_ISSUED.equals(fixture.supplyStatus(requestA)) ? requestA : requestB;
            long pending = done == requestA ? requestB : requestA;
            fixture.assetService.issue(TENANT, FINANCE, done, op("issue-" + done));
            assertThat(fixture.stock(paper.id())).isEqualTo(2);
            assertThatThrownBy(() -> fixture.assetService.issue(TENANT, FINANCE, pending, op("issue-" + done)))
                .hasMessageContaining("请求号已被使用");

            fixture.assetService.stockIn(TENANT, GM, paper.id(), stock("s5", 1));
            assertThat(fixture.assetService.issue(TENANT, FINANCE, pending, op("issue-again")).request().status())
                .isEqualTo(OaSupplyRequestHook.STATUS_ISSUED);
            assertThat(fixture.stock(paper.id())).isZero();
            assertThatThrownBy(() -> fixture.assetService.issue(TENANT, FINANCE, pending, op("issue-third")))
                .hasMessageContaining("该领用已发放");
            assertThat(fixture.assetService.supplyLedger(TENANT, paper.id()))
                .extracting(OaSupplyLedgerResp::balanceAfter)
                .containsExactly(0, 3, 2, 5);
            assertThat(fixture
                .count("SELECT COUNT(*) FROM oa_outbox WHERE event_type = '" + OaAssetService.EVENT_SUPPLY_ISSUED + "'"))
                .isEqualTo(2);
            assertThatThrownBy(() -> fixture.assetService.supplyRequestDetail(TENANT, SUPERVISOR, pending))
                .hasMessageContaining("领用单不存在");
            assertThat(fixture.assetService.supplyRequestDetail(TENANT, APPLICANT, pending).items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(3));
        });
    }

    @Test
    void assetOperationsScrapAndStocktake() {
        runner().run(context -> {
            Fixture fixture = fixture(context);
            assertThatThrownBy(() -> fixture.assetService.createAsset(TENANT, GM, asset("c0", TODAY.plusDays(1))))
                .hasMessageContaining("购置日期不能晚于今天");
            OaAssetResp projector = fixture.assetService.createAsset(TENANT, GM, asset("c1", null));
            assertThat(projector.status()).isEqualTo(OaAssetStatus.IN_STOCK);
            assertThat(projector.assetNo()).matches("AS\\d{6}\\d{4,}");
            assertThat(fixture.assetService.createAsset(TENANT, GM, asset("c1", null)).id()).isEqualTo(projector.id());
            long id = projector.id();

            assertThatThrownBy(() -> fixture.operate(id, "o0", "RETURN", null)).hasMessageContaining("资产当前状态为 在库，不能归还");
            assertThatThrownBy(() -> fixture.operate(id, "o0", "ASSIGN", null)).hasMessageContaining("请选择使用人");
            assertThatThrownBy(() -> fixture.operate(id, "o0", "ASSIGN", 999L)).hasMessageContaining("使用人不是在职员工");
            OaAssetResp assigned = fixture.operate(id, "o1", "ASSIGN", SUPERVISOR);
            assertThat(assigned.status()).isEqualTo(OaAssetStatus.IN_USE);
            assertThat(assigned.holderId()).isEqualTo(SUPERVISOR);
            assertThat(assigned.deptName()).isEqualTo("研发部");
            assertThat(fixture.operate(id, "o1", "ASSIGN", SUPERVISOR).version()).isEqualTo(assigned.version());
            assertThatThrownBy(() -> fixture.operate(id, "o2", "TRANSFER", SUPERVISOR))
                .hasMessageContaining("调拨对象与当前使用人相同");
            OaAssetResp transferred = fixture.operate(id, "o2", "TRANSFER", FINANCE);
            assertThat(transferred.holderName()).isEqualTo("Carol");
            assertThat(transferred.deptName()).isEqualTo("财务部");
            assertThat(fixture.operate(id, "o3", "REPAIR", null).status()).isEqualTo(OaAssetStatus.REPAIR);
            OaAssetResp repaired = fixture.operate(id, "o4", "REPAIRED", null);
            assertThat(repaired.status()).isEqualTo(OaAssetStatus.IN_USE);
            assertThat(repaired.holderId()).isEqualTo(FINANCE);
            assertThat(fixture.assetService.assets(TENANT, null, null, FINANCE, 1, 10)).hasSize(1);
            assertThatThrownBy(() -> fixture.assetService.assetDetail(TENANT, SUPERVISOR, id))
                .hasMessageContaining("资产不存在");

            fixture.seedScrapRelease();
            assertThatThrownBy(() -> fixture.submitScrap("x0", projector.assetNo()))
                .hasMessageContaining("资产在用，请先归还后再申请报废");
            assertThatThrownBy(() -> fixture.submitScrap("x0", "AS000000")).hasMessageContaining("资产编号 AS000000 不存在");
            OaAssetResp returned = fixture.operate(id, "o5", "RETURN", null);
            assertThat(returned.holderId()).isNull();
            assertThat(returned.deptId()).isNull();
            long scrapApp = fixture.submitScrap("x1", projector.assetNo());
            assertThat(fixture.variable(scrapApp, OaAssetScrapHook.VAR_VALUE)).isEqualTo(8000.0);
            assertThat(fixture.assetService.assetDetail(TENANT, null, id).asset().scrapPending()).isTrue();
            assertThatThrownBy(() -> fixture.submitScrap("x2", projector.assetNo()))
                .hasMessageContaining("该资产已有进行中的报废申请");
            assertThatThrownBy(() -> fixture.operate(id, "o6", "ASSIGN", SUPERVISOR))
                .hasMessageContaining("资产有进行中的报废申请，不能操作");
            long scrapId = fixture.scrapId(scrapApp);
            assertThatThrownBy(() -> fixture.assetService.executeScrap(TENANT, FINANCE, scrapId, op("e0")))
                .hasMessageContaining("报废尚未审批通过，不能执行");
            fixture.approve(SUPERVISOR, scrapApp, "a1");
            fixture.approve(FINANCE, scrapApp, "a2");
            Task gmTask = fixture.task(scrapApp);
            assertThat(gmTask.getTaskDefinitionKey()).isEqualTo("gmApprove");
            fixture.approvalService.approve(TENANT, GM, gmTask.getId(), action("a3"));
            assertThat(fixture.assetService.scraps(TENANT, OaAssetScrapHook.STATUS_PENDING_SCRAP, null, 1, 10))
                .singleElement()
                .satisfies(scrap -> assertThat(scrap.assetNo()).isEqualTo(projector.assetNo()));
            assertThatThrownBy(() -> fixture.assetService.executeScrap(TENANT, APPLICANT, scrapId, op("e1")))
                .hasMessageContaining("不能为本人的报废申请执行报废");
            OaAssetScrapResp scrapped = fixture.assetService.executeScrap(TENANT, FINANCE, scrapId, op("e1"));
            assertThat(scrapped.status()).isEqualTo(OaAssetScrapHook.STATUS_SCRAPPED);
            assertThat(scrapped.executorName()).isEqualTo("Carol");
            assertThat(fixture.assetService.executeScrap(TENANT, FINANCE, scrapId, op("e1")).status())
                .isEqualTo(OaAssetScrapHook.STATUS_SCRAPPED);
            assertThatThrownBy(() -> fixture.assetService.executeScrap(TENANT, FINANCE, scrapId, op("e2")))
                .hasMessageContaining("该报废已执行");
            assertThat(fixture
                .count("SELECT COUNT(*) FROM oa_outbox WHERE event_type = '" + OaAssetService.EVENT_ASSET_SCRAPPED + "'"))
                .isEqualTo(1);
            assertThatThrownBy(() -> fixture.operate(id, "o7", "REPAIR", null)).hasMessageContaining("资产已报废");
            OaAssetDetailResp detail = fixture.assetService.assetDetail(TENANT, null, id);
            assertThat(detail.asset().status()).isEqualTo(OaAssetStatus.SCRAPPED);
            assertThat(detail.logs()).extracting(OaAssetLogResp::action)
                .containsExactly("SCRAP", "RETURN", "REPAIRED", "REPAIR", "TRANSFER", "ASSIGN", "IN");
            assertThat(detail.logs().get(4).fromHolderName()).isEqualTo("Bob");
            assertThat(detail.scraps()).hasSize(1);

            OaSupplyItemResp ink = fixture.supplyItem("INK", "墨盒");
            fixture.assetService.stockIn(TENANT, GM, ink.id(), stock("s1", 10));
            OaAssetResp chair = fixture.assetService.createAsset(TENANT, GM, asset("c2", null));
            OaStocktakeDetailResp stocktake = fixture.assetService
                .createStocktake(TENANT, GM, new OaStocktakeCreateReq("年终盘点"));
            assertThat(stocktake.lines()).extracting(OaStocktakeLineResp::targetType)
                .containsExactly("SUPPLY", "ASSET");
            OaStocktakeLineResp inkLine = stocktake.lines().get(0);
            OaStocktakeLineResp chairLine = stocktake.lines().get(1);
            assertThat(inkLine.bookQty()).isEqualTo(10);
            assertThat(chairLine.targetId()).isEqualTo(chair.id());
            long stocktakeId = stocktake.stocktake().id();
            assertThatThrownBy(() -> fixture.assetService.createStocktake(TENANT, GM, new OaStocktakeCreateReq("重复")))
                .hasMessageContaining("已有进行中的盘点");
            assertThatThrownBy(() -> fixture.assetService.closeStocktake(TENANT, GM, stocktakeId))
                .hasMessageContaining("还有 2 项未盘点");
            assertThatThrownBy(() -> fixture.assetService.countStocktake(TENANT, stocktakeId, counts(chairLine
                .id(), 2))).hasMessageContaining("资产实盘数量只能是 0 或 1");
            fixture.assetService.countStocktake(TENANT, stocktakeId, counts(inkLine.id(), 8));
            OaStocktakeDetailResp counted = fixture.assetService.countStocktake(TENANT, stocktakeId, counts(chairLine
                .id(), 0));
            assertThat(counted.stocktake().countedCount()).isEqualTo(2);
            assertThat(counted.stocktake().varianceCount()).isEqualTo(2);
            OaStocktakeDetailResp closed = fixture.assetService.closeStocktake(TENANT, GM, stocktakeId);
            assertThat(closed.stocktake().status()).isEqualTo("CLOSED");
            assertThat(closed.lines()).extracting(OaStocktakeLineResp::adjustedQty).containsExactly(-2, -1);
            assertThat(fixture.stock(ink.id())).isEqualTo(8);
            assertThat(fixture.assetService.supplyLedger(TENANT, ink.id()).get(0)).satisfies(ledger -> {
                assertThat(ledger.action()).isEqualTo("ADJUST");
                assertThat(ledger.quantity()).isEqualTo(-2);
                assertThat(ledger.refType()).isEqualTo(OaAssetService.REF_STOCKTAKE);
            });
            assertThat(fixture.assetService.assetDetail(TENANT, null, chair.id()).logs().get(0)).satisfies(log -> {
                assertThat(log.action()).isEqualTo("CHECK");
                assertThat(log.remark()).isEqualTo("盘点未找到");
            });
            assertThatThrownBy(() -> fixture.assetService.countStocktake(TENANT, stocktakeId, counts(inkLine.id(), 8)))
                .hasMessageContaining("盘点已完成");
            assertThat(fixture.assetService.createStocktake(TENANT, GM, new OaStocktakeCreateReq("复盘")).lines())
                .hasSize(2);
        });
    }

    private static OaApprovalActionReq action(String requestId) {
        return new OaApprovalActionReq(requestId, null);
    }

    private static OaOperationReq op(String requestId) {
        return new OaOperationReq(requestId, null);
    }

    private static OaPurchaseBudgetReq budget(long deptId, String amount, Integer version) {
        return new OaPurchaseBudgetReq(deptId, YEAR, new BigDecimal(amount), "年度预算", version);
    }

    private static OaPurchaseOrderReq order(LocalDate date) {
        return new OaPurchaseOrderReq("京东企业购", "JD-1", date);
    }

    private static OaPurchaseReceiveLine line(long itemId, int quantity) {
        return new OaPurchaseReceiveLine(itemId, quantity);
    }

    private static OaPurchaseReceiveReq receive(String requestId, OaPurchaseReceiveLine... lines) {
        return new OaPurchaseReceiveReq(requestId, TODAY, "仓库 A", null, List.of(lines));
    }

    private static OaSupplyStockReq stock(String requestId, int quantity) {
        return new OaSupplyStockReq(requestId, quantity, null);
    }

    private static OaAssetCreateReq asset(String requestId, LocalDate acquired) {
        return new OaAssetCreateReq(requestId, "投影仪", "IT", "4K", new BigDecimal("8000"), "会议室", acquired);
    }

    private static OaStocktakeCountReq counts(long lineId, int quantity) {
        return new OaStocktakeCountReq(List.of(new OaStocktakeCount(lineId, quantity)));
    }

    private static Map<String, Object> row(String name, String category, String code, int quantity, String price) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(OaPurchaseHook.COL_NAME, name);
        row.put(OaPurchaseHook.COL_CATEGORY, category);
        if (code != null) {
            row.put(OaPurchaseHook.COL_SUPPLY_CODE, code);
        }
        row.put(OaPurchaseHook.COL_QUANTITY, quantity);
        row.put(OaPurchaseHook.COL_PRICE, new BigDecimal(price));
        return row;
    }

    private ApplicationContextRunner runner() {
        String url = "jdbc:h2:mem:oa-purchase-" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000";
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations
            .of(DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineAutoConfiguration.class, org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration.class))
            .withUserConfiguration(OaFlowableConfiguration.class)
            .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "flowable.process.enabled=true", "flowable.app.enabled=false", "flowable.database-schema-update=true", "flowable.async-executor-activate=false", "flowable.history-level=full");
    }

    private static Fixture fixture(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        OaExpenseServiceTest.createTables(jdbc);
        OaExpenseServiceTest.seedAccounts(jdbc);
        createAssetTables(jdbc);
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (10, 101, '研发部', 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, parent_id, deleted) VALUES (11, 101, '研发一组', 10, 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (20, 101, '财务部', 0)");
        jdbc.update("INSERT INTO sys_dept (id, tenant_id, name, deleted) VALUES (30, 102, '他租户', 0)");
        OaPurchaseRepository purchaseRepository = new OaPurchaseRepository(jdbc);
        OaAssetRepository assetRepository = new OaAssetRepository(jdbc);

        RepositoryService repositoryService = context.getBean(RepositoryService.class);
        repositoryService.createDeployment()
            .name("oa-purchase")
            .tenantId(Long.toString(TENANT))
            .addString("purchase.bpmn20.xml", OaPurchaseService.BPMN)
            .addString("supply.bpmn20.xml", OaAssetService.SUPPLY_BPMN)
            .addString("scrap.bpmn20.xml", OaAssetService.SCRAP_BPMN)
            .deploy();

        RuntimeService runtimeService = context.getBean(RuntimeService.class);
        TaskService taskService = context.getBean(TaskService.class);
        PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OaExpenseServiceTest.StubStaffDirectory directory = new OaExpenseServiceTest.StubStaffDirectory();

        OaLaunchRepository launchRepository = new OaLaunchRepository(jdbc);
        OaBusinessHooks hooks = new OaBusinessHooks(List
            .of(new OaPurchaseHook(purchaseRepository, assetRepository, directory), new OaSupplyRequestHook(assetRepository), new OaAssetScrapHook(assetRepository)), launchRepository);
        OaOutboxService outboxService = new OaOutboxService(new OaOutboxRepository(jdbc), objectMapper);
        OaPurchaseService purchaseService = new OaPurchaseService(purchaseRepository, assetRepository, null, null, outboxService, objectMapper, transactionManager);
        OaAssetService assetService = new OaAssetService(assetRepository, directory, null, null, outboxService, objectMapper, transactionManager);

        seedRelease(jdbc, repositoryService, 1, PURCHASE_RELEASE, OaPurchaseService.MODEL_KEY, OaPurchaseService.BPMN, OaPurchaseHook.BUSINESS_TYPE, purchaseService
            .templateSchema());

        OaApprovalEngine engine = new OaApprovalEngine(runtimeService, taskService, context
            .getBean(org.flowable.engine.HistoryService.class), repositoryService);
        OaProcessLauncher launcher = new OaProcessLauncher(new OaEngineTemplate(runtimeService, taskService), new OaAssigneeResolver(directory));
        OaAttachmentService attachmentService = new OaAttachmentService(new OaAttachmentRepository(jdbc), engine);
        OaCcService ccService = new OaCcService(new OaCcRepository(jdbc), launchRepository, outboxService);
        OaLaunchService launchService = new OaLaunchService(launchRepository, new OaBpmnValidator(List
            .of()), new OaFormValueValidator(), attachmentService, launcher, engine, outboxService, ccService, new OaDraftRepository(jdbc), hooks, objectMapper, transactionManager);
        OaApprovalService approvalService = new OaApprovalService(new OaApprovalRepository(jdbc), launchRepository, new OaDelegateRepository(jdbc), directory, engine, outboxService, ccService, hooks, objectMapper, transactionManager);
        return new Fixture(jdbc, repositoryService, hooks, purchaseService, assetService, launchService, approvalService, runtimeService, taskService);
    }

    private static void seedRelease(JdbcTemplate jdbc,
                                    RepositoryService repositoryService,
                                    long modelId,
                                    long releaseId,
                                    String processKey,
                                    String bpmn,
                                    String businessType,
                                    String schema) {
        String definitionId = repositoryService.createProcessDefinitionQuery()
            .processDefinitionTenantId(Long.toString(TENANT))
            .processDefinitionKey(processKey)
            .singleResult()
            .getId();
        long formId = modelId + 19;
        long versionId = modelId + 29;
        jdbc.update("""
            INSERT INTO oa_process_model
              (id, tenant_id, model_key, name, category, description, status, business_type, current_release_id,
               current_release_version, form_id, form_version_id, version, create_user, create_time, deleted)
            VALUES (?, 101, ?, ?, 'asset', '说明', 'PUBLISHED', ?, ?, 1, ?, ?, 0, 7, CURRENT_TIMESTAMP, 0)
            """, modelId, processKey, processKey, businessType, releaseId, formId, versionId);
        jdbc.update("""
            INSERT INTO oa_process_release
              (id, tenant_id, model_id, release_version, bpmn_xml, xml_digest, engine_definition_id,
               process_definition_key, process_definition_version, form_id, form_version_id, form_version,
               launch_scope, status, published_by, publish_time, remark)
            VALUES (?, 101, ?, 1, ?, ?, ?, ?, 1, ?, ?, 1, '{"depts":[10]}', 'ACTIVE', 7, CURRENT_TIMESTAMP, NULL)
            """, releaseId, modelId, bpmn, "a".repeat(64), definitionId, processKey, formId, versionId);
        jdbc.update("INSERT INTO oa_form (id, tenant_id, form_key, name, status, deleted) VALUES (?, 101, ?, ?, 'PUBLISHED', 0)", formId, processKey, processKey);
        jdbc.update("""
            INSERT INTO oa_form_version
              (id, tenant_id, form_id, version, schema_json, schema_digest, field_count, published_by, publish_time)
            VALUES (?, 101, ?, 1, ?, ?, 3, 7, CURRENT_TIMESTAMP)
            """, versionId, formId, schema, "b".repeat(64));
    }

    static void createAssetTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE oa_purchase_budget (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, dept_id BIGINT NOT NULL,
              budget_year INT NOT NULL, amount DECIMAL(14,2) NOT NULL, used_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
              occupied_amount DECIMAL(14,2) NOT NULL DEFAULT 0, remark VARCHAR(500), version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_budget_dept_year UNIQUE (tenant_id, dept_id, budget_year)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_purchase_budget_ledger (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, budget_id BIGINT NOT NULL,
              action VARCHAR(20) NOT NULL, amount DECIMAL(14,2) NOT NULL, purchase_id BIGINT, operator_id BIGINT NOT NULL,
              remark VARCHAR(500), create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_budget_ledger_purchase UNIQUE (tenant_id, purchase_id, action)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_purchase (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, dept_id BIGINT NOT NULL,
              budget_id BIGINT NOT NULL, purchase_no VARCHAR(32), total_amount DECIMAL(14,2) NOT NULL,
              item_count INT NOT NULL, expected_date DATE, status VARCHAR(20) NOT NULL, supplier VARCHAR(100),
              order_no VARCHAR(64), order_date DATE, orderer_id BIGINT, ordered_time TIMESTAMP, received_time TIMESTAMP,
              create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_purchase_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_purchase_no UNIQUE (tenant_id, purchase_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_purchase_item (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              purchase_id BIGINT NOT NULL, line_no INT NOT NULL, item_name VARCHAR(100) NOT NULL,
              category VARCHAR(20) NOT NULL, supply_code VARCHAR(32), spec VARCHAR(200), quantity INT NOT NULL,
              unit_price DECIMAL(14,2) NOT NULL, amount DECIMAL(14,2) NOT NULL, received_qty INT NOT NULL DEFAULT 0,
              CONSTRAINT uk_purchase_line UNIQUE (tenant_id, purchase_id, line_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_purchase_receipt (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              purchase_id BIGINT NOT NULL, request_id VARCHAR(64) NOT NULL, receive_date DATE NOT NULL,
              receiver_id BIGINT NOT NULL, remark VARCHAR(500), create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_receipt_request UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_purchase_receipt_line (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              receipt_id BIGINT NOT NULL, item_id BIGINT NOT NULL, quantity INT NOT NULL,
              CONSTRAINT uk_receipt_item UNIQUE (tenant_id, receipt_id, item_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_supply_item (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, code VARCHAR(32) NOT NULL,
              name VARCHAR(100) NOT NULL, spec VARCHAR(200), unit VARCHAR(16) NOT NULL, stock INT NOT NULL DEFAULT 0,
              safety_stock INT NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
              version INT NOT NULL DEFAULT 0, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL,
              update_user BIGINT, update_time TIMESTAMP,
              CONSTRAINT uk_supply_code UNIQUE (tenant_id, code),
              CONSTRAINT ck_supply_stock CHECK (stock >= 0)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_supply_ledger (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, item_id BIGINT NOT NULL,
              action VARCHAR(20) NOT NULL, quantity INT NOT NULL, balance_after INT NOT NULL, ref_type VARCHAR(20) NOT NULL,
              ref_id BIGINT, request_id VARCHAR(64), operator_id BIGINT NOT NULL, remark VARCHAR(500),
              create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_supply_ledger_request UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_supply_request (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, item_count INT NOT NULL,
              total_quantity INT NOT NULL, status VARCHAR(20) NOT NULL, issue_request_id VARCHAR(64), issuer_id BIGINT,
              issued_time TIMESTAMP, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_supply_request_application UNIQUE (tenant_id, application_id),
              CONSTRAINT uk_supply_issue_request UNIQUE (tenant_id, issue_request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_supply_request_item (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              supply_request_id BIGINT NOT NULL, item_id BIGINT NOT NULL, quantity INT NOT NULL,
              CONSTRAINT uk_supply_request_item UNIQUE (tenant_id, supply_request_id, item_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_asset (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, asset_no VARCHAR(32),
              name VARCHAR(100) NOT NULL, category VARCHAR(32), spec VARCHAR(200), status VARCHAR(20) NOT NULL,
              holder_id BIGINT, dept_id BIGINT, location VARCHAR(100), original_value DECIMAL(14,2) NOT NULL DEFAULT 0,
              purchase_id BIGINT, acquired_date DATE NOT NULL, version INT NOT NULL DEFAULT 0,
              create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP,
              CONSTRAINT uk_asset_no UNIQUE (tenant_id, asset_no)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_asset_log (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, asset_id BIGINT NOT NULL,
              action VARCHAR(20) NOT NULL, from_status VARCHAR(20), to_status VARCHAR(20) NOT NULL, from_holder_id BIGINT,
              to_holder_id BIGINT, location VARCHAR(100), ref_type VARCHAR(20) NOT NULL, ref_id BIGINT,
              request_id VARCHAR(64), operator_id BIGINT NOT NULL, remark VARCHAR(500), create_time TIMESTAMP NOT NULL,
              CONSTRAINT uk_asset_log_request UNIQUE (tenant_id, request_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_asset_scrap (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              application_id BIGINT NOT NULL, applicant_id BIGINT NOT NULL, asset_id BIGINT NOT NULL,
              status VARCHAR(20) NOT NULL, executor_id BIGINT, executed_time TIMESTAMP, create_time TIMESTAMP NOT NULL,
              update_time TIMESTAMP,
              CONSTRAINT uk_scrap_application UNIQUE (tenant_id, application_id)
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_stocktake (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL, title VARCHAR(100) NOT NULL,
              status VARCHAR(20) NOT NULL, create_user BIGINT NOT NULL, create_time TIMESTAMP NOT NULL, closed_by BIGINT,
              closed_time TIMESTAMP
            )
            """);
        jdbc.execute("""
            CREATE TABLE oa_stocktake_line (
              id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, tenant_id BIGINT NOT NULL,
              stocktake_id BIGINT NOT NULL, target_type VARCHAR(20) NOT NULL, target_id BIGINT NOT NULL,
              target_code VARCHAR(32), target_name VARCHAR(100) NOT NULL, book_qty INT NOT NULL, counted_qty INT,
              adjusted_qty INT,
              CONSTRAINT uk_stocktake_target UNIQUE (tenant_id, stocktake_id, target_type, target_id)
            )
            """);
    }

    private record Fixture(JdbcTemplate jdbc, RepositoryService repositoryService, OaBusinessHooks hooks,
                           OaPurchaseService purchaseService, OaAssetService assetService,
                           OaLaunchService launchService, OaApprovalService approvalService,
                           RuntimeService runtimeService, TaskService taskService) {

        private void seedSupplyRelease() {
            seedRelease(jdbc, repositoryService, 2, SUPPLY_RELEASE, OaAssetService.SUPPLY_MODEL_KEY, OaAssetService.SUPPLY_BPMN, OaSupplyRequestHook.BUSINESS_TYPE, assetService
                .supplySchema(assetService.supplyItems(TENANT, "ENABLED", null)));
        }

        private void seedScrapRelease() {
            seedRelease(jdbc, repositoryService, 3, SCRAP_RELEASE, OaAssetService.SCRAP_MODEL_KEY, OaAssetService.SCRAP_BPMN, OaAssetScrapHook.BUSINESS_TYPE, assetService
                .scrapSchema());
        }

        private OaSupplyItemResp supplyItem(String code, String name) {
            return assetService
                .createSupplyItem(TENANT, GM, new OaSupplyItemReq(code, name, null, "个", 2, "ENABLED", null));
        }

        private long submit(String requestId, long releaseId, String title, Map<String, Object> form) {
            return launchService.submit(TENANT, APPLICANT, releaseId, new OaLaunchSubmitReq(requestId, title, form, List
                .of())).applicationId();
        }

        @SafeVarargs
        private long submitPurchase(String requestId, Map<String, Object>... rows) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaPurchaseHook.FIELD_ITEMS, List.of(rows));
            form.put(OaPurchaseHook.FIELD_REASON, "部门办公采购");
            return submit(requestId, PURCHASE_RELEASE, "办公采购", form);
        }

        private long submitSupply(String requestId, String code, int quantity) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(OaSupplyRequestHook.COL_CODE, code);
            row.put(OaSupplyRequestHook.COL_QUANTITY, quantity);
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaSupplyRequestHook.FIELD_ITEMS, List.of(row));
            form.put(OaSupplyRequestHook.FIELD_REASON, "日常办公");
            return submit(requestId, SUPPLY_RELEASE, "领用", form);
        }

        private long submitScrap(String requestId, String assetNo) {
            Map<String, Object> form = new LinkedHashMap<>();
            form.put(OaAssetScrapHook.FIELD_ASSET_NO, assetNo);
            form.put(OaAssetScrapHook.FIELD_REASON, "损坏无法维修");
            return submit(requestId, SCRAP_RELEASE, "报废", form);
        }

        private OaAssetResp operate(long id, String requestId, String action, Long holderId) {
            return assetService.operate(TENANT, GM, id, new OaAssetActionReq(requestId, action, holderId, null, null));
        }

        private void approve(long userId, long applicationId, String requestId) {
            approvalService.approve(TENANT, userId, task(applicationId).getId(), action(requestId));
        }

        private Task task(long applicationId) {
            Task task = taskService.createTaskQuery().processInstanceId(instanceId(applicationId)).singleResult();
            assertThat(task).isNotNull();
            return task;
        }

        private String instanceId(long applicationId) {
            return jdbc
                .queryForObject("SELECT process_instance_id FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private Object variable(long applicationId, String name) {
            return runtimeService.getVariable(instanceId(applicationId), name);
        }

        private String applicationStatus(long applicationId) {
            return jdbc.queryForObject("SELECT status FROM oa_application WHERE id = ?", String.class, applicationId);
        }

        private OaPurchaseBudgetResp budget() {
            return purchaseService.budgets(TENANT, YEAR).get(0);
        }

        private long purchaseId(long applicationId) {
            return jdbc
                .queryForObject("SELECT id FROM oa_purchase WHERE application_id = ?", Long.class, applicationId);
        }

        private String purchaseStatus(long purchaseId) {
            return jdbc.queryForObject("SELECT status FROM oa_purchase WHERE id = ?", String.class, purchaseId);
        }

        private long supplyRequestId(long applicationId) {
            return jdbc
                .queryForObject("SELECT id FROM oa_supply_request WHERE application_id = ?", Long.class, applicationId);
        }

        private String supplyStatus(long requestId) {
            return jdbc.queryForObject("SELECT status FROM oa_supply_request WHERE id = ?", String.class, requestId);
        }

        private long scrapId(long applicationId) {
            return jdbc
                .queryForObject("SELECT id FROM oa_asset_scrap WHERE application_id = ?", Long.class, applicationId);
        }

        private int stock(long itemId) {
            return jdbc.queryForObject("SELECT stock FROM oa_supply_item WHERE id = ?", Integer.class, itemId);
        }

        private long count(String sql) {
            return jdbc.queryForObject(sql, Long.class);
        }
    }
}
