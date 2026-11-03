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

package com.guardfind.backend.miniapp.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guardfind.backend.miniapp.context.MiniappUserContextHolder;
import com.guardfind.backend.miniapp.mapper.MiniappNoticeMapper;
import com.guardfind.backend.miniapp.model.entity.MiniappNoticeDO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import top.continew.starter.extension.crud.model.query.PageQuery;
import top.continew.starter.extension.crud.model.resp.PageResp;

@RestController
@RequiredArgsConstructor
@RequestMapping("/miniapp/notices")
public class MiniappNoticeController {
    private final MiniappNoticeMapper notices;

    @GetMapping
    public PageResp<MiniappNoticeDO> page(PageQuery query) {
        var page = notices.selectPage(new Page<>(Math.max(1, query.getPage() == null ? 1 : query.getPage()), Math
            .max(1, Math.min(100, query.getSize()))), Wrappers.<MiniappNoticeDO>lambdaQuery()
                .eq(MiniappNoticeDO::getUserId, MiniappUserContextHolder.getUserIdRequired())
                .orderByDesc(MiniappNoticeDO::getId));
        var result = new PageResp<MiniappNoticeDO>();
        result.setList(page.getRecords());
        result.setTotal(page.getTotal());
        return result;
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable Long id) {
        notices.update(null, Wrappers.<MiniappNoticeDO>lambdaUpdate()
            .eq(MiniappNoticeDO::getId, id)
            .eq(MiniappNoticeDO::getUserId, MiniappUserContextHolder.getUserIdRequired())
            .set(MiniappNoticeDO::getIsRead, 1));
    }
}
