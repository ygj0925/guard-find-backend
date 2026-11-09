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

package com.guardfind.backend.oa.collab;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 某一时刻的租户组织快照：部门树与在职员工的部门、主管、负责人标记，用于计算日程与报告的可见范围。
 */
public final class OaOrgScope {

    private final Map<Long, Dept> depts = new HashMap<>();
    private final Map<Long, List<Long>> children = new HashMap<>();
    private final Map<Long, Employee> employees = new HashMap<>();
    private final Map<Long, List<Long>> reports = new HashMap<>();

    public OaOrgScope(Collection<Dept> deptRows, Collection<Employee> employeeRows) {
        for (Dept dept : deptRows) {
            depts.put(dept.id(), dept);
            children.computeIfAbsent(dept.parentId(), k -> new ArrayList<>()).add(dept.id());
        }
        for (Employee employee : employeeRows) {
            employees.put(employee.userId(), employee);
            if (employee.supervisorId() != null) {
                reports.computeIfAbsent(employee.supervisorId(), k -> new ArrayList<>()).add(employee.userId());
            }
        }
    }

    public Employee employee(long userId) {
        return employees.get(userId);
    }

    public Collection<Employee> employees() {
        return employees.values();
    }

    public boolean hasDept(long deptId) {
        return depts.containsKey(deptId);
    }

    public String deptName(Long deptId) {
        Dept dept = deptId == null ? null : depts.get(deptId);
        return dept == null ? null : dept.name();
    }

    /**
     * 部门本身及全部下级部门
     */
    public Set<Long> subtree(long deptId) {
        Set<Long> result = new LinkedHashSet<>();
        if (!depts.containsKey(deptId)) {
            return result;
        }
        Deque<Long> queue = new ArrayDeque<>(List.of(deptId));
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (result.add(id)) {
                queue.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return result;
    }

    /**
     * 部门本身及全部上级部门
     */
    public Set<Long> ancestorsOrSelf(Long deptId) {
        Set<Long> result = new LinkedHashSet<>();
        Long current = deptId;
        while (current != null && depts.containsKey(current) && result.add(current)) {
            current = depts.get(current).parentId();
        }
        return result;
    }

    /**
     * 主管链下的全部下属（直属及间接）
     */
    public Set<Long> subordinates(long userId) {
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>(reports.getOrDefault(userId, List.of()));
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (id != userId && result.add(id)) {
                queue.addAll(reports.getOrDefault(id, List.of()));
            }
        }
        return result;
    }

    /**
     * 作为部门负责人可管理的部门（所在部门及其下级部门）
     */
    public Set<Long> ledDepts(long userId) {
        Employee employee = employees.get(userId);
        if (employee == null || !employee.deptLeader() || employee.deptId() == null) {
            return Set.of();
        }
        return subtree(employee.deptId());
    }

    /**
     * 在职员工中部门属于给定集合的用户
     */
    public List<Long> usersInDepts(Collection<Long> deptIds) {
        List<Long> result = new ArrayList<>();
        for (Employee employee : employees.values()) {
            if (employee.deptId() != null && deptIds.contains(employee.deptId())) {
                result.add(employee.userId());
            }
        }
        result.sort(Long::compare);
        return result;
    }

    public record Dept(long id, Long parentId, String name) {
    }

    public record Employee(long userId, String name, Long deptId, Long supervisorId, boolean deptLeader) {
    }
}
