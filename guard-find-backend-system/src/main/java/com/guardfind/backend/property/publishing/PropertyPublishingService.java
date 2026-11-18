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

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.property.operations.OperationsStore;
import com.guardfind.backend.property.operations.PropertyOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.starter.core.exception.BusinessException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class PropertyPublishingService {
    private final OperationsStore db;
    private final PropertyOperationsService operations;
    private final ObjectMapper json;
    private final PublishingGateway gateway;
    private final PublishingProperties properties;
    private final PlatformTransactionManager transactions;
    private static final Set<String> PLATFORMS = Set.of("DOUYIN", "XIAOHONGSHU", "WECHAT_CHANNELS", "OTHER");
    private static final Set<String> STATES = Set
        .of("DRAFT", "QUEUED", "PROCESSING", "MANUAL_REQUIRED", "SUBMITTED", "PUBLISHED", "FAILED", "UNKNOWN", "CANCELLED", "REJECTED");

    public record Account(String platform, String name, String handle, String mode, String credentialKey,
                          boolean enabled) {}

    public record Settings(boolean autoDraft, List<Long> accountIds) {}

    public record Create(Long propertyId, List<Long> accountIds, String title, String body, List<String> images,
                         String scheduledAt, String requestId) {}

    public record Edit(long version, String title, String body, List<String> images, String scheduledAt) {}

    public record Action(long version, String action, String reason, String resultUrl) {}

    public Map<String, Object> capabilities() {
        operations.platform();
        var platforms = new ArrayList<Map<String, Object>>();
        String[][] values = {{"DOUYIN", "抖音", "https://creator.douyin.com/"}, {"XIAOHONGSHU", "小红书",
            "https://creator.xiaohongshu.com/"}, {"WECHAT_CHANNELS", "视频号", "https://channels.weixin.qq.com/"}, {
                "OTHER", "其他平台", ""}};
        for (var p : values)
            platforms.add(Map.of("code", p[0], "label", p[1], "mode", p[0].equals("DOUYIN")
                ? "CONFIGURABLE"
                : "MANUAL", "reason", p[0].equals("DOUYIN")
                    ? "获准的应用与账号配置完成后可提交图文；平台接收后仍需审核。"
                    : "当前通过素材交接和作品链接回填完成发布。", "maxImages", p[0].equals("DOUYIN")
                        ? 30
                        : p[0].equals("XIAOHONGSHU") ? 18 : 9, "maxTextLength", 1000, "creatorUrl", p[2]));
        return Map.of("platforms", platforms, "douyinConfigured", properties.getDouyinAccounts()
            .keySet()
            .stream()
            .anyMatch(gateway::ready));
    }

    public List<Map<String, Object>> accounts() {
        operations.platform();
        var rows = db
            .rows("SELECT id,platform,name,handle,mode,credential_key,enabled FROM gf_property_publish_account ORDER BY id DESC");
        for (var row : rows)
            row.put("ready", "DOUYIN_API".equals(row.get("mode")) && gateway.ready(text(row, "credentialKey")));
        return rows;
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String saveAccount(Long accountId, Account input) {
        operations.platform();
        if (input == null || !PLATFORMS.contains(input.platform()) || !Set.of("MANUAL", "DOUYIN_API")
            .contains(input.mode()))
            throw fail("平台或接入方式无效");
        String name = required(input.name(), 80), handle = required(input.handle(), 100);
        if ("DOUYIN_API".equals(input.mode()) && (!"DOUYIN".equals(input.platform()) || input
            .credentialKey() == null || !input.credentialKey().matches("[A-Za-z0-9_-]{1,80}")))
            throw fail("抖音接口账号须填写有效的服务端授权配置名称");
        // Serialize account identity changes and policy changes without storing platform secrets in SQL.
        db.one("SELECT id FROM gf_property_publish_config WHERE id=1 FOR UPDATE");
        if (accountId != null) {
            var old = db.one("SELECT * FROM gf_property_publish_account WHERE id=? FOR UPDATE", accountId);
            if ((!text(old, "platform").equals(input.platform()) || !text(old, "handle").equals(handle)) && db.jdbc()
                .queryForObject("SELECT COUNT(*) FROM gf_property_publish_job WHERE account_id=?", Long.class, accountId) > 0)
                throw fail("已有任务的账号不能更换平台或账号标识");
            if ((!text(old, "mode").equals(input.mode()) || !text(old, "credentialKey").equals(input
                .credentialKey() == null ? "" : input.credentialKey())) && db.jdbc()
                    .queryForObject("SELECT COUNT(*) FROM gf_property_publish_job WHERE account_id=? AND status IN('DRAFT','QUEUED','PROCESSING','MANUAL_REQUIRED','SUBMITTED','UNKNOWN')", Long.class, accountId) > 0)
                throw fail("请先处理或取消该账号未完成的任务，再切换授权配置");
        }
        if (!db.rows("SELECT id FROM gf_property_publish_account WHERE platform=? AND handle=? AND id<>?", input
            .platform(), handle, accountId == null ? 0L : accountId).isEmpty())
            throw fail("该平台账号已存在");
        if (input.enabled() && "DOUYIN_API".equals(input.mode()))
            uniqueCredential(input.credentialKey(), accountId == null ? 0 : accountId);
        long value = accountId == null ? IdUtil.getSnowflakeNextId() : accountId;
        var now = LocalDateTime.now();
        if (accountId == null)
            db.jdbc()
                .update("INSERT INTO gf_property_publish_account(id,platform,name,handle,mode,credential_key,enabled,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)", value, input
                    .platform(), name, handle, input.mode(), input.credentialKey(), input.enabled(), now, now);
        else
            db.jdbc()
                .update("UPDATE gf_property_publish_account SET platform=?,name=?,handle=?,mode=?,credential_key=?,enabled=?,update_time=? WHERE id=?", input
                    .platform(), name, handle, input.mode(), input.credentialKey(), input.enabled(), now, value);
        return Long.toString(value);
    }

    private void uniqueCredential(String key, long except) {
        var credential = properties.getDouyinAccounts().get(key);
        for (var row : db
            .rows("SELECT id,credential_key FROM gf_property_publish_account WHERE enabled=true AND mode='DOUYIN_API' AND id<>?", except)) {
            String otherKey = text(row, "credentialKey");
            var other = properties.getDouyinAccounts().get(otherKey);
            if (key.equals(otherKey) || (credential != null && other != null && credential
                .getOpenId() != null && !credential.getOpenId().isBlank() && credential.getOpenId()
                    .equals(other.getOpenId())))
                throw fail("同一抖音授权只能绑定一个启用账号");
        }
    }

    public Map<String, Object> settings() {
        operations.platform();
        var row = db.one("SELECT * FROM gf_property_publish_config WHERE id=1");
        row.put("accountIds", strings(text(row, "accountIds")));
        return row;
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void settings(Settings input) {
        operations.platform();
        if (input == null)
            throw fail("请填写自动建稿设置");
        var row = db.one("SELECT * FROM gf_property_publish_config WHERE id=1 FOR UPDATE");
        var ids = accountIds(input.accountIds(), !input.autoDraft());
        for (long account : ids)
            enabledAccount(account);
        Object activated = Boolean.TRUE.equals(row.get("autoDraft")) ? row.get("activatedAt") : LocalDateTime.now();
        db.jdbc()
            .update("UPDATE gf_property_publish_config SET auto_draft=?,account_ids=?,activated_at=? WHERE id=1", input
                .autoDraft(), encode(ids.stream().map(String::valueOf).toList()), activated);
    }

    public Map<String, Object> properties(String keyword) {
        operations.platform();
        var args = new ArrayList<Object>();
        String from = "FROM gf_property p LEFT JOIN gf_property_operation o ON o.property_id=p.id WHERE p.deleted=0 AND p.status=2 AND (o.availability IS NULL OR o.availability='ACTIVE')";
        if (keyword != null && !keyword.isBlank()) {
            from += " AND (p.title LIKE ? ESCAPE '!' OR p.community LIKE ? ESCAPE '!')";
            args.add(like(keyword));
            args.add(like(keyword));
        }
        return db.page(from, args, "p.id,p.title,p.community,p.price,p.price_unit,p.status", "p.id DESC", 1, 30);
    }

    public Map<String, Object> preview(long propertyId) {
        operations.platform();
        return content(source(propertyId, false));
    }

    private Map<String, Object> source(long propertyId, boolean lock) {
        if (lock)
            db.one("SELECT id FROM gf_property WHERE id=? FOR UPDATE", propertyId);
        var row = db
            .one("SELECT p.id,p.title,p.city,p.district,p.community,p.layout,p.area_sqm,p.price,p.price_type,p.price_unit,p.images,p.status,p.deleted,o.availability FROM gf_property p LEFT JOIN gf_property_operation o ON o.property_id=p.id WHERE p.id=?" + (lock
                ? " FOR UPDATE"
                : ""), propertyId);
        if (id(row, "status") != 2 || id(row, "deleted") != 0 || (!text(row, "availability").isEmpty() && !"ACTIVE"
            .equals(row.get("availability"))))
            throw fail("房源已下架、已出租或暂不接待，不能发布推广");
        return row;
    }

    private Map<String, Object> content(Map<String, Object> source) {
        String place = String.join(" ", text(source, "city"), text(source, "district"), text(source, "community"))
            .trim();
        String price = text(source, "price") + ("rent".equals(source.get("priceType"))
            ? "day".equals(source.get("priceUnit")) ? "元/天" : "元/月"
            : "元");
        String body = "房源：" + text(source, "title") + "\n区域/小区：" + place + "\n户型：" + text(source, "layout") + "\n面积：" + text(source, "areaSqm") + "平方米\n" + ("rent"
            .equals(source.get("priceType")) ? "租金：" : "售价：") + price + "\n具体房况、费用和可入住时间请预约核实。";
        return Map
            .of("propertyId", text(source, "id"), "title", text(source, "title"), "body", body, "images", strings(text(source, "images")));
    }

    public Map<String, Object> jobs(int page, int size, String status, Long propertyId) {
        operations.platform();
        String from = "FROM gf_property_publish_job j JOIN gf_property_publish_account a ON a.id=j.account_id WHERE 1=1";
        var args = new ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            if (!STATES.contains(status))
                throw fail("发布任务状态无效");
            from += " AND j.status=?";
            args.add(status);
        }
        if (propertyId != null) {
            from += " AND j.property_id=?";
            args.add(propertyId);
        }
        var result = db
            .page(from, args, "j.*,a.name account_name,j.create_time created_at,j.update_time updated_at", "j.id DESC", page, size);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>)result.get("list");
        rows.forEach(this::decorate);
        return result;
    }

    public Map<String, Object> job(long id) {
        operations.platform();
        var row = db
            .one("SELECT j.*,a.name account_name,j.create_time created_at,j.update_time updated_at FROM gf_property_publish_job j JOIN gf_property_publish_account a ON a.id=j.account_id WHERE j.id=?", id);
        decorate(row);
        row.put("logs", db
            .rows("SELECT action,note reason,create_time created_at FROM gf_property_publish_log WHERE job_id=? ORDER BY id DESC", id));
        return row;
    }

    private void decorate(Map<String, Object> row) {
        row.put("images", strings(text(row, "images")));
        row.remove("requestHash");
        row.remove("requestKey");
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> create(Create input, long actor) {
        operations.platform();
        return createInternal(input, actor);
    }

    private Map<String, Object> createInternal(Create input, Long actor) {
        if (input == null || input.propertyId() == null)
            throw fail("请选择房源");
        var ids = accountIds(input.accountIds(), false);
        String request = required(input.requestId(), 120);
        if (!request.matches("[A-Za-z0-9:_-]+"))
            throw fail("发布请求标识无效");
        var property = source(input.propertyId(), true);
        var generated = content(property);
        var date = schedule(input.scheduledAt());
        String requestHash = digest(Arrays.asList(input.propertyId(), ids, input.title(), input.body(), input
            .images(), input.scheduledAt()));
        String sourceHash = digest(property);
        var created = new ArrayList<String>();
        for (long accountId : ids) {
            var account = enabledAccount(accountId);
            String requestKey = request + ":" + accountId;
            var existing = db
                .rows("SELECT id,request_hash FROM gf_property_publish_job WHERE request_key=?", requestKey);
            if (!existing.isEmpty()) {
                if (!requestHash.equals(text(existing.get(0), "requestHash")))
                    throw fail("该请求已用于其他内容，请刷新后重新创建");
                created.add(text(existing.get(0), "id"));
                continue;
            }
            String title = input.title() == null ? text(generated, "title") : input.title();
            int titleLimit = "XIAOHONGSHU".equals(account.get("platform")) ? 20 : 100;
            if (input.title() == null && title.length() > titleLimit)
                title = title.substring(0, titleLimit);
            String body = input.body() == null ? text(generated, "body") : input.body();
            List<String> images = input.images() == null ? strings(text(property, "images")) : input.images();
            // Auto-created drafts may need better photos; approval always performs strict validation.
            if (actor != null)
                validateContent(account, title, body, images);
            // Repeated clicks with different request IDs still cannot duplicate an active/finished exact post.
            for (var old : db
                .rows("SELECT id,title,body,images FROM gf_property_publish_job WHERE property_id=? AND account_id=? AND source_hash=? AND status NOT IN('CANCELLED','REJECTED')", input
                    .propertyId(), accountId, sourceHash)) {
                if (title.trim().equals(text(old, "title")) && body.trim().equals(text(old, "body")) && images
                    .equals(strings(text(old, "images"))))
                    throw fail("该账号已有相同内容任务，请先处理原任务");
            }
            long id = IdUtil.getSnowflakeNextId();
            var now = LocalDateTime.now();
            db.jdbc()
                .update("INSERT INTO gf_property_publish_job(id,property_id,account_id,platform,title,body,images,source_hash,status,scheduled_at,request_key,request_hash,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, input
                    .propertyId(), accountId, account.get("platform"), title.trim(), body
                        .trim(), encode(images), sourceHash, "DRAFT", date, requestKey, requestHash, now, now);
            log(id, actor, "CREATE", actor == null ? "房源上架后自动建稿，等待审核" : "创建发布草稿");
            created.add(Long.toString(id));
        }
        return Map.of("ids", created);
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void edit(long id, Edit input, long actor) {
        operations.platform();
        var row = lockJob(id);
        version(row, input.version());
        if (!Set.of("DRAFT", "REJECTED", "FAILED").contains(text(row, "status")))
            throw fail("请先取消已排期任务；结果不确定或已提交的任务不能修改");
        var property = source(id(row, "propertyId"), true);
        var account = enabledAccount(id(row, "accountId"));
        validateContent(account, input.title(), input.body(), input.images());
        db.jdbc()
            .update("UPDATE gf_property_publish_job SET title=?,body=?,images=?,source_hash=?,scheduled_at=?,status='DRAFT',last_error=NULL,attempts=0,approved_by=NULL,approved_at=NULL,version=version+1,update_time=? WHERE id=?", input
                .title()
                .trim(), input.body().trim(), encode(input.images()), digest(property), schedule(input
                    .scheduledAt()), LocalDateTime.now(), id);
        log(id, actor, "EDIT", "更新草稿，需重新审核");
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void action(long jobId, Action input, long actor) {
        operations.platform();
        if (input == null || input.action() == null)
            throw fail("请选择任务操作");
        var row = lockJob(jobId);
        version(row, input.version());
        String status = text(row, "status"), next;
        String reason = input.reason() == null ? "" : input.reason().trim();
        if (reason.length() > 800)
            throw fail("说明不能超过800字");
        String resultUrl = null;
        switch (input.action()) {
            case "APPROVE", "RETRY" -> {
                if ("APPROVE".equals(input.action()) ? !"DRAFT".equals(status) : !"FAILED".equals(status))
                    throw fail("当前状态不支持审核或安全重试");
                if (id(row, "attempts") >= 5)
                    throw fail("已尝试5次，请修正内容后重新审核");
                ensureCurrent(row);
                var account = enabledAccount(id(row, "accountId"));
                validateContent(account, text(row, "title"), text(row, "body"), strings(text(row, "images")));
                next = "QUEUED";
                db.jdbc()
                    .update("UPDATE gf_property_publish_job SET approved_by=?,approved_at=? WHERE id=?", actor, LocalDateTime
                        .now(), jobId);
            }
            case "REJECT" -> {
                if (!"DRAFT".equals(status))
                    throw fail("仅待审核草稿可退回");
                required(reason, 800);
                next = "REJECTED";
            }
            case "CANCEL" -> {
                if (!Set.of("DRAFT", "QUEUED", "FAILED", "MANUAL_REQUIRED", "REJECTED").contains(status))
                    throw fail("任务可能已提交平台，请先核实外部结果");
                next = "CANCELLED";
            }
            case "CONFIRM_PUBLISHED" -> {
                if (!Set.of("MANUAL_REQUIRED", "SUBMITTED", "UNKNOWN").contains(status))
                    throw fail("仅已提交或人工处理任务可以确认发布");
                resultUrl = publicUrl(required(input.resultUrl(), 1000));
                validateResultUrl(text(row, "platform"), resultUrl);
                next = "PUBLISHED";
            }
            case "CONFIRM_NOT_PUBLISHED" -> {
                if (!Set.of("SUBMITTED", "UNKNOWN").contains(status))
                    throw fail("仅已提交或结果待核实的任务可登记未发布结果");
                required(reason, 800);
                next = "CANCELLED";
            }
            default -> throw fail("不支持的发布操作");
        }
        db.jdbc()
            .update("UPDATE gf_property_publish_job SET status=?,result_url=COALESCE(?,result_url),last_error=NULL,version=version+1,update_time=? WHERE id=?", next, resultUrl, LocalDateTime
                .now(), jobId);
        log(jobId, actor, input.action(), reason.isEmpty()
            ? "CONFIRM_PUBLISHED".equals(input.action()) ? "运营核实作品已发布：" + resultUrl : "操作完成"
            : reason);
    }

    public Map<String, Object> exportPackage(long id) {
        var row = job(id);
        @SuppressWarnings("unchecked") var images = (List<String>)row.get("images");
        String document = "# " + text(row, "title") + "\n\n" + text(row, "body") + "\n\n## 发布交接\n\n目标账号：" + text(row, "accountName") + "\n平台：" + text(row, "platform") + "\n任务编号：" + id + "\n\n## 房源图片地址\n\n" + String
            .join("\n", images) + "\n\n导出不表示已经发布。请核对目标账号及房源当前状态，发布后回填真实作品链接。\n";
        return Map.of("filename", "property-publishing-" + id + ".md", "content", document);
    }

    public void maintain() {
        readCommittedTransaction().executeWithoutResult(tx -> {
            for (var row : db
                .rows("SELECT id FROM gf_property_publish_job WHERE status='PROCESSING' AND update_time<?", LocalDateTime
                    .now()
                    .minusMinutes(15))) {
                long id = id(row, "id");
                if (db.jdbc()
                    .update("UPDATE gf_property_publish_job SET status='UNKNOWN',last_error='提交超时，请核实平台结果后回填；请勿重复发送',version=version+1,update_time=? WHERE id=? AND status='PROCESSING' AND update_time<?", LocalDateTime
                        .now(), id, LocalDateTime.now().minusMinutes(15)) == 1)
                    log(id, null, "RECOVER_UNKNOWN", "提交进程超时，禁止自动重发");
            }
        });
        scanAutoDrafts();
        var candidates = db
            .rows("SELECT j.id FROM gf_property_publish_job j JOIN gf_property_publish_account a ON a.id=j.account_id WHERE j.status='QUEUED' AND j.scheduled_at<=? AND (a.last_dispatch_at IS NULL OR a.last_dispatch_at<?) ORDER BY j.scheduled_at,j.id LIMIT 30", LocalDateTime
                .now(), LocalDateTime.now().minusMinutes(5));
        for (var candidate : candidates) {
            var claimed = readCommittedTransaction().execute(tx -> claim(id(candidate, "id")));
            if (claimed == null)
                continue;
            PublishingGateway.Result result;
            try {
                result = gateway
                    .publish(text(claimed, "credentialKey"), text(claimed, "title"), text(claimed, "body"), strings(text(claimed, "images")), () -> stillCurrent(claimed));
            } catch (Exception e) {
                result = new PublishingGateway.Result("UNKNOWN", null, "平台提交结果不确定，请核实后处理");
            }
            var outcome = result;
            readCommittedTransaction().executeWithoutResult(tx -> {
                String state = Set.of("SUBMITTED", "FAILED", "UNKNOWN", "MANUAL_REQUIRED").contains(outcome.status())
                    ? outcome.status()
                    : "UNKNOWN";
                String explanation = outcome.message() == null ? "请核实平台结果" : outcome.message();
                if (explanation.length() > 500)
                    explanation = explanation.substring(0, 500);
                if (db.jdbc()
                    .update("UPDATE gf_property_publish_job SET status=?,platform_item_id=?,last_error=?,version=version+1,update_time=? WHERE id=? AND status='PROCESSING' AND version=?", state, outcome
                        .itemId(), explanation, LocalDateTime.now(), id(claimed, "id"), id(claimed, "version")) == 1)
                    log(id(claimed, "id"), null, state, explanation);
            });
        }
    }

    private Map<String, Object> claim(long id) {
        var row = lockJob(id);
        if (!"QUEUED".equals(row.get("status")) || LocalDateTime.parse(text(row, "scheduledAt").replace(' ', 'T'))
            .isAfter(LocalDateTime.now()))
            return null;
        Map<String, Object> account;
        try {
            ensureCurrent(row);
            account = enabledAccount(id(row, "accountId"));
            validateContent(account, text(row, "title"), text(row, "body"), strings(text(row, "images")));
            if ("DOUYIN_API".equals(account.get("mode")))
                uniqueCredential(text(account, "credentialKey"), id(account, "id"));
        } catch (BusinessException e) {
            db.jdbc()
                .update("UPDATE gf_property_publish_job SET status='REJECTED',last_error=?,version=version+1,update_time=? WHERE id=?", e
                    .getMessage(), LocalDateTime.now(), id);
            log(id, null, "REJECTED", e.getMessage());
            return null;
        }
        if (!"DOUYIN_API".equals(account.get("mode")) || !gateway.ready(text(account, "credentialKey"))) {
            db.jdbc()
                .update("UPDATE gf_property_publish_job SET status='MANUAL_REQUIRED',last_error='请使用素材包在目标账号发布，完成后回填作品链接',version=version+1,update_time=? WHERE id=?", LocalDateTime
                    .now(), id);
            log(id, null, "MANUAL_REQUIRED", "当前账号使用人工发布交接");
            return null;
        }
        if ((!text(account, "lastDispatchAt").isEmpty() && LocalDateTime.parse(text(account, "lastDispatchAt")
            .replace(' ', 'T')).isAfter(LocalDateTime.now().minusMinutes(5))) || !db
                .rows("SELECT id FROM gf_property_publish_job WHERE account_id=? AND status='PROCESSING' FOR UPDATE", id(row, "accountId"))
                .isEmpty())
            return null;
        db.jdbc()
            .update("UPDATE gf_property_publish_account SET last_dispatch_at=? WHERE id=?", LocalDateTime
                .now(), id(row, "accountId"));
        db.jdbc()
            .update("UPDATE gf_property_publish_job SET status='PROCESSING',attempts=attempts+1,version=version+1,update_time=? WHERE id=?", LocalDateTime
                .now(), id);
        log(id, null, "DISPATCH", "开始向平台提交，等待结果");
        row.put("version", id(row, "version") + 1);
        row.put("credentialKey", account.get("credentialKey"));
        return row;
    }

    private boolean stillCurrent(Map<String, Object> claimed) {
        try {
            var account = enabledAccount(id(claimed, "accountId"));
            if (!"DOUYIN_API".equals(account.get("mode")) || !text(account, "credentialKey")
                .equals(text(claimed, "credentialKey")))
                return false;
            var row = db.one("SELECT status,version FROM gf_property_publish_job WHERE id=?", id(claimed, "id"));
            if (!"PROCESSING".equals(row.get("status")) || id(row, "version") != id(claimed, "version"))
                return false;
            ensureCurrent(claimed);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void scanAutoDrafts() {
        var config = db.one("SELECT * FROM gf_property_publish_config WHERE id=1");
        if (!Boolean.TRUE.equals(config.get("autoDraft")) || config.get("activatedAt") == null)
            return;
        var candidates = db
            .rows("SELECT p.id FROM gf_property p LEFT JOIN gf_property_operation o ON o.property_id=p.id WHERE p.deleted=0 AND p.status=2 AND (o.availability IS NULL OR o.availability='ACTIVE') AND p.create_time>=? AND NOT EXISTS(SELECT 1 FROM gf_property_publish_job j WHERE j.property_id=p.id AND j.request_key LIKE 'auto:%') ORDER BY p.id LIMIT 50", config
                .get("activatedAt"));
        for (var candidate : candidates) {
            try {
                readCommittedTransaction().executeWithoutResult(tx -> {
                    var latest = db.one("SELECT * FROM gf_property_publish_config WHERE id=1 FOR UPDATE");
                    if (!Boolean.TRUE.equals(latest.get("autoDraft")))
                        return;
                    long property = id(candidate, "id");
                    if (!db
                        .rows("SELECT id FROM gf_property_publish_job WHERE property_id=? AND request_key LIKE 'auto:%'", property)
                        .isEmpty())
                        return;
                    var ids = strings(text(latest, "accountIds")).stream()
                        .map(Long::valueOf)
                        .filter(account -> !db
                            .rows("SELECT id FROM gf_property_publish_account WHERE id=? AND enabled=true", account)
                            .isEmpty())
                        .toList();
                    if (ids.isEmpty())
                        return;
                    createInternal(new Create(property, ids, null, null, null, null, "auto:" + property), null);
                });
            } catch (Exception e) {
                log.warn("房源 {} 自动建稿未完成，其他任务继续处理：{}", candidate.get("id"), e instanceof BusinessException
                    ? e.getMessage()
                    : e.getClass().getSimpleName());
            }
        }
    }

    private Map<String, Object> lockJob(long jobId) {
        var ref = db.one("SELECT property_id,account_id FROM gf_property_publish_job WHERE id=?", jobId);
        db.one("SELECT id FROM gf_property WHERE id=? FOR UPDATE", id(ref, "propertyId"));
        db.one("SELECT id FROM gf_property_publish_account WHERE id=? FOR UPDATE", id(ref, "accountId"));
        return db.one("SELECT * FROM gf_property_publish_job WHERE id=? FOR UPDATE", jobId);
    }

    private void version(Map<String, Object> row, long version) {
        if (id(row, "version") != version)
            throw fail("任务已更新，请刷新后操作");
    }

    private void ensureCurrent(Map<String, Object> row) {
        if (!digest(source(id(row, "propertyId"), true)).equals(text(row, "sourceHash")))
            throw fail("房源信息已变化，请重新核对并编辑草稿后审核");
    }

    private LocalDateTime schedule(String value) {
        if (value == null || value.isBlank())
            return LocalDateTime.now();
        try {
            var date = LocalDateTime.parse(value.replace(' ', 'T'));
            if (date.isAfter(LocalDateTime.now().plusYears(1)))
                throw fail("最多提前一年排期");
            return date;
        } catch (java.time.format.DateTimeParseException e) {
            throw fail("发布时间格式无效");
        }
    }

    private void validateContent(Map<String, Object> account, String title, String body, List<String> images) {
        required(title, "XIAOHONGSHU".equals(account.get("platform")) ? 20 : 100);
        required(body, 1000);
        if (title.trim().length() + body.trim().length() + 1 > 1000)
            throw fail("标题与正文合计不能超过1000字");
        int max = "DOUYIN".equals(account.get("platform"))
            ? 30
            : "XIAOHONGSHU".equals(account.get("platform")) ? 18 : 9;
        if (images == null || images.isEmpty() || images.size() > max || images.stream().distinct().count() != images
            .size())
            throw fail("请提供1至" + max + "张不重复的房源图片");
        images.forEach(this::publicUrl);
    }

    private String publicUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri
                .getUserInfo() != null || value.length() > 2000)
                throw fail("请使用有效的HTTPS公开链接");
            return value;
        } catch (IllegalArgumentException e) {
            throw fail("链接格式无效");
        }
    }

    private void validateResultUrl(String platform, String value) {
        String host = URI.create(value).getHost().toLowerCase(Locale.ROOT);
        String root = switch (platform) {
            case "DOUYIN" -> "douyin.com";
            case "XIAOHONGSHU" -> "xiaohongshu.com";
            case "WECHAT_CHANNELS" -> "weixin.qq.com";
            default -> "";
        };
        if (!root.isEmpty() && !host.equals(root) && !host.endsWith("." + root) && !(platform
            .equals("XIAOHONGSHU") && host.equals("xhslink.com")))
            throw fail("作品链接与目标平台不符");
    }

    private void log(long jobId, Long actor, String action, String reason) {
        db.jdbc()
            .update("INSERT INTO gf_property_publish_log(id,job_id,actor_id,action,note,create_time) VALUES(?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), jobId, actor, action, reason.length() > 1000
                    ? reason.substring(0, 1000)
                    : reason, LocalDateTime.now());
    }

    private List<String> strings(String value) {
        if (value.isBlank())
            return List.of();
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw fail("内容素材格式无效，请核对房源图片");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("发布内容序列化失败", e);
        }
    }

    private String digest(Object value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String required(String value, int length) {
        if (value == null || value.isBlank() || value.trim().length() > length)
            throw fail("请填写1至" + length + "字的有效内容");
        return value.trim();
    }

    private List<Long> accountIds(List<Long> ids, boolean emptyAllowed) {
        if (ids == null || ids.size() > 20 || (!emptyAllowed && ids.isEmpty()) || ids.stream()
            .anyMatch(id -> id == null || id <= 0))
            throw fail("请选择1至20个有效发布账号");
        return ids.stream().distinct().sorted().toList();
    }

    private Map<String, Object> enabledAccount(long id) {
        var row = db.one("SELECT * FROM gf_property_publish_account WHERE id=? FOR UPDATE", id);
        if (!Boolean.TRUE.equals(row.get("enabled")))
            throw fail("发布账号不存在或已停用");
        return row;
    }

    private BusinessException fail(String message) {
        return new BusinessException(message);
    }

    private TransactionTemplate readCommittedTransaction() {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
