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

package com.guardfind.backend.oa.config;

import com.guardfind.backend.oa.engine.OaEngineTemplate;
import com.guardfind.backend.oa.staff.JdbcOaStaffDirectory;
import com.guardfind.backend.oa.staff.OaAssigneeResolver;
import com.guardfind.backend.oa.staff.OaProcessLauncher;
import com.guardfind.backend.oa.staff.OaStaffDirectory;
import org.flowable.engine.ProcessEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OA 员工目录与审批人解析装配。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ProcessEngine.class, JdbcTemplate.class})
public class OaStaffConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OaStaffDirectory oaStaffDirectory(JdbcTemplate jdbcTemplate) {
        return new JdbcOaStaffDirectory(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public OaAssigneeResolver oaAssigneeResolver(OaStaffDirectory staffDirectory) {
        return new OaAssigneeResolver(staffDirectory);
    }

    @Bean
    @ConditionalOnMissingBean
    public OaProcessLauncher oaProcessLauncher(OaEngineTemplate engineTemplate, OaAssigneeResolver assigneeResolver) {
        return new OaProcessLauncher(engineTemplate, assigneeResolver);
    }
}
