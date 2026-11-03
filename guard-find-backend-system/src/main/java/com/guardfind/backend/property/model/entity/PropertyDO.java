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

package com.guardfind.backend.property.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.guardfind.backend.common.base.model.entity.BaseDO;
import lombok.Data;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 房产房源实体
 *
 * @author guardfind
 * @since 2026/10/29
 */
@Data
@TableName("gf_property")
public class PropertyDO extends BaseDO {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String title;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private String propertyType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String layout;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal areaSqm;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String floorInfo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String orientation;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String decoration;
    private BigDecimal price;
    private String priceType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String community;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private java.time.LocalDate availableFrom;
    /** 租赁方式(whole=整租/shared=合租;仅出租) */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String rentalMode;
    private String address;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String district;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String city;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String facilities;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contactName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String contactPhone;
    private Integer status;
    private Integer viewCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String images;
}
