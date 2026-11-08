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

package com.guardfind.backend.oa.leave;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 按工作日历折算请假天数：未登记日期周一至周五上班，登记的 WORKDAY/HOLIDAY 覆盖默认；
 * 开始选下午、结束选上午各扣半天（仅当该日为工作日）。
 */
public final class OaLeaveDayCalculator {

    public static final String AM = "AM";
    public static final String PM = "PM";
    public static final String WORKDAY = "WORKDAY";
    public static final String HOLIDAY = "HOLIDAY";
    private static final long MAX_SPAN_DAYS = 366;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private OaLeaveDayCalculator() {
    }

    public static void requireRange(LocalDate start, String startHalf, LocalDate end, String endHalf) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("请选择请假开始和结束日期");
        }
        if (!AM.equals(startHalf) && !PM.equals(startHalf) || !AM.equals(endHalf) && !PM.equals(endHalf)) {
            throw new IllegalArgumentException("请假时段只能是上午或下午");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("结束日期不能早于开始日期");
        }
        if (start.equals(end) && PM.equals(startHalf) && AM.equals(endHalf)) {
            throw new IllegalArgumentException("结束时段不能早于开始时段");
        }
        if (start.getYear() != end.getYear()) {
            throw new IllegalArgumentException("跨年度请假请拆分为两张申请");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("单次请假跨度不能超过 366 天");
        }
    }

    public static BigDecimal days(LocalDate start,
                                  String startHalf,
                                  LocalDate end,
                                  String endHalf,
                                  Map<LocalDate, String> overrides) {
        requireRange(start, startHalf, end, endHalf);
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (isWorkday(date, overrides)) {
                total = total.add(BigDecimal.ONE);
            }
        }
        if (PM.equals(startHalf) && isWorkday(start, overrides)) {
            total = total.subtract(HALF);
        }
        if (AM.equals(endHalf) && isWorkday(end, overrides)) {
            total = total.subtract(HALF);
        }
        return total.setScale(1);
    }

    public static boolean isWorkday(LocalDate date, Map<LocalDate, String> overrides) {
        String override = overrides == null ? null : overrides.get(date);
        if (override != null) {
            return WORKDAY.equals(override);
        }
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
