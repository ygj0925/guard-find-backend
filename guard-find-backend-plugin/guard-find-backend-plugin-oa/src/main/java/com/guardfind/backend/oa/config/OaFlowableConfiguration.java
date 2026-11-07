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

import com.guardfind.backend.oa.engine.OaApprovalEngine;
import com.guardfind.backend.oa.engine.OaEngineTemplate;
import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OA 引擎接入：引擎表由 Liquibase 托管，流程引擎只参与 Spring 事务，不启用引擎自带身份与事件注册表。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ProcessEngine.class)
public class OaFlowableConfiguration {

    private static final String LIQUIBASE_BEAN = "liquibase";

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> oaProcessEngineConfigurer() {
        return configuration -> {
            configuration.setDisableIdmEngine(true);
            configuration.setDisableEventRegistry(true);
            configuration.setHistoryLevel(HistoryLevel.FULL);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public OaEngineTemplate oaEngineTemplate(RuntimeService runtimeService, TaskService taskService) {
        return new OaEngineTemplate(runtimeService, taskService);
    }

    @Bean
    @ConditionalOnMissingBean
    public OaApprovalEngine oaApprovalEngine(RuntimeService runtimeService,
                                             TaskService taskService,
                                             HistoryService historyService,
                                             RepositoryService repositoryService) {
        return new OaApprovalEngine(runtimeService, taskService, historyService, repositoryService);
    }

    /**
     * 生产环境关闭引擎自动建表，必须保证 Liquibase 先完成迁移再初始化引擎。
     */
    @Bean
    public static BeanFactoryPostProcessor oaEngineSchemaOrdering() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition(LIQUIBASE_BEAN)) {
                return;
            }
            for (String name : new String[] {"springProcessEngineConfiguration", "processEngine"}) {
                if (!beanFactory.containsBeanDefinition(name)) {
                    continue;
                }
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                String[] existing = definition.getDependsOn();
                if (existing == null || existing.length == 0) {
                    definition.setDependsOn(LIQUIBASE_BEAN);
                    continue;
                }
                boolean alreadyDepends = false;
                for (String dependency : existing) {
                    alreadyDepends = alreadyDepends || LIQUIBASE_BEAN.equals(dependency);
                }
                if (alreadyDepends) {
                    continue;
                }
                String[] merged = new String[existing.length + 1];
                System.arraycopy(existing, 0, merged, 0, existing.length);
                merged[existing.length] = LIQUIBASE_BEAN;
                definition.setDependsOn(merged);
            }
        };
    }
}
