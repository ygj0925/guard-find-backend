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
import lombok.Data;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 房源预约看房实体
 */
@Data
@TableName("gf_property_booking")
public class PropertyBookingDO {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long propertyId;
    /** 预约人用户 ID */
    private Long userId;
    /** 房东用户 ID */
    private Long ownerId;
    private LocalDateTime bookingTime;
    private String contactName;
    private String contactPhone;
    private String remark;
    /** 状态(1:待确认;2:已确认;3:已拒绝;4:已取消) */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
