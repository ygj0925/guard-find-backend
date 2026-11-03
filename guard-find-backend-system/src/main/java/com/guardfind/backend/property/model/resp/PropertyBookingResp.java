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

/**
 * 预约看房响应(预约人与房东两个视角共用)
 */
@Data
@Schema(description = "预约看房响应")
public class PropertyBookingResp implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long propertyId;
    private String propertyTitle;
    private String propertyImage;
    private BigDecimal propertyPrice;
    private String propertyPriceType;
    /** 预约人(guest 视角即自己;owner 视角为看房人) */
    private Long userId;
    private String userNickname;
    private String userAvatar;
    private Long ownerId;
    private LocalDateTime bookingTime;
    private String contactName;
    private String contactPhone;
    private String remark;
    /** 1 待确认;2 已确认;3 已拒绝;4 已取消 */
    private Integer status;
    private LocalDateTime createTime;
}
