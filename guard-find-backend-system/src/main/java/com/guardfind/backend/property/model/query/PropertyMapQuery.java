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

package com.guardfind.backend.property.model.query;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class PropertyMapQuery {
    @Size(max = 50)
    private String city;
    @Size(max = 50)
    private String district;
    @Size(max = 100)
    private String community;
    @Size(max = 64)
    private String keyword;
    @Pattern(regexp = "^(rent|sale)$")
    private String priceType;
    @Pattern(regexp = "^(month|day)$")
    private String priceUnit;
    @Min(0)
    @Max(9999999999L)
    private Long minPrice;
    @Min(0)
    @Max(9999999999L)
    private Long maxPrice;
    private Double south;
    private Double north;
    private Double west;
    private Double east;

    @AssertTrue(message = "地图范围或价格区间无效")
    public boolean isValid() {
        if (minPrice != null && maxPrice != null && minPrice > maxPrice)
            return false;
        if (south == null && north == null && west == null && east == null)
            return true;
        return south != null && north != null && west != null && east != null && Double.isFinite(south) && Double
            .isFinite(north) && Double.isFinite(west) && Double
                .isFinite(east) && south >= -90 && north <= 90 && south < north && west >= -180 && east <= 180 && west < east;
    }
}
