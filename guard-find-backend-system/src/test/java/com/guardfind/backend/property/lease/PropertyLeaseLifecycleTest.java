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

package com.guardfind.backend.property.lease;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PropertyLeaseLifecycleTest extends PropertyLeaseTest {
    PropertyLeaseLifecycleService lifecycle;

    @BeforeEach
    void lifecycle() {
        var proxy = new ProxyFactory(new PropertyLeaseLifecycleService(store, service, notices, new ObjectMapper()));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        lifecycle = (PropertyLeaseLifecycleService)proxy.getProxy();
    }

    long bill(long lease, String kind, BigDecimal amount) {
        String today = LocalDate.now().toString();
        lifecycle
            .createBill(lease, 9, new PropertyLeaseLifecycleService.BillInput(kind, kind, today, today, today, amount));
        return jdbc.queryForObject("SELECT MAX(id) FROM gf_property_lease_bill WHERE lease_id=?", Long.class, lease);
    }

    void paid(long lease, long bill) {
        lifecycle
            .billAction(lease, bill, 1, false, new PropertyLeaseLifecycleService.BillAction("SUBMIT", 0, "bank-123", LocalDate
                .now()
                .toString(), null, null));
        lifecycle
            .billAction(lease, bill, 2, false, new PropertyLeaseLifecycleService.BillAction("CONFIRM", 1, null, null, null, null));
    }

    long document(long lease, String kind, List<PropertyLeaseLifecycleService.Deduction> deductions) {
        lifecycle.createDocument(lease, 9, new PropertyLeaseLifecycleService.DocumentInput(kind, LocalDate.now()
            .toString(), "桌椅完好", "墙面无破损", "12.35", "45.10", "", 2, deductions, "双方核对实际情况", List.of()));
        return jdbc
            .queryForObject("SELECT MAX(id) FROM gf_property_lease_document WHERE lease_id=?", Long.class, lease);
    }

    void confirm(long lease, long doc) {
        String hash = jdbc
            .queryForObject("SELECT content_hash FROM gf_property_lease_document WHERE id=?", String.class, doc);
        lifecycle
            .documentAction(lease, doc, 1, false, new PropertyLeaseLifecycleService.DocumentAction("CONFIRM", 0, hash, null, null, null));
        lifecycle
            .documentAction(lease, doc, 2, false, new PropertyLeaseLifecycleService.DocumentAction("CONFIRM", 1, hash, null, null, null));
    }

    @Test
    void payerCannotConfirmTheirOwnPaymentAndStaleOrForeignBillsAreRejected() {
        long lease = create();
        signed(lease);
        long bill = bill(lease, "DEPOSIT", new BigDecimal("2000.00"));
        assertThatThrownBy(() -> lifecycle.detail(lease, 3L, false)).hasMessageContaining("无权");
        lifecycle
            .billAction(lease, bill, 1, false, new PropertyLeaseLifecycleService.BillAction("SUBMIT", 0, "transfer", LocalDate
                .now()
                .toString(), null, null));
        assertThatThrownBy(() -> lifecycle
            .billAction(lease, bill, 1, false, new PropertyLeaseLifecycleService.BillAction("CONFIRM", 1, null, null, null, null)))
            .hasMessageContaining("出租人");
        assertThatThrownBy(() -> lifecycle
            .billAction(lease, bill, 2, false, new PropertyLeaseLifecycleService.BillAction("CONFIRM", 0, null, null, null, null)))
            .hasMessageContaining("刷新");
        lifecycle
            .billAction(lease, bill, 2, false, new PropertyLeaseLifecycleService.BillAction("CONFIRM", 1, null, null, null, null));
        assertThat(lifecycle.detail(lease, 1L, false).get("amountPaid")).isEqualTo(new BigDecimal("2000.00"));
        assertThatThrownBy(() -> lifecycle
            .billAction(lease, bill, 9, true, new PropertyLeaseLifecycleService.BillAction("VOID", 2, null, null, "oops", null)))
            .hasMessageContaining("状态");
    }

    @Test
    void generationUsesAnniversaryDatesProratesLastPeriodAndNeverDuplicates() {
        LocalDate start = LocalDate.of(LocalDate.now().getYear() + 1, 1, 31);
        long lease = Long.parseLong(service.create(draft(start, start.plusMonths(2).minusDays(1)), 9));
        signed(lease);
        lifecycle.generate(lease, 9, new PropertyLeaseLifecycleService.ScheduleInput(1, 3, true));
        assertThat(jdbc
            .queryForObject("SELECT COUNT(*) FROM gf_property_lease_bill WHERE lease_id=?", Long.class, lease))
            .isEqualTo(3L);
        assertThat(jdbc
            .queryForObject("SELECT SUM(amount) FROM gf_property_lease_bill WHERE lease_id=? AND kind='RENT'", BigDecimal.class, lease))
            .isEqualByComparingTo("4000.00");
        assertThatThrownBy(() -> lifecycle
            .generate(lease, 9, new PropertyLeaseLifecycleService.ScheduleInput(1, 3, true)))
            .hasMessageContaining("重复");
        assertThatThrownBy(() -> bill(lease, "DEPOSIT", BigDecimal.ONE)).isInstanceOf(Exception.class);
    }

    @Test
    void settlementUsesCollectedDepositAndRequiresHandoverAndBothConfirmations() {
        long lease = create();
        signed(lease);
        assertThatThrownBy(() -> document(lease, "SETTLEMENT", List.of())).hasMessageContaining("退租交接");
        long deposit = bill(lease, "DEPOSIT", new BigDecimal("2000.00"));
        long checkout = document(lease, "CHECK_OUT", List.of());
        confirm(lease, checkout);
        assertThatThrownBy(() -> document(lease, "SETTLEMENT", List.of())).hasMessageContaining("未结账单");
        paid(lease, deposit);
        assertThatThrownBy(() -> document(lease, "SETTLEMENT", List
            .of(new PropertyLeaseLifecycleService.Deduction("损坏", new BigDecimal("2001.00")))))
            .hasMessageContaining("实收押金");
        long settlement = document(lease, "SETTLEMENT", List
            .of(new PropertyLeaseLifecycleService.Deduction("钥匙遗失", new BigDecimal("20.00"))));
        assertThatThrownBy(() -> bill(lease, "OTHER", BigDecimal.ONE)).hasMessageContaining("结算单");
        assertThatThrownBy(() -> lifecycle
            .documentAction(lease, settlement, 9, true, new PropertyLeaseLifecycleService.DocumentAction("CONFIRM", 0, "x", null, null, null)))
            .hasMessageContaining("本人");
        confirm(lease, settlement);
        assertThat(jdbc
            .queryForObject("SELECT status FROM gf_property_lease_document WHERE id=?", String.class, settlement))
            .isEqualTo("REFUND_PENDING");
        lifecycle
            .documentAction(lease, settlement, 2, false, new PropertyLeaseLifecycleService.DocumentAction("REFUND", 2, null, null, "refund-1980", LocalDate
                .now()
                .toString()));
        assertThatThrownBy(() -> lifecycle
            .documentAction(lease, settlement, 2, false, new PropertyLeaseLifecycleService.DocumentAction("RECEIVED", 3, null, null, null, null)))
            .hasMessageContaining("承租人");
        lifecycle
            .documentAction(lease, settlement, 1, false, new PropertyLeaseLifecycleService.DocumentAction("RECEIVED", 3, null, null, null, null));
        assertThat(jdbc
            .queryForObject("SELECT status FROM gf_property_lease_document WHERE id=?", String.class, settlement))
            .isEqualTo("CLOSED");
    }

    @Test
    void hashMismatchAndDisputesPreventConfirmationAndNotificationsRollbackMutations() {
        long lease = create();
        signed(lease);
        long doc = document(lease, "CHECK_IN", List.of());
        assertThatThrownBy(() -> lifecycle
            .documentAction(lease, doc, 1, false, new PropertyLeaseLifecycleService.DocumentAction("CONFIRM", 0, "wrong", null, null, null)))
            .hasMessageContaining("内容");
        lifecycle
            .documentAction(lease, doc, 1, false, new PropertyLeaseLifecycleService.DocumentAction("DISPUTE", 0, null, "清单不符", null, null));
        assertThatThrownBy(() -> confirm(lease, doc)).isInstanceOf(Exception.class);
        lifecycle
            .documentAction(lease, doc, 9, true, new PropertyLeaseLifecycleService.DocumentAction("VOID", 1, null, "重新核实", null, null));
        reset(notices);
        doThrow(new IllegalStateException("notification failed")).when(notices)
            .send(anyLong(), anyString(), anyLong(), anyString(), anyString());
        assertThatThrownBy(() -> bill(lease, "OTHER", BigDecimal.ONE)).hasMessageContaining("notification failed");
        assertThat(jdbc
            .queryForObject("SELECT COUNT(*) FROM gf_property_lease_bill WHERE lease_id=?", Long.class, lease))
            .isZero();
    }

    @Test
    void finalPartialMonthUsesActualDaysAndDuplicateOtherBillsAreRejected() {
        var start = LocalDate.now().plusYears(1).withMonth(4).withDayOfMonth(1);
        long lease = Long.parseLong(service.create(draft(start, start.plusMonths(1).plusDays(14)), 9));
        signed(lease);
        lifecycle.generate(lease, 9, new PropertyLeaseLifecycleService.ScheduleInput(1, 0, false));
        assertThat(jdbc
            .queryForObject("SELECT SUM(amount) FROM gf_property_lease_bill WHERE lease_id=?", BigDecimal.class, lease))
            .isEqualByComparingTo("2967.74");
        var input = new PropertyLeaseLifecycleService.BillInput("OTHER", "水费", start.toString(), start.toString(), start
            .toString(), new BigDecimal("20.00"));
        lifecycle.createBill(lease, 9, input);
        assertThatThrownBy(() -> lifecycle.createBill(lease, 9, input)).hasMessageContaining("重复提交");
    }

    @Test
    void zeroRefundStillNeedsBothPartiesAndReopeningRequiresClosureAndNoOtherLease() {
        jdbc.execute("CREATE TABLE gf_property_operation(property_id BIGINT PRIMARY KEY,availability VARCHAR(20),reason VARCHAR(500),reopen_date DATE,version BIGINT DEFAULT 0,update_time TIMESTAMP)");
        jdbc.update("INSERT INTO gf_property_operation(property_id,availability) VALUES(100,'RENTED')");
        long lease = create();
        signed(lease);
        long checkout = document(lease, "CHECK_OUT", List.of());
        confirm(lease, checkout);
        long settlement = document(lease, "SETTLEMENT", List.of());
        service.action(lease, 9, true, action("TERMINATE", version(lease), "提前交接"));
        assertThatThrownBy(() -> lifecycle.reopen(lease, 9)).hasMessageContaining("到账确认");
        confirm(lease, settlement);
        assertThat(jdbc
            .queryForObject("SELECT status FROM gf_property_lease_document WHERE id=?", String.class, settlement))
            .isEqualTo("CLOSED");
        lifecycle.reopen(lease, 9);
        assertThat(jdbc
            .queryForObject("SELECT availability FROM gf_property_operation WHERE property_id=100", String.class))
            .isEqualTo("ACTIVE");
        long next = create();
        signed(next);
        assertThatThrownBy(() -> lifecycle.reopen(lease, 9)).hasMessageContaining("其他已签租约");
    }

    @Test
    void privatePhotosRequireParticipantAccessAndCannotBeAttachedToAnotherLease() throws Exception {
        long first = create();
        signed(first);
        var evidence = new PropertyLeaseEvidenceService(store, service);
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        byte[] bytes = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6yFkAAAAASUVORK5CYII=");
        when(file.getSize()).thenReturn((long)bytes.length);
        when(file.getBytes()).thenReturn(bytes);
        long photo = Long.parseLong(evidence.upload(first, 1, false, file).get("id"));
        assertThat(evidence.read(first, photo, 2, false).get("mimeType")).isEqualTo("image/png");
        assertThatThrownBy(() -> evidence.read(first, photo, 3, false)).hasMessageContaining("无权");
        long second = Long.parseLong(service.create(draft(LocalDate.now().plusDays(31), LocalDate.now()
            .plusDays(60)), 9));
        signed(second);
        var start = LocalDate.now().plusDays(31).toString();
        lifecycle
            .createBill(second, 9, new PropertyLeaseLifecycleService.BillInput("RENT", "租金", start, start, start, new BigDecimal("100.00")));
        long billId = jdbc.queryForObject("SELECT MAX(id) FROM gf_property_lease_bill", Long.class);
        assertThatThrownBy(() -> lifecycle
            .billAction(second, billId, 1, false, new PropertyLeaseLifecycleService.BillAction("SUBMIT", 0, "已转账", LocalDate
                .now()
                .toString(), null, photo))).hasMessageContaining("附件");
        when(file.getSize()).thenReturn(3L * 1024 * 1024);
        assertThatThrownBy(() -> evidence.upload(first, 1, false, file)).hasMessageContaining("2MB");
    }
}
