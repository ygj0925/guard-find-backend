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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;

/** Official image upload and create_image_text APIs. No cookie or password login. */
@Component
@RequiredArgsConstructor
public class DouyinPublishingGateway implements PublishingGateway {
    private final PublishingProperties properties;
    private final ObjectMapper json;
    private static final int MAX_IMAGE = 20 * 1024 * 1024;
    private static final String API = "https://open.douyin.com/api/douyin/v1/video/";

    @Override
    public boolean ready(String key) {
        var c = properties.getDouyinAccounts().get(key);
        try {
            return properties.isEnabled() && !properties.getMediaHosts().isEmpty() && c != null && c
                .isHousingApproved() && c.isScopeConfirmed() && c.getOpenId() != null && !c.getOpenId().isBlank() && c
                    .getAccessToken() != null && !c.getAccessToken().isBlank() && Instant.parse(c.getExpiresAt())
                        .isAfter(Instant.now().plusSeconds(300));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Result publish(String key, String title, String body, List<String> images) {
        return publish(key, title, body, images, () -> true);
    }

    @Override
    public Result publish(String key, String title, String body, List<String> images, BooleanSupplier stillCurrent) {
        if (!ready(key))
            return new Result("MANUAL_REQUIRED", null, "抖音授权、场景准入或素材域名尚未配置，或授权已过期");
        if (title == null || title.isBlank() || body == null || body.isBlank() || title.length() + 1 + body
            .length() > 1000 || images == null || images.isEmpty() || images.size() > 30 || images.stream()
                .anyMatch(value -> value == null || value.isBlank()))
            return new Result("FAILED", null, "抖音图文需要标题、正文和 1～30 张图片，标题与正文合计最多 1000 字符");
        var c = properties.getDouyinAccounts().get(key);
        List<String> ids = new ArrayList<>();
        Instant mediaDeadline = Instant.now().plusSeconds(180);
        try {
            for (String url : images) {
                if (Instant.now().isAfter(mediaDeadline))
                    return new Result("FAILED", null, "素材准备超时，本次尚未提交发布，请减少图片或稍后重试");
                byte[] image = downloadImage(url);
                String boundary = "GuardFind" + UUID.randomUUID().toString().replace("-", "");
                var bytes = new ByteArrayOutputStream();
                boolean png = image.length > 0 && image[0] == (byte)137;
                bytes
                    .write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"property." + (png
                        ? "png"
                        : "jpg") + "\"\r\nContent-Type: " + (png ? "image/png" : "image/jpeg") + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                bytes.write(image);
                bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                JsonNode response = post("upload_image", c, "multipart/form-data; boundary=" + boundary, bytes
                    .toByteArray());
                if (!ok(response))
                    return new Result("FAILED", null, "抖音素材上传被拒绝，错误码 " + errorCode(response));
                String imageId = response.path("data").path("image").path("image_id").asText();
                if (imageId.isBlank())
                    return new Result("FAILED", null, "抖音素材上传未返回图片标识");
                ids.add(imageId);
            }
        } catch (Exception e) {
            // Nothing has been submitted for publication; explicit retry is safe.
            return new Result("FAILED", null, "素材读取或上传失败，请检查公开图片地址、域名配置和授权后重试");
        }
        try {
            if (!stillCurrent.getAsBoolean())
                return new Result("FAILED", null, "房源或任务已变化，本次尚未提交发布，请重新审核");
            if (!ready(key))
                return new Result("MANUAL_REQUIRED", null, "授权或发布配置已变化，本次尚未提交发布");
        } catch (Exception e) {
            return new Result("FAILED", null, "发布前状态核验失败，本次尚未提交发布");
        }
        try {
            JsonNode response = post("create_image_text", c, "application/json", json.writeValueAsBytes(Map
                .of("text", title + "\n" + body, "image_list", ids)));
            Integer code = response == null ? null : responseCode(response.path("data").path("error_code"));
            if (code == null || (code == 0 && response.path("extra").hasNonNull("error_code") && !Integer.valueOf(0)
                .equals(responseCode(response.path("extra").path("error_code")))))
                return new Result("UNKNOWN", null, "平台未返回明确结果，请到创作者中心核对，勿重复提交");
            if (code != 0)
                return new Result("FAILED", null, "抖音拒绝本次提交，错误码 " + errorCode(response));
            String item = response.path("data").path("item_id").asText();
            if (item.isBlank() || item.length() > 500)
                return new Result("UNKNOWN", null, "平台响应缺少作品标识，请到创作者中心核对，勿重复提交");
            return new Result("SUBMITTED", item, "平台已接收，仍需平台审核；请核实公开结果并回填链接");
        } catch (Exception e) {
            // A timeout after POST may already have published. Never automatically repeat it.
            return new Result("UNKNOWN", null, "提交结果不确定，请到创作者中心核对，勿重复提交");
        }
    }

    byte[] downloadImage(String value) throws Exception {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || (uri
            .getPort() != -1 && uri.getPort() != 443) || !properties.getMediaHosts()
                .contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw new IOException("Unapproved media host");
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            byte[] raw = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address
                .isSiteLocalAddress() || address
                    .isMulticastAddress() || (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) || (raw.length == 4 && (raw[0] & 255) == 100 && (raw[1] & 192) == 64))
                throw new IOException("Non-public media host");
        }
        HttpsURLConnection connection = connection(uri);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_IMAGE)
                throw new IOException("Invalid media response");
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = input.readNBytes(MAX_IMAGE + 1);
            }
            boolean jpeg = bytes.length > 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            boolean png = bytes.length > 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[] {(byte)137, 80, 78, 71,
                13, 10, 26, 10});
            if (bytes.length > MAX_IMAGE || (!jpeg && !png))
                throw new IOException("Only bounded JPEG/PNG are supported");
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    JsonNode post(String endpoint,
                  PublishingProperties.Credential credential,
                  String contentType,
                  byte[] bytes) throws Exception {
        URI uri = URI.create(API + endpoint + "/?open_id=" + URLEncoder.encode(credential
            .getOpenId(), StandardCharsets.UTF_8));
        HttpsURLConnection connection = connection(uri);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("access-token", credential.getAccessToken());
            connection.setRequestProperty("Content-Type", contentType);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            if (connection.getResponseCode() != 200)
                throw new IOException("Platform HTTP failure");
            try (InputStream in = connection.getInputStream()) {
                byte[] response = in.readNBytes(1024 * 1024 + 1);
                if (response.length > 1024 * 1024)
                    throw new IOException("Oversized platform response");
                return json.readTree(response);
            }
        } finally {
            connection.disconnect();
        }
    }

    HttpsURLConnection connection(URI uri) throws IOException {
        var connection = (HttpsURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        return connection;
    }

    private boolean ok(JsonNode value) {
        return value != null && value.path("data").hasNonNull("error_code") && value.path("data")
            .path("error_code")
            .asInt(-1) == 0 && value.path("extra").path("error_code").asInt(0) == 0;
    }

    private int errorCode(JsonNode value) {
        return value.path("data").path("error_code").asInt(-1);
    }

    private Integer responseCode(JsonNode value) {
        if (value.isIntegralNumber() && value.canConvertToInt())
            return value.intValue();
        if (value.isTextual() && value.textValue().matches("[0-9]{1,9}"))
            return Integer.valueOf(value.textValue());
        return null;
    }
}
