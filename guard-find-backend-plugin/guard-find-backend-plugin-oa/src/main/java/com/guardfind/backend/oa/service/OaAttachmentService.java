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

package com.guardfind.backend.oa.service;

import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.repository.OaAttachmentRepository;
import com.guardfind.backend.oa.repository.OaAttachmentRepository.AttachmentRow;
import com.guardfind.backend.oa.repository.OaAttachmentRepository.FileRow;
import com.guardfind.backend.oa.repository.OaAttachmentRepository.LinkedAttachment;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.stereotype.Service;
import top.continew.starter.core.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OA 附件归属与下载授权。
 *
 * <p>
 * 发起时只把上传人本人、同租户的已上传文件挂到申请上；下载按申请参与人授权
 * （发起人、当前处理人、已有审计记录的历史审批人），不依赖可猜测的 fileId。
 * </p>
 */
@Service
public class OaAttachmentService {

    static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    private static final int MAX_TOTAL_ATTACHMENTS = 100;
    private static final String NOT_FOUND = "附件不存在或无权访问";

    private final OaAttachmentRepository repository;
    private final OaApprovalEngine approvalEngine;

    public OaAttachmentService(OaAttachmentRepository repository, OaApprovalEngine approvalEngine) {
        this.repository = repository;
        this.approvalEngine = approvalEngine;
    }

    /**
     * 校验附件归属与大小，并用服务端文件名覆盖客户端展示名。
     */
    public PreparedForm prepare(long tenantId, long userId, Map<String, Object> normalized) {
        List<Long> orderedIds = new ArrayList<>();
        normalized.values().forEach(value -> {
            if (value instanceof Collection<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> map && map.get("fileId") instanceof Number number) {
                        orderedIds.add(number.longValue());
                    }
                }
            }
        });
        if (orderedIds.isEmpty()) {
            return new PreparedForm(normalized, List.of());
        }
        if (orderedIds.size() > MAX_TOTAL_ATTACHMENTS) {
            throw new BusinessException("附件总数不能超过 " + MAX_TOTAL_ATTACHMENTS + " 个");
        }
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new BusinessException("同一附件不能被多个字段重复引用");
        }

        Map<Long, FileRow> files = repository.uploadedFiles(tenantId, userId, orderedIds)
            .stream()
            .collect(Collectors.toMap(FileRow::id, Function.identity(), (existing, replacement) -> existing));
        List<AttachmentRow> attachments = new ArrayList<>();
        for (Long fileId : orderedIds) {
            FileRow file = files.get(fileId);
            if (file == null) {
                throw new BusinessException("附件不存在或无权使用");
            }
            if (file.type() == 0) {
                throw new BusinessException("不能把文件夹作为附件");
            }
            if (file.size() == null || file.size() > MAX_ATTACHMENT_BYTES) {
                throw new BusinessException("单个附件不能超过 10MB");
            }
            attachments.add(new AttachmentRow(file.id(), displayName(file), file.size(), contentType(file
                .contentType())));
        }
        if (!repository.linkedFileIds(tenantId, orderedIds).isEmpty()) {
            throw new BusinessException("附件已被其他申请使用，请重新上传");
        }

        Map<Long, AttachmentRow> byId = attachments.stream()
            .collect(Collectors.toMap(AttachmentRow::fileId, Function.identity()));
        Map<String, Object> values = new LinkedHashMap<>();
        normalized.forEach((key, value) -> values.put(key, replaceAttachments(value, byId)));
        return new PreparedForm(Map.copyOf(values), List.copyOf(attachments));
    }

    /**
     * 申请落库后写入附件归属；调用方需保证与申请、幂等记录处于同一事务。
     */
    public void link(long tenantId,
                     long applicationId,
                     String businessType,
                     String businessKey,
                     long userId,
                     List<AttachmentRow> attachments,
                     LocalDateTime now) {
        if (attachments.isEmpty()) {
            return;
        }
        repository.insertLinks(tenantId, applicationId, businessType, businessKey, userId, attachments, now);
    }

    public DownloadAttachment authorizeDownload(long tenantId, long userId, long fileId) {
        LinkedAttachment link = repository.linkedForDownload(tenantId, fileId);
        if (link == null) {
            throw new BusinessException(NOT_FOUND);
        }
        boolean authorized = userId == link.applicantId() || repository.hasAuditRecord(tenantId, link
            .applicationId(), userId) || approvalEngine.hasTaskAccess(link.processInstanceId(), Long
                .toString(tenantId), Long.toString(userId));
        if (!authorized) {
            throw new BusinessException(NOT_FOUND);
        }
        FileRow file = link.file();
        return new DownloadAttachment(displayName(file), file.contentType(), file.size(), toFileInfo(file));
    }

    private static Object replaceAttachments(Object value, Map<Long, AttachmentRow> byId) {
        if (!(value instanceof Collection<?> items)) {
            return value;
        }
        boolean changed = false;
        List<Object> replaced = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map && map.get("fileId") instanceof Number number) {
                AttachmentRow attachment = byId.get(number.longValue());
                if (attachment != null) {
                    Map<String, Object> file = new LinkedHashMap<>();
                    Object uid = map.get("uid");
                    if (uid instanceof CharSequence text) {
                        file.put("uid", text.toString());
                    }
                    file.put("name", attachment.name());
                    file.put("fileId", attachment.fileId());
                    replaced.add(Map.copyOf(file));
                    changed = true;
                    continue;
                }
            }
            replaced.add(item);
        }
        return changed ? List.copyOf(replaced) : value;
    }

    private static String displayName(FileRow file) {
        return file.originalName() == null || file.originalName().isBlank() ? file.name() : file.originalName();
    }

    private static String contentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        return contentType.length() > 100 ? contentType.substring(0, 100) : contentType;
    }

    private static FileInfo toFileInfo(FileRow file) {
        FileInfo info = new FileInfo();
        info.setPlatform(file.storageCode());
        info.setFilename(file.name());
        info.setOriginalFilename(displayName(file));
        info.setBasePath("");
        info.setSize(file.size());
        info.setPath(parentPath(file.parentPath()));
        info.setExt(file.extension());
        info.setContentType(file.contentType());
        info.setUrl(withoutLeadingSlash(file.path()));
        return info;
    }

    private static String parentPath(String parentPath) {
        if (parentPath == null || parentPath.isBlank() || "/".equals(parentPath)) {
            return "";
        }
        String path = parentPath.startsWith("/") ? parentPath.substring(1) : parentPath;
        return path.endsWith("/") ? path : path + "/";
    }

    private static String withoutLeadingSlash(String path) {
        if (path == null) {
            return "";
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    public record PreparedForm(Map<String, Object> values, List<AttachmentRow> attachments) {

        public PreparedForm {
            values = values == null ? Map.of() : Map.copyOf(values);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    public record DownloadAttachment(String fileName, String contentType, Long size, FileInfo fileInfo) {
    }
}
