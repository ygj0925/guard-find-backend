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

package com.guardfind.backend.oa.launch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可发起范围：用户、部门、角色三类条件取并集，全部为空表示不限制。
 *
 * <pre>
 * {"users":[101],"depts":[10],"roles":["general"]}
 * </pre>
 *
 * <p>
 * 只接受该结构，避免把任意 JSON 当策略解释；发布时校验并规范化，发起时按当前用户上下文匹配。
 * </p>
 */
public record OaLaunchScope(List<Long> users, List<Long> depts, List<String> roles) {

    private static final int MAX_ENTRIES = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final OaLaunchScope EMPTY = new OaLaunchScope(List.of(), List.of(), List.of());

    public OaLaunchScope {
        users = users == null ? List.of() : List.copyOf(users);
        depts = depts == null ? List.of() : List.copyOf(depts);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static OaLaunchScope parse(String json) {
        if (json == null || json.isBlank()) {
            return EMPTY;
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("必须是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("必须是 JSON 对象");
        }
        var names = root.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!"users".equals(name) && !"depts".equals(name) && !"roles".equals(name)) {
                throw new IllegalArgumentException("不支持字段 " + name + "，仅允许 users/depts/roles");
            }
        }
        return new OaLaunchScope(longList(root.get("users"), "users"), longList(root
            .get("depts"), "depts"), roleList(root.get("roles")));
    }

    public boolean unrestricted() {
        return users.isEmpty() && depts.isEmpty() && roles.isEmpty();
    }

    public boolean allows(Long userId, Long deptId, Set<String> roleCodes) {
        if (unrestricted()) {
            return true;
        }
        if (userId != null && users.contains(userId)) {
            return true;
        }
        if (deptId != null && depts.contains(deptId)) {
            return true;
        }
        if (!roles.isEmpty() && roleCodes != null) {
            return roles.stream().anyMatch(roleCodes::contains);
        }
        return false;
    }

    public String toJsonOrNull() {
        if (unrestricted()) {
            return null;
        }
        ObjectNode node = OBJECT_MAPPER.createObjectNode();
        if (!users.isEmpty()) {
            ArrayNode array = node.putArray("users");
            users.forEach(array::add);
        }
        if (!depts.isEmpty()) {
            ArrayNode array = node.putArray("depts");
            depts.forEach(array::add);
        }
        if (!roles.isEmpty()) {
            ArrayNode array = node.putArray("roles");
            roles.forEach(array::add);
        }
        return node.toString();
    }

    private static List<Long> longList(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(name + " 必须是数组");
        }
        if (node.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(name + " 不能超过 " + MAX_ENTRIES + " 项");
        }
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber()) {
                throw new IllegalArgumentException(name + " 只能包含整数 ID");
            }
            long value = item.asLong();
            if (value <= 0) {
                throw new IllegalArgumentException(name + " 只能包含正整数 ID");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static List<String> roleList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("roles 必须是数组");
        }
        if (node.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("roles 不能超过 " + MAX_ENTRIES + " 项");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("roles 只能包含角色编码字符串");
            }
            String value = item.asText().trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("roles 不能包含空编码");
            }
            if (value.length() > 64) {
                throw new IllegalArgumentException("roles 中的编码不能超过 64 个字符");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }
}
