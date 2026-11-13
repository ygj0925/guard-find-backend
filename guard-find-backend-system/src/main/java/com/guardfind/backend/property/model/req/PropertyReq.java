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

package com.guardfind.backend.property.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "房源请求")
public class PropertyReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "房源标题")
    @NotBlank(message = "标题不能为空")
    @Length(max = 100, message = "标题不能超过 {max} 个字符")
    private String title;

    @Schema(description = "房源描述")
    @Length(max = 2000, message = "描述不能超过 {max} 个字符")
    private String description;

    @Schema(description = "类型(house/apartment/villa/shop)")
    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "^(house|apartment|villa|shop)$", message = "类型取值不合法")
    private String propertyType;

    @Schema(description = "户型", example = "2室1厅1卫")
    @Length(max = 30, message = "户型不能超过 {max} 个字符")
    private String layout;

    @Schema(description = "面积(㎡)")
    @DecimalMin(value = "1", message = "面积必须大于 0")
    @DecimalMax("999999.99")
    @Digits(integer = 6, fraction = 2)
    private BigDecimal areaSqm;

    @Schema(description = "楼层信息", example = "5/18")
    @Length(max = 20, message = "楼层信息不能超过 {max} 个字符")
    private String floorInfo;

    @Schema(description = "朝向", example = "南")
    @Length(max = 20, message = "朝向不能超过 {max} 个字符")
    private String orientation;

    @Schema(description = "装修", example = "精装")
    @Length(max = 20, message = "装修不能超过 {max} 个字符")
    private String decoration;

    @Schema(description = "价格(元)")
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "1", message = "价格必须大于 0")
    @DecimalMax("9999999999.99")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal price;

    @Schema(description = "价格类型(rent=租金/sale=售价)")
    @NotBlank(message = "价格类型不能为空")
    @Pattern(regexp = "^(rent|sale)$", message = "价格类型取值不合法")
    private String priceType;

    @Length(max = 100)
    private String community;
    private java.time.LocalDate availableFrom;
    @Pattern(regexp = "^(whole|shared|room|short)$", message = "租赁方式取值不合法")
    private String rentalMode;
    @Pattern(regexp = "^(month|day)$", message = "价格单位取值不合法")
    private String priceUnit;

    @AssertTrue(message = "价格单位与租售类型或租赁方式不匹配")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    public boolean isPriceUnitValid() {
        return priceUnit == null || "rent".equals(priceType)
            && ("short".equals(rentalMode) ? "day" : "month").equals(priceUnit);
    }

    @NotBlank(message = "地址不能为空")
    @Length(max = 200)
    private String address;

    @Schema(description = "区域")
    @Length(max = 50, message = "区域不能超过 {max} 个字符")
    private String district;

    @Schema(description = "城市")
    @Length(max = 50, message = "城市不能超过 {max} 个字符")
    private String city;

    @Schema(description = "设施列表")
    @Size(max = 20)
    private List<@NotBlank @Size(max = 20) String> facilities;

    @Schema(description = "联系人")
    @Length(max = 50, message = "联系人不能超过 {max} 个字符")
    private String contactName;

    @Schema(description = "联系电话")
    @Length(max = 20, message = "联系电话不能超过 {max} 个字符")
    private String contactPhone;

    @Schema(description = "图片 URL 列表(最多 9 张)")
    @Size(max = 9)
    private List<@NotBlank @Pattern(regexp = "^https?://[^\\s]+$", message = "图片必须为 HTTP(S) 地址") @Size(max = 1000) String> images;

    @Schema(description = "状态(1 草稿 / 2 已上架 / 3 已下架)", example = "1")
    @NotNull(message = "状态不能为空")
    @Min(1)
    @Max(3)
    private Integer status;
}
