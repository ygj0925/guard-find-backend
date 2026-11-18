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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.awt.Color;
import java.io.*;
import java.util.*;
import static com.guardfind.backend.property.operations.OperationsStore.text;

@Service
@RequiredArgsConstructor
public class PropertyLeasePdfService {
    private final PropertyLeaseService leases;

    @Transactional(readOnly = true)
    public Map<String, Object> download(long leaseId, Long user, boolean admin) {
        var lease = leases.detail(leaseId, user, admin);
        try {
            return Map.of("filename", "租赁合同-" + leaseId + ".pdf", "base64", Base64.getEncoder()
                .encodeToString(render(lease)));
        } catch (IOException e) {
            throw new IllegalStateException("合同PDF生成失败，请重试", e);
        }
    }

    public byte[] render(Map<String, Object> lease) throws IOException {
        try (var document = new PDDocument(); var fontStream = getClass()
            .getResourceAsStream("/fonts/NotoSansSC-Regular.ttf")) {
            if (fontStream == null)
                throw new IOException("合同中文字体未安装");
            var font = PDType0Font.load(document, fontStream, true);
            var writer = new Layout(document, font, text(lease, "id"), status(text(lease, "status")));
            writer.newPage();
            writer.paragraph(text(lease, "title"), 21, 32);
            writer.paragraph("房屋租赁合同 · " + status(text(lease, "status")), 11, 24);
            writer.rule();
            for (String line : List
                .of("合同编号：" + text(lease, "id"), "出租方（甲方）：" + text(lease, "landlordName"), "承租方（乙方）：" + text(lease, "tenantName"), "房源：" + text(lease, "propertyTitle"), "坐落地址：" + text(lease, "propertyAddress"), "租赁期限：" + text(lease, "startDate") + " 至 " + text(lease, "endDate"), "月租金：人民币 " + text(lease, "monthlyRent") + " 元    押金：人民币 " + text(lease, "deposit") + " 元"))
                writer.paragraph(line, 11, 21);
            writer.gap(16);
            writer.paragraph("合同条款", 14, 27);
            writer.paragraph(text(lease, "terms"), 11, 21);
            writer.newPage();
            writer.paragraph("签署记录", 20, 34);
            writer.paragraph("本页签名与本合同正文一并留存。", 11, 24);
            writer.paragraph("合同内容校验值：" + text(lease, "contentHash"), 8, 15);
            @SuppressWarnings("unchecked") var signatures = (List<Map<String, Object>>)lease
                .getOrDefault("signatures", List.of());
            for (String party : List.of("landlord", "tenant")) {
                writer.ensure(230);
                writer.gap(18);
                writer.rule();
                String role = party.equals("landlord") ? "LANDLORD" : "TENANT";
                writer.paragraph((party.equals("landlord")
                    ? "出租方（甲方）："
                    : "承租方（乙方）：") + text(lease, party + "Name"), 13, 25);
                var signature = signatures.stream()
                    .filter(s -> role.equals(s.get("signerRole")))
                    .findFirst()
                    .orElse(null);
                if (signature != null) {
                    writer.images(text(signature, "imageBase64"), text(signature, "sealBase64"));
                    writer.paragraph("手写签署时间：" + text(signature, "signedAt"), 10, 20);
                    writer.paragraph("签署记录校验值：" + text(signature, "recordHash"), 8, 14);
                } else
                    writer.paragraph(lease.get(party + "SignedAt") == null
                        ? "尚未签署"
                        : "历史账号确认签署：" + text(lease, party + "SignedAt") + "（当时未采集手写签名）", 11, 21);
            }
            writer.gap(24);
            writer.paragraph("签署说明：本合同记录小程序账号确认及手写签名；所附印章为使用人上传的图片。未接入第三方实名认证、CA数字证书或可信时间戳服务。", 9, 17);
            if (writer.hasEncodedCharacters)
                writer.paragraph("字符说明：字体不能显示的字符以[U+字符编码]保留，请在小程序合同原文中查看对应字符。", 9, 17);
            writer.finish();
            document.getDocumentInformation().setTitle(text(lease, "title"));
            document.getDocumentInformation().setCreator("泊之湾合同服务");
            var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String status(String status) {
        return switch (status) {
            case "DRAFT" -> "草稿 · 尚未发送签署";
            case "PENDING_SIGNATURE" -> "签署中 · 双方尚未签齐";
            case "SIGNED" -> "双方已签署";
            case "EXPIRED" -> "租期已结束";
            case "TERMINATED" -> "合同已终止";
            case "VOID" -> "合同已作废";
            case "SIGNING_EXPIRED" -> "签署已超时";
            default -> status;
        };
    }

    private static class Layout {
        final PDDocument document;
        final PDType0Font font;
        final String id;
        final String status;
        PDPageContentStream stream;
        boolean hasEncodedCharacters;
        float y;
        final float left = 48, width = PDRectangle.A4.getWidth() - 96;

        Layout(PDDocument document, PDType0Font font, String id, String status) {
            this.document = document;
            this.font = font;
            this.id = id;
            this.status = status;
        }

        void newPage() throws IOException {
            if (stream != null)
                stream.close();
            var page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = 776;
            at("泊之湾 / 租赁合同", 9, left, 811);
            at(status, 9, 330, 811);
        }

        void ensure(float height) throws IOException {
            if (y - height < 66)
                newPage();
        }

        void gap(float height) throws IOException {
            ensure(height);
            y -= height;
        }

        void rule() throws IOException {
            ensure(12);
            stream.setStrokingColor(new Color(214, 220, 229));
            stream.moveTo(left, y);
            stream.lineTo(left + width, y);
            stream.stroke();
            y -= 16;
        }

        void at(String text, float size, float x, float baseline) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(new Color(31, 42, 58));
            stream.newLineAtOffset(x, baseline);
            stream.showText(text);
            stream.endText();
        }

        void paragraph(String content, float size, float leading) throws IOException {
            for (String paragraph : content.replace("\r", "").replace("\t", "    ").split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                float length = 0;
                for (int code : paragraph.codePoints().toArray()) {
                    if (Character.isISOControl(code))
                        continue;
                    String glyph = new String(Character.toChars(code));
                    float glyphWidth;
                    try {
                        glyphWidth = font.getStringWidth(glyph) / 1000 * size;
                    } catch (IllegalArgumentException missing) {
                        glyph = "[U+" + Integer.toHexString(code).toUpperCase(Locale.ROOT) + "]";
                        hasEncodedCharacters = true;
                        glyphWidth = font.getStringWidth(glyph) / 1000 * size;
                    }
                    if (length + glyphWidth > width && !line.isEmpty()) {
                        ensure(leading);
                        at(line.toString(), size, left, y);
                        y -= leading;
                        line.setLength(0);
                        length = 0;
                    }
                    line.append(glyph);
                    length += glyphWidth;
                }
                ensure(leading);
                at(line.toString(), size, left, y);
                y -= leading;
            }
        }

        void images(String signature, String seal) throws IOException {
            ensure(105);
            stream.drawImage(PDImageXObject.createFromByteArray(document, Base64.getDecoder()
                .decode(signature), "signature"), left, y - 90, 225, 90);
            if (!seal.isEmpty()) {
                var image = PDImageXObject.createFromByteArray(document, Base64.getDecoder().decode(seal), "seal");
                float scale = Math.min(95f / image.getWidth(), 95f / image.getHeight());
                stream.drawImage(image, left + 300, y - 95, image.getWidth() * scale, image.getHeight() * scale);
            }
            y -= 110;
        }

        void finish() throws IOException {
            if (stream != null)
                stream.close();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                stream = new PDPageContentStream(document, document
                    .getPage(i), PDPageContentStream.AppendMode.APPEND, true, true);
                at("合同 " + id, 8, left, 33);
                at("第 " + (i + 1) + " / " + document.getNumberOfPages() + " 页", 8, 450, 33);
                stream.close();
            }
        }
    }
}
