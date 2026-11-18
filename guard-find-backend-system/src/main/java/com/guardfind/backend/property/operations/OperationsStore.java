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

package com.guardfind.backend.property.operations;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import top.continew.starter.core.exception.BusinessException;
import java.util.*;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

@Repository
@RequiredArgsConstructor
public class OperationsStore {
    private final JdbcTemplate jdbc;

    /** Field reads bypass Spring repository proxies; dispatch through the target instead. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnLabel(i).toLowerCase(Locale.ROOT);
                StringBuilder name = new StringBuilder();
                boolean upper = false;
                for (char c : column.toCharArray()) {
                    if (c == '_')
                        upper = true;
                    else {
                        name.append(upper ? Character.toUpperCase(c) : c);
                        upper = false;
                    }
                }
                Object value = rs.getObject(i);
                if (value instanceof java.sql.Clob clob)
                    value = clob.getSubString(1, (int)clob.length());
                if (value instanceof java.sql.Date date)
                    value = date.toString();
                if (value instanceof Timestamp t)
                    value = t.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                if (value != null && (column.equals("id") || column.endsWith("_id")))
                    value = value.toString();
                row.put(name.toString(), value);
            }
            return row;
        }, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty())
            throw new BusinessException("记录不存在或无权访问");
        return rows.get(0);
    }

    public Map<String, Object> page(String from, List<Object> args, String select, String order, int page, int size) {
        int safeSize = Math.min(100, Math.max(1, size));
        long total = jdbc.queryForObject("SELECT COUNT(*) " + from, Long.class, args.toArray());
        var query = new ArrayList<>(args);
        query.add(safeSize);
        query.add(((long)Math.max(1, page) - 1) * safeSize);
        return Map.of("list", rows("SELECT " + select + " " + from + " ORDER BY " + order + " LIMIT ? OFFSET ?", query
            .toArray()), "total", total);
    }

    public static long id(Map<String, Object> row, String key) {
        return Long.parseLong(row.get(key).toString());
    }

    public static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    public static String like(String value) {
        return "%" + value.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
