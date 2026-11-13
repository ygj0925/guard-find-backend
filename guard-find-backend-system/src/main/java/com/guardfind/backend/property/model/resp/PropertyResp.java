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

package com.guardfind.backend.property.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "房源响应")
public class PropertyResp implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private String ownerNickname;
    private String ownerAvatar;
    /** 房东是否认证管家。 */
    private Boolean ownerIsManager;
    private String title;
    private String description;
    private String propertyType;
    private String layout;
    private BigDecimal areaSqm;
    private String floorInfo;
    private String orientation;
    private String decoration;
    private BigDecimal price;
    private String priceType;
    private String community;
    /** 可入住日期 */
    private java.time.LocalDate availableFrom;
    /** 租赁方式(whole/shared/room/short;仅出租) */
    private String rentalMode;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    private String priceUnit;
    private boolean recommended;
    private String address;
    private String district;
    private String city;
    private List<String> facilities;
    private String contactName;
    private String contactPhone;
    private Integer status;
    private Integer viewCount;
    /** 当前登录用户是否已收藏;未登录为 null */
    private Boolean isFavorited;
    private List<String> images;
    private LocalDateTime createTime;
}
