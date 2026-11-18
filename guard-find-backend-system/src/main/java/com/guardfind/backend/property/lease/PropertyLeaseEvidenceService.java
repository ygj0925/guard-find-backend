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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import static com.guardfind.backend.property.operations.OperationsStore.id;

/** Private evidence: never publishes photos through public property image URLs. */
@Service
@RequiredArgsConstructor
public class PropertyLeaseEvidenceService {
    private final OperationsStore db;
    private final PropertyLeaseService leases;

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, String> upload(long leaseId, long actor, boolean admin, MultipartFile file) throws IOException {
        leases.detail(leaseId, actor, admin);
        var ref = db.one("SELECT property_id FROM gf_property_lease WHERE id=?", leaseId);
        db.one("SELECT id FROM gf_property WHERE id=? FOR UPDATE", id(ref, "propertyId"));
        leases.detail(leaseId, actor, admin);
        if (file == null || file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
            throw new BusinessException("请选择不超过2MB的JPEG或PNG照片");
        if (db.jdbc()
            .queryForObject("SELECT COUNT(*) FROM gf_property_lease_file WHERE lease_id=?", Long.class, leaseId) >= 100)
            throw new BusinessException("本合同照片数量已达100张，请联系管理员");
        byte[] bytes = file.getBytes();
        String mime;
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext())
                throw new BusinessException("图片格式无效");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName();
                mime = format.equalsIgnoreCase("png")
                    ? "image/png"
                    : format.equalsIgnoreCase("JPEG") ? "image/jpeg" : "";
                if (mime.isEmpty() || (long)reader.getWidth(0) * reader.getHeight(0) > 40000000)
                    throw new BusinessException("仅支持不超过4000万像素的JPEG或PNG照片");
            } finally {
                reader.dispose();
            }
        }
        long fileId = IdUtil.getSnowflakeNextId();
        db.jdbc()
            .update("INSERT INTO gf_property_lease_file(id,lease_id,mime_type,file_data,create_time) VALUES(?,?,?,?,?)", fileId, leaseId, mime, bytes, LocalDateTime
                .now());
        return Map.of("id", Long.toString(fileId));
    }

    public Map<String, String> read(long leaseId, long fileId, long actor, boolean admin) {
        leases.detail(leaseId, actor, admin);
        var rows = db.jdbc()
            .query("SELECT mime_type,file_data FROM gf_property_lease_file WHERE id=? AND lease_id=?", (rs, i) -> Map
                .of("mimeType", rs.getString(1), "data", Base64.getEncoder()
                    .encodeToString(rs.getBytes(2))), fileId, leaseId);
        if (rows.isEmpty())
            throw new BusinessException("附件不存在或无权查看");
        return rows.get(0);
    }
}
