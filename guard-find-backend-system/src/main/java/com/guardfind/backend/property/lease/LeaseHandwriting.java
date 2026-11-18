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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import top.continew.starter.core.exception.BusinessException;

/** A fixed 1000 x 400 coordinate space keeps signatures independent of screen size. */
public record LeaseHandwriting(List<List<Point>> strokes) {
    public record Point(Double x, Double y) {}

    public byte[] render() {
        if (strokes == null || strokes.isEmpty() || strokes.size() > 120)
            throw invalid();
        int count = 0;
        double minX = 1000, maxX = 0, minY = 400, maxY = 0, distance = 0;
        var paths = new java.util.ArrayList<Path2D.Double>();
        for (var stroke : strokes) {
            if (stroke == null || stroke.size() < 2 || stroke.size() > 2500)
                throw invalid();
            var path = new Path2D.Double();
            Point previous = null;
            for (var point : stroke) {
                if (++count > 12000 || point == null || point.x() == null || point.y() == null || !Double.isFinite(point
                    .x()) || !Double.isFinite(point.y()) || point.x() < 0 || point.x() > 1000 || point.y() < 0 || point
                        .y() > 400)
                    throw invalid();
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minY = Math.min(minY, point.y());
                maxY = Math.max(maxY, point.y());
                if (previous == null)
                    path.moveTo(point.x(), point.y());
                else {
                    path.lineTo(point.x(), point.y());
                    distance += Math.hypot(point.x() - previous.x(), point.y() - previous.y());
                }
                previous = point;
            }
            paths.add(path);
        }
        // Reject blank canvases, taps and tiny accidental marks; this is not identity recognition.
        if (count < 8 || maxX - minX < 40 || maxY - minY < 25 || distance < 100)
            throw invalid();
        var image = new BufferedImage(1000, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1000, 400);
            graphics.setColor(new Color(24, 35, 51));
            graphics.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (var path : paths)
                graphics.draw(path);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("签名保存失败", e);
        } finally {
            graphics.dispose();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException("请在签名区域清晰手写本人完整姓名，不能提交空白或过短笔迹");
    }
}
