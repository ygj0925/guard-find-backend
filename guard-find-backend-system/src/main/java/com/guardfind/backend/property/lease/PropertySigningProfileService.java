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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guardfind.backend.property.operations.OperationsStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.continew.starter.core.exception.BusinessException;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.*;

@Service
@RequiredArgsConstructor
public class PropertySigningProfileService {
    private final OperationsStore db;
    private final ObjectMapper json;

    private void authorize(long user, boolean lock) {
        db.one("SELECT id FROM gf_miniapp_user WHERE id=? AND deleted=0 AND account_status=1" + (lock
            ? " FOR UPDATE"
            : ""), user);
    }

    public Map<String, Object> profile(long user) {
        authorize(user, false);
        var result = new LinkedHashMap<String, Object>();
        for (var asset : db
            .rows("SELECT id,kind,strokes_json,update_time FROM gf_property_signing_asset WHERE owner_id=?", user)) {
            if ("HANDWRITING".equals(asset.get("kind"))) {
                try {
                    asset.put("handwriting", json.readValue(text(asset, "strokesJson"), LeaseHandwriting.class));
                } catch (Exception e) {
                    throw new IllegalStateException("签名读取失败", e);
                }
            }
            asset.remove("strokesJson");
            asset.put("imageBase64", Base64.getEncoder()
                .encodeToString(image(user, id(asset, "id"), text(asset, "kind"))));
            result.put(text(asset, "kind").toLowerCase(Locale.ROOT), asset);
        }
        return result;
    }

    public byte[] image(long user, long assetId, String kind) {
        authorize(user, false);
        var values = db.jdbc()
            .query("SELECT image_data FROM gf_property_signing_asset WHERE id=? AND owner_id=? AND kind=?", (rs,
                                                                                                             index) -> rs
                                                                                                                 .getBytes(1), assetId, user, kind);
        if (values.isEmpty())
            throw new BusinessException("签名或印章已更新，请重新选择本人的签署资料");
        return values.get(0);
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveHandwriting(long user, LeaseHandwriting handwriting, byte[] rendered) {
        authorize(user, true);
        try {
            save(user, "HANDWRITING", json.writeValueAsString(handwriting), rendered);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> uploadSeal(long user, MultipartFile file) {
        authorize(user, true);
        if (file == null || file.isEmpty() || file.getSize() > 1024 * 1024)
            throw new BusinessException("请上传不超过1MB的PNG印章图片");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext())
                throw new BusinessException("印章图片格式不正确");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0) < 20 || reader
                    .getHeight(0) < 20 || reader.getWidth(0) > 2000 || reader.getHeight(0) > 2000)
                    throw new BusinessException("印章须为PNG图片，尺寸为20至2000像素");
                var image = reader.read(0);
                boolean ink = false;
                for (int y = 0; y < image.getHeight() && !ink; y++)
                    for (int x = 0; x < image.getWidth(); x++) {
                        int pixel = image.getRGB(x, y);
                        if ((pixel >>> 24) > 32 && (((pixel >> 16) & 255) < 245 || ((pixel >> 8) & 255) < 245 || (pixel & 255) < 245)) {
                            ink = true;
                            break;
                        }
                    }
                if (!ink)
                    throw new BusinessException("印章图片为空白，请重新选择");
                var output = new ByteArrayOutputStream();
                ImageIO.write(image, "png", output);
                save(user, "SEAL", null, output.toByteArray());
            } finally {
                reader.dispose();
            }
            return profile(user);
        } catch (java.io.IOException e) {
            throw new BusinessException("无法读取印章图片，请重新导出PNG文件");
        }
    }

    private void save(long user, String kind, String strokes, byte[] image) {
        db.jdbc().update("DELETE FROM gf_property_signing_asset WHERE owner_id=? AND kind=?", user, kind);
        db.jdbc()
            .update("INSERT INTO gf_property_signing_asset(id,owner_id,kind,strokes_json,image_data,update_time) VALUES(?,?,?,?,?,?)", IdUtil
                .getSnowflakeNextId(), user, kind, strokes, image, LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(long user, String kind) {
        authorize(user, true);
        if (!Set.of("HANDWRITING", "SEAL").contains(kind))
            throw new BusinessException("签署资料类型无效");
        db.jdbc().update("DELETE FROM gf_property_signing_asset WHERE owner_id=? AND kind=?", user, kind);
    }
}
