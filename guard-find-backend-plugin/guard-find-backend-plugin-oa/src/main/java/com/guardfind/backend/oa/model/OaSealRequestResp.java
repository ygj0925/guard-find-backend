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

import java.time.LocalDate;
import java.time.LocalDateTime;

public record OaSealRequestResp(long id, long applicationId, String title, String applicationStatus, long applicantId,
                                String applicantName, long sealId, String sealCode, String sealName, String sealType,
                                long keeperId, String keeperName, Long contractId, String contractNo, int copies,
                                LocalDate useDate, String status, Integer usedCopies, LocalDate usedDate,
                                Long executorId, String executorName, LocalDateTime executedTime, String executeRemark,
                                LocalDateTime createTime) {
}
