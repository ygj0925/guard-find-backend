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
 * 行政区划仓库:省 → 市 → 区县三级,数据来自随服务发布的 china.json(国标编码)。
 * 首次访问一次性载入并常驻内存,供小程序房源发布的省市区级联选择使用;
 * 资源缺失时降级为空树,不阻断接口。
 *
 * <p>
 * 与用户侧服务(guard-find-server)的 RegionRepository 同源,保证两端区划编码一致。
 * </p>
 */
@Slf4j
@Component
public class RegionRepository {

    /** 区划数据资源名(位于 guard-find-backend-api 的 resources 下,运行时同属一个 classpath) */
    private static final String REGION_RESOURCE = "china.json";

    /** 懒加载后的区划树(volatile 保证多线程可见,加载失败时为空列表且不再重试) */
    private volatile List<RegionNodeResp> regionTree;

    /** 区划编码 → 展示名称 */
    private volatile Map<String, String> nameMap;

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
            ClassPathResource resource = new ClassPathResource(REGION_RESOURCE);
            String content = IoUtil.read(resource.getInputStream(), StandardCharsets.UTF_8);
            JSONObject root = JSONUtil.parseObj(content);
            JSONArray provinces = root.getJSONArray("children");
            if (provinces != null) {
                for (Object province : provinces) {
                    tree.add(toNode((JSONObject)province, nameIndex));
                }
            }
        } catch (Exception e) {
            log.warn("行政区划数据 [{}] 载入失败,区划选择降级为空树: {}", REGION_RESOURCE, e.getMessage());
        }
        this.regionTree = tree;
        this.nameMap = nameIndex;
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
