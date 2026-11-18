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

package com.guardfind.backend.property.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 行政区划仓库:民政部县级以上官方版本快照。直辖市保留原始省→区县层级。
 * 首次访问一次性载入并常驻内存,供小程序房源发布的省市区级联选择使用;
 * 资源缺失时降级为空树,不阻断接口。
 *
 * <p>
 * 数据及版本、来源和校验值随本服务发布；不依赖运行时抓取外部网站。
 * </p>
 */
@Slf4j
@Component
public class RegionRepository {

    /** 区划数据资源名(位于 guard-find-backend-system 的 resources 下) */
    private static final String REGION_RESOURCE = "china.json";

    /** 懒加载后的区划树(volatile 保证多线程可见,加载失败时为空列表且不再重试) */
    private volatile List<RegionNodeResp> regionTree;

    /** 区划编码 → 展示名称 */
    private volatile Map<String, String> nameMap;
    private Map<String, Object> regionSource;

    /**
     * 区划树(省 → 市 → 区县)
     *
     * @return 区划树,数据缺失时为空列表
     */
    public List<RegionNodeResp> tree() {
        ensureLoaded();
        return regionTree;
    }

    /**
     * 区划编码转展示名称
     *
     * @param code 区划编码
     * @return 名称,无法判定时返回 null
     */
    public String nameOf(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        ensureLoaded();
        return nameMap.get(code.trim());
    }

    public Map<String, Object> source() {
        ensureLoaded();
        if (regionSource == null)
            throw new IllegalStateException("区划来源元信息缺失或快照校验失败");
        return regionSource;
    }

    private String resource(String path) throws java.io.IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return IoUtil.read(stream, StandardCharsets.UTF_8);
        }
    }

    public Map<String, String> location(String code) {
        for (var province : tree()) {
            if (String.valueOf(province.getCode()).equals(code))
                return Map.of("city", province.getName(), "district", "");
            for (var city : province.getChildren()) {
                boolean directCounty = city.getCode() % 100 != 0;
                boolean municipality = java.util.Set.of(110000L, 120000L, 310000L, 500000L)
                    .contains(province.getCode());
                if (String.valueOf(city.getCode()).equals(code))
                    return Map.of("city", directCounty && municipality
                        ? province.getName()
                        : city.getName(), "district", directCounty ? city.getName() : "");
                for (var district : city.getChildren()) {
                    if (String.valueOf(district.getCode()).equals(code))
                        return Map.of("city", city.getName(), "district", district.getName());
                }
            }
        }
        throw new top.continew.starter.core.exception.BusinessException("行政区划代码不在当前官方数据版本中，请重新选择");
    }

    private void ensureLoaded() {
        if (regionTree != null) {
            return;
        }
        synchronized (this) {
            if (regionTree != null) {
                return;
            }
            load();
        }
    }

    private void load() {
        List<RegionNodeResp> tree = new ArrayList<>();
        Map<String, String> nameIndex = new HashMap<>(4096);
        try {
            String content = resource(REGION_RESOURCE);
            var metadata = JSONUtil.parseObj(resource("china-source.json"));
            String actualHash = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
            if (!actualHash.equals(metadata.getStr("dataSha256")))
                throw new IllegalStateException("区划快照校验不匹配");
            JSONObject root = JSONUtil.parseObj(content);
            JSONArray provinces = root.getJSONArray("children");
            if (provinces != null) {
                for (Object province : provinces) {
                    tree.add(toNode((JSONObject)province, nameIndex));
                }
            }
            this.regionSource = java.util.Collections.unmodifiableMap(new HashMap<>(metadata));
        } catch (Exception e) {
            tree.clear();
            nameIndex.clear();
            this.regionSource = null;
            log.warn("行政区划数据 [{}] 载入失败,区划选择降级为空树: {}", REGION_RESOURCE, e.getMessage());
        }
        this.nameMap = nameIndex;
        this.regionTree = tree;
    }

    private RegionNodeResp toNode(JSONObject json, Map<String, String> nameIndex) {
        RegionNodeResp node = new RegionNodeResp();
        node.setCode(json.getLong("code"));
        node.setName(StrUtil.blankToDefault(json.getStr("fullname"), json.getStr("name")));
        nameIndex.put(String.valueOf(node.getCode()), node.getName());
        JSONArray children = json.getJSONArray("children");
        List<RegionNodeResp> childNodes = new ArrayList<>();
        if (children != null) {
            for (Object child : children) {
                childNodes.add(toNode((JSONObject)child, nameIndex));
            }
        }
        node.setChildren(childNodes);
        return node;
    }
}
