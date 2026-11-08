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

import com.guardfind.backend.oa.leave.OaLeaveDayCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 11.1 请假天数折算：周末、节假日/调休覆盖与半天规则。
 */
class OaLeaveDayCalculatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 3, 2);

    @Test
    void countsWorkdaysAndSkipsWeekends() {
        assertThat(days(MON, "AM", MON.plusDays(4), "PM", Map.of())).isEqualByComparingTo("5");
        assertThat(days(MON, "AM", MON.plusDays(6), "PM", Map.of())).isEqualByComparingTo("5");
        assertThat(days(MON.plusDays(5), "AM", MON.plusDays(6), "PM", Map.of())).isEqualByComparingTo("0");
    }

    @Test
    void appliesHalfDays() {
        assertThat(days(MON, "AM", MON, "AM", Map.of())).isEqualByComparingTo("0.5");
        assertThat(days(MON, "PM", MON, "PM", Map.of())).isEqualByComparingTo("0.5");
        assertThat(days(MON, "PM", MON.plusDays(1), "AM", Map.of())).isEqualByComparingTo("1");
        assertThat(days(MON.minusDays(1), "PM", MON, "AM", Map.of())).isEqualByComparingTo("0.5");
    }

    @Test
    void calendarOverridesDefaultWeek() {
        Map<LocalDate, String> overrides = Map.of(MON.plusDays(1), OaLeaveDayCalculator.HOLIDAY, MON
            .plusDays(5), OaLeaveDayCalculator.WORKDAY);
        assertThat(days(MON, "AM", MON.plusDays(6), "PM", overrides)).isEqualByComparingTo("5");
        assertThat(days(MON.plusDays(1), "PM", MON.plusDays(1), "PM", overrides)).isEqualByComparingTo("0");
        assertThat(OaLeaveDayCalculator.isWorkday(MON.plusDays(5), overrides)).isTrue();
    }

    @Test
    void rejectsInvalidRanges() {
        assertThatThrownBy(() -> days(MON, "AM", MON.minusDays(1), "PM", Map.of())).hasMessageContaining("不能早于开始日期");
        assertThatThrownBy(() -> days(MON, "PM", MON, "AM", Map.of())).hasMessageContaining("结束时段");
        assertThatThrownBy(() -> days(LocalDate.of(2026, 12, 31), "AM", LocalDate.of(2027, 1, 4), "PM", Map.of()))
            .hasMessageContaining("跨年度");
        assertThatThrownBy(() -> days(MON, "XX", MON, "PM", Map.of())).hasMessageContaining("上午或下午");
    }

    private static BigDecimal days(LocalDate start,
                                   String startHalf,
                                   LocalDate end,
                                   String endHalf,
                                   Map<LocalDate, String> overrides) {
        return OaLeaveDayCalculator.days(start, startHalf, end, endHalf, overrides);
    }
}
