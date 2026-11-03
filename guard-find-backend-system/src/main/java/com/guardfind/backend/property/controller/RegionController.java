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

package com.guardfind.backend.property.controller;

import java.util.List;

import com.guardfind.backend.property.support.RegionNodeResp;
import com.guardfind.backend.property.support.RegionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行政区划 API(小程序房源发布省市区级联选择)
 *
 * <p>
 * 公开接口(免登录):区划数据无敏感信息,浏览/发布前都需要。
 * </p>
 */
@Tag(name = "行政区划 API(小程序)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/miniapp/region")
public class RegionController {

    private final RegionRepository regionRepository;

    @Operation(summary = "省市区树", description = "省 → 市 → 区县三级,国标编码,数据缺失时为空列表")
    @GetMapping("/tree")
    public List<RegionNodeResp> tree() {
        return regionRepository.tree();
    }
}
