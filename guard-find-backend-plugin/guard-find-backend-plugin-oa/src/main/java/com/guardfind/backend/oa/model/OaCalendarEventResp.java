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

import java.time.LocalDateTime;
import java.util.List;

public record OaCalendarEventResp(long id, long ownerId, String ownerName, String scope, Long deptId, String deptName,
                                  String title, String location, String description, LocalDateTime startTime,
                                  LocalDateTime endTime, boolean allDay, Integer remindMinutes, LocalDateTime remindAt,
                                  String status, int version, LocalDateTime updateTime,
                                  List<OaCalendarMemberResp> members) {

    public OaCalendarEventResp withMembers(List<OaCalendarMemberResp> list) {
        return new OaCalendarEventResp(id, ownerId, ownerName, scope, deptId, deptName, title, location, description, startTime, endTime, allDay, remindMinutes, remindAt, status, version, updateTime, list);
    }
}
