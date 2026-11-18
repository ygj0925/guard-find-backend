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

import cn.hutool.core.util.IdUtil;
import com.guardfind.backend.property.operations.OperationsStore;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import top.continew.starter.core.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.guardfind.backend.property.operations.OperationsStore.id;
import static com.guardfind.backend.property.operations.OperationsStore.text;

/**
 * Identity verification is deliberately provider-neutral. The default mode is DISABLED.
 * Provider credentials and raw identity images never enter this service or the database.
 */
@Service
public class PropertyIdentityVerificationService {
    private final OperationsStore db;
    private final IdentityVerificationProvider provider;

    @Autowired
    public PropertyIdentityVerificationService(OperationsStore db, IdentityVerificationProvider provider) {
        this.db = db;
        this.provider = provider;
    }

    public PropertyIdentityVerificationService(OperationsStore db) {
        this(db, new UnavailableIdentityVerificationProvider());
    }

    public record ConsentInput(Boolean accepted, String consentVersion) {}

    public record SessionInput(Boolean accepted, String consentVersion, String purpose, String realName,
                               String idNumber) {
        public SessionInput() {
            this(null, null, "LEASE_SIGN", null, null);
        }
    }

    public record AdminAction(String action, String reason, Long version) {
        public AdminAction(String action, String reason) {
            this(action, reason, null);
        }
    }

    public record SettingsInput(boolean enabled, boolean required, String provider, int verificationTtlDays) {}

    @Data
    public static class Config {
        private String mode = "DISABLED";
        private String provider;
        private boolean requiredForSigning;
        private int verificationTtlDays = 365;
        private String consentVersion = "identity-v1";
    }

