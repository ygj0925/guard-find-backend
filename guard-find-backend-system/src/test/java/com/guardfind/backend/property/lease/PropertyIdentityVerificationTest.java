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

package com.guardfind.backend.property.lease;

import com.guardfind.backend.property.operations.OperationsStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PropertyIdentityVerificationTest {
    @Test
    void disabledModeNeverClaimsVerified() {
        OperationsStore db = mock(OperationsStore.class);
        when(db.one(any(String.class), any(Object[].class))).thenReturn(config("DISABLED", null, false));
        when(db.rows(any(String.class), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> result = new PropertyIdentityVerificationService(db).status(7L);

        assertThat(result).containsEntry("enabled", false)
            .containsEntry("required", false)
            .containsEntry("status", "NOT_REQUIRED")
            .containsEntry("verified", false);
    }

    @Test
    void requiredVerificationRejectsPendingUsersAtSigningGate() {
        OperationsStore db = mock(OperationsStore.class);
        when(db.one(any(String.class), any(Object[].class))).thenReturn(config("MANUAL", "MANUAL", true))
            .thenReturn(Map.of("id", "11", "status", "PENDING", "expiresAt", ""));

        var service = new PropertyIdentityVerificationService(db);

        assertThatThrownBy(() -> service.requireVerified(7L, 11L)).hasMessageContaining("完成身份核验");
    }

    @Test
    void expiredVerificationIsNotReportedAsVerified() {
        OperationsStore db = mock(OperationsStore.class);
        when(db.one(any(String.class), any(Object[].class))).thenReturn(config("MANUAL", "MANUAL", true));
        when(db.rows(any(String.class), any(Object[].class))).thenReturn(List.of(Map
            .of("id", "11", "status", "VERIFIED", "expiresAt", LocalDateTime.now().minusMinutes(1).toString())));

        Map<String, Object> result = new PropertyIdentityVerificationService(db).status(7L);

        assertThat(result).containsEntry("status", "EXPIRED").containsEntry("verified", false);
    }

    private static Map<String, Object> config(String mode, String provider, boolean required) {
        return Map.of("mode", mode, "provider", provider == null
            ? ""
            : provider, "requiredForSigning", required, "verificationTtlDays", 365, "consentVersion", "identity-v1");
    }
}
