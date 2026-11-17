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

package com.guardfind.backend.property.operations;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OperationsSettings {
    @Size(max = 100)
    private String operator = "";
    @Size(max = 100)
    private String contact = "";
    @Size(max = 40)
    private String policyVersion = "";
    @Size(max = 10)
    private String effectiveDate = "";
    @Size(max = 30000)
    private String userAgreement = "";
    @Size(max = 30000)
    private String privacyPolicy = "";
    private boolean policiesPublished;
    private boolean subscriptionEnabled;
    @Size(max = 100)
    private String templateId = "";
    @Size(max = 30)
    private String titleField = "thing1";
    @Size(max = 30)
    private String timeField = "time2";
    @Size(max = 30)
    private String statusField = "phrase3";
    private int reminderHours = 24;
    private int expiryHours = 24;
}
