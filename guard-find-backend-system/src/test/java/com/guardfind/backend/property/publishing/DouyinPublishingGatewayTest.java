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

package com.guardfind.backend.property.publishing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DouyinPublishingGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private PublishingProperties config;
    private PublishingProperties.Credential credential;
    private DouyinPublishingGateway gateway;
    private static final String IMAGE = "https://images.example.com/property.png";

    @BeforeEach
    void setup() {
        config = new PublishingProperties();
        config.setEnabled(true);
        config.setMediaHosts(Set.of("images.example.com"));
        credential = new PublishingProperties.Credential();
        credential.setOpenId("open-id");
        credential.setAccessToken("private-test-token");
        credential.setExpiresAt(Instant.now().plusSeconds(3600).toString());
        credential.setHousingApproved(true);
        credential.setScopeConfirmed(true);
        config.getDouyinAccounts().put("account1", credential);
        gateway = spy(new DouyinPublishingGateway(config, json));
    }

    @Test
    void requiresServerGateApprovedScopeHousingAndUnexpiredAuthorization() {
        assertThat(gateway.ready("account1")).isTrue();
        assertThat(gateway.ready("unknown")).isFalse();
        credential.setHousingApproved(false);
        assertThat(gateway.publish("account1", "标题", "正文", List.of(IMAGE)).status()).isEqualTo("MANUAL_REQUIRED");
        credential.setHousingApproved(true);
        credential.setScopeConfirmed(false);
        assertThat(gateway.ready("account1")).isFalse();
        credential.setScopeConfirmed(true);
        credential.setExpiresAt(Instant.now().plusSeconds(30).toString());
        assertThat(gateway.ready("account1")).isFalse();
        credential.setExpiresAt("invalid");
        assertThat(gateway.ready("account1")).isFalse();
        credential.setExpiresAt(Instant.now().plusSeconds(3600).toString());
        config.setEnabled(false);
        assertThat(gateway.ready("account1")).isFalse();
        assertThat(credential.toString()).doesNotContain("private-test-token");
        assertThat(config.toString()).doesNotContain("private-test-token");
    }

    @Test
    void invalidPayloadNeverTouchesNetwork() throws Exception {
        assertThat(gateway.publish("account1", "标题", "正文", List.of()).status()).isEqualTo("FAILED");
        assertThat(gateway.publish("account1", "标题", "正文", Collections.nCopies(31, IMAGE)).status())
            .isEqualTo("FAILED");
        assertThat(gateway.publish("account1", "标题", "字".repeat(1000), List.of(IMAGE)).status()).isEqualTo("FAILED");
        assertThat(gateway.publish("account1", "标题", "正文", Arrays.asList((String)null)).status()).isEqualTo("FAILED");
        verify(gateway, never()).downloadImage(any());
        verify(gateway, never()).post(any(), any(), any(), any());
    }

    @Test
    void uploadsOfficialImageFieldThenCreatesUsingReturnedIdsAndReportsModeration() throws Exception {
        doReturn(new byte[] {(byte)137, 80, 78, 71, 13, 10, 26, 10, 0}).when(gateway).downloadImage(IMAGE);
        doAnswer(call -> {
            assertThat(call.getArgument(2, String.class)).startsWith("multipart/form-data; boundary=");
            assertThat(new String(call.getArgument(3, byte[].class), StandardCharsets.UTF_8))
                .contains("name=\"image\"", "filename=\"property.png\"", "Content-Type: image/png");
            return json.readTree("{\"data\":{\"error_code\":0,\"image\":{\"image_id\":\"official-image-id\"}}}");
        }).when(gateway).post(eq("upload_image"), same(credential), anyString(), any());
        doAnswer(call -> {
            var payload = json.readTree(call.getArgument(3, byte[].class));
            assertThat(payload.path("text").asText()).isEqualTo("标题\n正文");
            assertThat(payload.path("image_list").get(0).asText()).isEqualTo("official-image-id");
            return json.readTree("{\"data\":{\"error_code\":0,\"item_id\":\"item-123\"},\"extra\":{\"error_code\":0}}");
        }).when(gateway).post(eq("create_image_text"), same(credential), eq("application/json"), any());
        var result = gateway.publish("account1", "标题", "正文", List.of(IMAGE));
        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.itemId()).isEqualTo("item-123");
        verify(gateway, times(1)).post(eq("create_image_text"), any(), any(), any());
    }

    private void uploaded() throws Exception {
        doReturn(new byte[] {(byte)255, (byte)216, (byte)255, 0}).when(gateway).downloadImage(IMAGE);
        doReturn(json.readTree("{\"data\":{\"error_code\":0,\"image\":{\"image_id\":\"image-id\"}}}")).when(gateway)
            .post(eq("upload_image"), any(), any(), any());
    }

    @Test
    void submissionTimeoutIsUnknownAndNeverRetried() throws Exception {
        uploaded();
        doThrow(new IOException("sensitive token must never leak")).when(gateway)
            .post(eq("create_image_text"), any(), any(), any());
        var result = gateway.publish("account1", "标题", "正文", List.of(IMAGE));
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.message()).doesNotContain("sensitive");
        verify(gateway, times(1)).post(eq("create_image_text"), any(), any(), any());
    }

    @Test
    void malformedOrMissingItemResponsesCannotBecomePublishedOrRetryable() throws Exception {
        uploaded();
        doReturn(json.readTree("{}"), json.readTree("{\"data\":{\"error_code\":0}}")).when(gateway)
            .post(eq("create_image_text"), any(), any(), any());
        assertThat(gateway.publish("account1", "标题", "正文", List.of(IMAGE)).status()).isEqualTo("UNKNOWN");
        assertThat(gateway.publish("account1", "标题", "正文", List.of(IMAGE)).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void uploadFailureIsRetryableWithoutCreatingPost() throws Exception {
        doThrow(new IOException("media failed")).when(gateway).downloadImage(IMAGE);
        assertThat(gateway.publish("account1", "标题", "正文", List.of(IMAGE)).status()).isEqualTo("FAILED");
        verify(gateway, never()).post(eq("create_image_text"), any(), any(), any());
    }

    @Test
    void changedPropertyAfterUploadNeverCreatesPost() throws Exception {
        uploaded();
        var result = gateway.publish("account1", "标题", "正文", List.of(IMAGE), () -> false);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("房源或任务已变化");
        verify(gateway).post(eq("upload_image"), any(), any(), any());
        verify(gateway, never()).post(eq("create_image_text"), any(), any(), any());
    }

    @Test
    void explicitPlatformRejectionIsRetryableWithoutLeakingResponseText() throws Exception {
        uploaded();
        doReturn(json.readTree("{\"data\":{\"error_code\":28001008,\"description\":\"secret diagnostic\"}}"))
            .when(gateway)
            .post(eq("create_image_text"), any(), any(), any());
        var result = gateway.publish("account1", "标题", "正文", List.of(IMAGE));
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.message()).contains("28001008").doesNotContain("secret diagnostic");
    }

    @Test
    void officialTransportUsesEncodedOpenIdAndTokenHeader() throws Exception {
        var connection = mock(HttpsURLConnection.class);
        var output = new ByteArrayOutputStream();
        credential.setOpenId("id+/=");
        when(connection.getOutputStream()).thenReturn(output);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream("{\"data\":{\"error_code\":0}}"
            .getBytes(StandardCharsets.UTF_8)));
        doReturn(connection).when(gateway).connection(any());
        byte[] payload = "{\"text\":\"标题\"}".getBytes(StandardCharsets.UTF_8);
        gateway.post("create_image_text", credential, "application/json", payload);
        verify(gateway).connection(URI
            .create("https://open.douyin.com/api/douyin/v1/video/create_image_text/?open_id=id%2B%2F%3D"));
        verify(connection).setRequestMethod("POST");
        verify(connection).setRequestProperty("access-token", "private-test-token");
        verify(connection).setRequestProperty("Content-Type", "application/json");
        verify(connection).disconnect();
        assertThat(output.toByteArray()).isEqualTo(payload);
    }

    @Test
    void mediaRejectsLocalNetworkUnapprovedHostsCredentialsPortsAndSchemes() throws Exception {
        config.setMediaHosts(Set.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "images.example.com"));
        for (String address : List
            .of("http://images.example.com/a.jpg", "file:///a.jpg", "https://evil.example/a.jpg", "https://user:pass@images.example.com/a.jpg", "https://images.example.com:444/a.jpg", "https://127.0.0.1/a.jpg", "https://10.0.0.1/a.jpg", "https://169.254.169.254/a.jpg", "https://100.64.0.1/a.jpg"))
            assertThatThrownBy(() -> gateway.downloadImage(address)).isInstanceOf(IOException.class);
        verify(gateway, never()).connection(any());
    }

    @Test
    void mediaRejectsRedirectNonImagesAndOversizedPayloads() throws Exception {
        config.setMediaHosts(Set.of("1.1.1.1"));
        var connection = mock(HttpsURLConnection.class);
        doReturn(connection).when(gateway).connection(any());
        when(connection.getResponseCode()).thenReturn(302);
        assertThatThrownBy(() -> gateway.downloadImage("https://1.1.1.1/a.jpg")).isInstanceOf(IOException.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getContentLengthLong()).thenReturn(20L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> gateway.downloadImage("https://1.1.1.1/a.jpg")).isInstanceOf(IOException.class);
        when(connection.getContentLengthLong()).thenReturn(-1L);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream("not a picture"
            .getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> gateway.downloadImage("https://1.1.1.1/a.jpg")).isInstanceOf(IOException.class);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {(byte)255, (byte)216,
            (byte)255, 0}));
        assertThat(gateway.downloadImage("https://1.1.1.1/a.jpg")).hasSize(4);
    }
}
