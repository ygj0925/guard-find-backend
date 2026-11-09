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
import java.time.LocalDate;
import java.time.LocalDateTime;

public record OaContractResp(long id, String contractNo, String title, String counterparty, String contractType,
                             BigDecimal amount, LocalDate startDate, LocalDate endDate, long ownerId, String ownerName,
                             int currentVersion, long currentApplicationId, String applicationStatus, String status,
                             LocalDate signedDate, String signatories, String signRemark, Long signedBy,
                             String signedByName, LocalDateTime signedTime, String archiveNo, String archiveLocation,
                             Long archivedBy, String archivedByName, LocalDateTime archivedTime,
                             LocalDateTime createTime, LocalDateTime updateTime) {
}
