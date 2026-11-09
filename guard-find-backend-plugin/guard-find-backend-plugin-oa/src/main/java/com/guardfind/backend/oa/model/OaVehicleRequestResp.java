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

package com.guardfind.backend.oa.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OaVehicleRequestResp(long id, long applicationId, String title, String applicationStatus,
                                   long applicantId, String applicantName, String purpose, String destination,
                                   int passengers, boolean needDriver, LocalDateTime startTime, LocalDateTime endTime,
                                   String status, Long vehicleId, String plateNo, String vehicleModel, Long driverId,
                                   String driverName, String driverPhone, String dispatchByName,
                                   LocalDateTime dispatchTime, String dispatchRemark, LocalDateTime departTime,
                                   Integer startMileage, LocalDateTime returnTime, Integer endMileage,
                                   BigDecimal fuelCost, BigDecimal tollCost, BigDecimal otherCost, String returnRemark,
                                   String cancelReason, LocalDateTime cancelTime, LocalDateTime createTime) {
}