    public Map<String, Object> status(long userId) {
        Config config = config();
        Map<String, Object> latest = latest(userId);
        String rawState = latest.isEmpty() ? "" : text(latest, "status");
        String state = latest.isEmpty()
            ? ("DISABLED".equals(config.getMode()) ? "NOT_REQUIRED" : "PENDING_CONSENT")
            : rawState;
        boolean verified = "VERIFIED".equals(state) && !expired(latest);
        if ("VERIFIED".equals(state) && !verified)
            state = "EXPIRED";
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enabled", !"DISABLED".equals(config.getMode()));
        result.put("mode", config.getMode());
        result.put("provider", config.getProvider() == null ? "" : config.getProvider());
        result.put("required", config.isRequiredForSigning());
        result.put("consentVersion", config.getConsentVersion());
        result.put("status", verified ? "VERIFIED" : normalizeState(state));
        result.put("verified", verified);
        result.put("sessionId", latest.isEmpty() ? "" : text(latest, "id"));
        result.put("maskedIdNumber", latest.isEmpty() ? "" : text(latest, "idNumberMasked"));
        result.put("consentAt", latest.isEmpty() ? "" : text(latest, "consentTime"));
        result.put("verifiedAt", latest.isEmpty() ? "" : text(latest, "verifiedAt"));
        result.put("launchUrl", latest.isEmpty() ? "" : text(latest, "providerLaunchUrl"));
        result.put("expiresAt", latest.isEmpty() || latest.get("expiresAt") == null ? "" : latest.get("expiresAt"));
        result.put("displayName", latest.isEmpty() ? "" : text(latest, "realName"));
        return result;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> consent(long userId, ConsentInput input) {
        Config config = config();
        if (!config.isRequiredForSigning() && "DISABLED".equals(config.getMode()))
            return status(userId);
        requireConsent(input, config);
        LocalDateTime now = LocalDateTime.now();
        db.jdbc()
            .update("INSERT INTO gf_property_identity_verification(id,user_id,status,provider,consent_version,consent_time,create_time,update_time) VALUES(?,?,?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), userId, "PENDING_CONSENT", config.getProvider(), config
                    .getConsentVersion(), now, now, now);
        return status(userId);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> start(long userId, SessionInput input) {
        Config config = config();
        if ("DISABLED".equals(config.getMode()))
            return status(userId);
        if (input != null && input.purpose() != null && !"LEASE_SIGN".equals(input.purpose()))
            throw fail("当前仅支持合同签署身份核验");
        Map<String, Object> consent = latest(userId);
        if (consent.isEmpty() || !"PENDING_CONSENT".equals(text(consent, "status")) || !config.getConsentVersion()
            .equals(text(consent, "consentVersion")))
            throw fail("请先同意身份核验说明");
        long sessionId = IdUtil.getSnowflakeNextId();
        LocalDateTime now = LocalDateTime.now();
        if ("MANUAL".equals(config.getMode()))
            validateManualIdentity(input);
        IdentityVerificationProvider.BeginResult begin = "PROVIDER".equals(config.getMode())
            ? this.provider.begin(userId, "LEASE_SIGN", config.getConsentVersion())
            : new IdentityVerificationProvider.BeginResult("PENDING", null, null, null);
        String state = "MANUAL".equals(config.getMode()) ? "PENDING" : begin.status();
        String failure = "MANUAL".equals(config.getMode()) ? null : begin.failureReason();
        String realName = input == null || input.realName() == null ? null : input.realName().trim();
        String idNumber = input == null || input.idNumber() == null
            ? null
            : input.idNumber().trim().toUpperCase(Locale.ROOT);
        db.jdbc()
            .update("INSERT INTO gf_property_identity_verification(id,user_id,status,real_name,id_number_masked,id_number_hash,provider,provider_session_id,provider_launch_url,consent_version,consent_time,failure_reason,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", sessionId, userId, state, realName, idNumber == null
                ? null
                : maskIdNumber(idNumber), idNumber == null ? null : sha256(idNumber), config.getProvider(), begin
                    .providerSessionId() == null ? Long.toString(sessionId) : begin.providerSessionId(), begin
                        .launchUrl(), config.getConsentVersion(), now, failure, now, now);
        db.jdbc()
            .update("UPDATE gf_property_identity_verification SET status='VOID',update_time=? WHERE id=?", now, id(consent, "id"));
        return session(userId, sessionId);
    }

    public Map<String, Object> session(long userId, long sessionId) {
        Map<String, Object> row = db
            .one("SELECT id,status,real_name,provider,provider_session_id,provider_launch_url,consent_version,consent_time,failure_reason,verified_at,expires_at FROM gf_property_identity_verification WHERE id=? AND user_id=?", sessionId, userId);
        String sessionState = text(row, "status");
        if ("VERIFIED".equals(sessionState) && expired(row))
            sessionState = "EXPIRED";
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sessionId", text(row, "id"));
        result.put("status", normalizeState(sessionState));
        result.put("displayName", text(row, "realName"));
        result.put("provider", text(row, "provider"));
        result.put("providerSessionId", text(row, "providerSessionId"));
        result.put("consentVersion", text(row, "consentVersion"));
        result.put("consentTime", text(row, "consentTime"));
        result.put("failureReason", text(row, "failureReason"));
        result.put("verifiedAt", text(row, "verifiedAt"));
        result.put("expiresAt", text(row, "expiresAt"));
        result.put("launchUrl", text(row, "providerLaunchUrl"));
        return result;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> adminAction(long sessionId, long adminId, AdminAction input) {
        if (input == null || input.action() == null)
            throw fail("请选择核验处理结果");
        Map<String, Object> row = db
            .one("SELECT * FROM gf_property_identity_verification WHERE id=? FOR UPDATE", sessionId);
        if (input.version() != null && id(row, "version") != input.version())
            throw fail("核验记录已更新，请刷新后再处理");
        String action = input.action().trim().toUpperCase(Locale.ROOT);
        LocalDateTime now = LocalDateTime.now();
        if ("VERIFY".equals(action)) {
            db.jdbc()
                .update("UPDATE gf_property_identity_verification SET status='VERIFIED',verified_at=?,expires_at=?,failure_reason=NULL,reviewer_id=?,review_time=?,version=version+1,update_time=? WHERE id=?", now, now
                    .plusDays(config().getVerificationTtlDays()), adminId, now, now, sessionId);
        } else if ("REJECT".equals(action)) {
            if (input.reason() == null || input.reason().isBlank())
                throw fail("拒绝核验时必须填写原因");
            db.jdbc()
                .update("UPDATE gf_property_identity_verification SET status='REJECTED',failure_reason=?,reviewer_id=?,review_time=?,version=version+1,update_time=? WHERE id=?", input
                    .reason()
                    .trim(), adminId, now, now, sessionId);
        } else {
            throw fail("不支持的核验处理");
        }
        return session(id(row, "userId"), sessionId);
    }

    public Config config() {
        Map<String, Object> row = db
            .one("SELECT mode,provider,required_for_signing,verification_ttl_days,consent_version FROM gf_property_identity_config WHERE id=1");
        Config config = new Config();
        config.setMode(text(row, "mode").isBlank() ? "DISABLED" : text(row, "mode").toUpperCase(Locale.ROOT));
        config.setProvider(text(row, "provider").isBlank() ? null : text(row, "provider"));
        config.setRequiredForSigning(Boolean.TRUE.equals(row.get("requiredForSigning")) || "1"
            .equals(text(row, "requiredForSigning")));
        config.setVerificationTtlDays(row.get("verificationTtlDays") == null
            ? 365
            : Integer.parseInt(row.get("verificationTtlDays").toString()));
        config.setConsentVersion(text(row, "consentVersion").isBlank() ? "identity-v1" : text(row, "consentVersion"));
        if (!List.of("DISABLED", "MANUAL", "PROVIDER").contains(config.getMode()))
            throw fail("身份核验模式配置无效");
        return config;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void saveConfig(Config input) {
        if (input == null || input.getMode() == null || !List.of("DISABLED", "MANUAL", "PROVIDER")
            .contains(input.getMode().toUpperCase(Locale.ROOT)))
            throw fail("身份核验模式只能是DISABLED、MANUAL或PROVIDER");
        if (input.getConsentVersion() == null || input.getConsentVersion().isBlank() || input.getConsentVersion()
            .length() > 40)
            throw fail("同意版本不能为空且不超过40字");
        if ("PROVIDER".equalsIgnoreCase(input.getMode()) && (input.getProvider() == null || input.getProvider()
            .isBlank()))
            throw fail("启用外部核验时必须配置服务商标识");
        if (input.getVerificationTtlDays() < 1 || input.getVerificationTtlDays() > 3650)
            throw fail("核验有效期应为1至3650天");
        db.jdbc()
            .update("UPDATE gf_property_identity_config SET mode=?,provider=?,required_for_signing=?,verification_ttl_days=?,consent_version=?,update_time=? WHERE id=1", input
                .getMode()
                .toUpperCase(Locale.ROOT), input.getProvider(), input.isRequiredForSigning(), input
                    .getVerificationTtlDays(), input.getConsentVersion().trim(), LocalDateTime.now());
    }

    public Map<String, Object> settingsView() {
        Config c = config();
        return Map.of("enabled", !"DISABLED".equals(c.getMode()), "required", c.isRequiredForSigning(), "provider", c
            .getProvider() == null ? "DISABLED" : c.getProvider(), "verificationTtlDays", c.getVerificationTtlDays());
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void saveSettings(SettingsInput input) {
        if (input == null)
            throw fail("实名认证配置不能为空");
        String provider = input.provider() == null ? "DISABLED" : input.provider().trim().toUpperCase(Locale.ROOT);
        Config c = config();
        c.setMode(!input.enabled() || "DISABLED".equals(provider)
            ? "DISABLED"
            : ("MANUAL".equals(provider) ? "MANUAL" : "PROVIDER"));
        c.setProvider("DISABLED".equals(provider) || "MANUAL".equals(provider)
            ? ("MANUAL".equals(provider) ? "MANUAL" : null)
            : provider);
        c.setRequiredForSigning(input.required());
        c.setVerificationTtlDays(input.verificationTtlDays());
        saveConfig(c);
    }

    public Map<String, Object> page(String status, int page, int size) {
        String from = "FROM gf_property_identity_verification i LEFT JOIN gf_miniapp_user u ON u.id=i.user_id WHERE 1=1";
        var args = new java.util.ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            from += " AND i.status=?";
            args.add(status.trim().toUpperCase(Locale.ROOT));
        }
        Map<String, Object> result = db
            .page(from, args, "i.id,i.user_id,u.nickname AS user_name,'USER' AS role,i.status,i.id_number_masked,i.provider,i.verified_at,i.failure_reason,i.update_time AS last_attempt_at,i.version", "i.update_time DESC,i.id DESC", page, size);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>)result.get("list");
        for (Map<String, Object> row : rows) {
            row.put("status", normalizeState(text(row, "status")));
            row.put("maskedIdNumber", row.remove("idNumberMasked"));
            row.put("userId", row.get("userId").toString());
            row.put("id", row.get("id").toString());
        }
        return result;
    }

    public void requireVerified(long userId, Long sessionId) {
        Config config = config();
        if (!config.isRequiredForSigning() || "DISABLED".equals(config.getMode()))
            return;
        Map<String, Object> row = sessionId == null
            ? latest(userId)
            : db.one("SELECT * FROM gf_property_identity_verification WHERE id=? AND user_id=?", sessionId, userId);
        if (!"VERIFIED".equals(text(row, "status")) || expired(row))
            throw fail("签署前请先完成身份核验");
    }

    private Map<String, Object> latest(long userId) {
        var rows = db
            .rows("SELECT id,status,real_name,id_number_masked,provider,provider_session_id,provider_launch_url,consent_version,consent_time,failure_reason,verified_at,expires_at FROM gf_property_identity_verification WHERE user_id=? AND status<>'VOID' ORDER BY update_time DESC,id DESC LIMIT 1", userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void validateManualIdentity(SessionInput input) {
        if (input == null || input.realName() == null || input.realName().isBlank() || input.realName().length() > 100)
            throw fail("人工核验请填写真实姓名");
        validateIdNumber(input.idNumber());
    }

    private String normalizeState(String value) {
        return switch (value) {
            case "PENDING_CONSENT" -> "PENDING_CONSENT";
            case "REJECTED", "UNAVAILABLE" -> "FAILED";
            case "VERIFIED" -> "VERIFIED";
            case "PENDING" -> "PENDING";
            default -> value.isBlank() ? "UNVERIFIED" : value;
        };
    }

    private boolean expired(Map<String, Object> row) {
        Object expiresAt = row.get("expiresAt");
        return expiresAt != null && !expiresAt.toString().isBlank() && LocalDateTime.parse(expiresAt.toString()
            .replace(' ', 'T')).isBefore(LocalDateTime.now());
    }

    private void requireConsent(ConsentInput input, Config config) {
        if (input == null || !input.accepted() || !config.getConsentVersion().equals(input.consentVersion()))
            throw fail("请先阅读并同意身份核验及个人信息处理说明");
    }

    private void validateIdNumber(String value) {
        if (value == null || !value.trim()
            .toUpperCase(Locale.ROOT)
            .matches("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])([0-2]\\d|3[01])\\d{3}[0-9X]$"))
            throw fail("身份证号码格式不正确");
    }

    private String maskIdNumber(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.substring(0, 4) + "**********" + normalized.substring(normalized.length() - 4);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BusinessException fail(String message) {
        return new BusinessException(message);
    }
}
