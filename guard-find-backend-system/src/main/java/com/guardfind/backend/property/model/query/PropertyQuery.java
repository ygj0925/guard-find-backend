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

import cn.hutool.core.util.StrUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 公开房源列表查询,仅检索已上架房源。
 */
@Data
@Schema(description = "公开房源列表查询")
public class PropertyQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "城市")
    @Size(max = 50, message = "城市不能超过 {max} 个字符")
    private String city;

    @Schema(description = "类型(house/apartment/villa/shop)")
    @Pattern(regexp = "^(house|apartment|villa|shop)?$", message = "类型取值不合法")
    private String propertyType;

    @Schema(description = "价格类型(rent/sale)")
    @Pattern(regexp = "^(rent|sale)?$", message = "价格类型取值不合法")
    private String priceType;

    @Schema(description = "租赁方式(whole/shared/room/short)")
    @Pattern(regexp = "^(whole|shared|room|short)?$", message = "租赁方式取值不合法")
    private String rentalMode;

    @Pattern(regexp = "^(month|day)$", message = "价格单位取值不合法")
    private String priceUnit;
    private Boolean recommended;
    @Pattern(regexp = "^[a-z0-9-]{1,40}$", message = "活动 ID 格式不合法")
    private String activityId;

    @Schema(description = "标题/小区/地址关键词")
    @Size(max = 64, message = "关键词不能超过 {max} 个字符")
    private String keyword;

    @Schema(description = "最低价格(元),含边界")
    @DecimalMin(value = "0", message = "最低价格不能为负数")
    @DecimalMax("9999999999.99")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal minPrice;

    @Schema(description = "最高价格(元),含边界")
    @DecimalMin(value = "0", message = "最高价格不能为负数")
    @DecimalMax("9999999999.99")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal maxPrice;

    @Schema(description = "排序(newest/priceAsc/priceDesc)", defaultValue = "newest")
    @NotBlank(message = "排序不能为空")
    @Pattern(regexp = "^(newest|priceAsc|priceDesc)$", message = "排序取值不合法")
    private String sort = "newest";

    public void setCity(String city) {
        this.city = StrUtil.trim(city);
    }

    public void setPropertyType(String propertyType) {
        this.propertyType = StrUtil.trim(propertyType);
    }

    public void setPriceType(String priceType) {
        this.priceType = StrUtil.trim(priceType);
    }

    public void setRentalMode(String rentalMode) {
        this.rentalMode = StrUtil.trim(rentalMode);
    }

    public void setKeyword(String keyword) {
        this.keyword = StrUtil.trim(keyword);
    }

    public void setSort(String sort) {
        this.sort = StrUtil.trim(sort);
    }

    @AssertTrue(message = "最低价格不能大于最高价格")
    @Schema(hidden = true)
    public boolean isPriceRangeValid() {
        return minPrice == null || maxPrice == null || minPrice.compareTo(maxPrice) <= 0;
    }
}
