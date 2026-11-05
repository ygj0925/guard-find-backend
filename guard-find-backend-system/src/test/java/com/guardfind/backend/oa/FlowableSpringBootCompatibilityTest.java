package com.guardfind.backend.oa;

import org.flowable.engine.ProcessEngine;
import org.flowable.spring.boot.ProcessEngineAutoConfiguration;
import org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9.1 隔离兼容性验证：Flowable 7.2.0 的 Spring Boot 自动配置能在本项目
 * Boot 3.4.10 / Spring 6.2 + Jakarta 环境下创建 ProcessEngine。
 *
 * 只装配数据源与流程引擎，避免把项目完整上下文（Redis、租户、定时任务）引入单测。
 */
class FlowableSpringBootCompatibilityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            ProcessEngineAutoConfiguration.class,
            ProcessEngineServicesAutoConfiguration.class))
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:flowable-spring;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "flowable.process.enabled=true",
            "flowable.database-schema-update=true",
            "flowable.async-executor-activate=false");

    @Test
    void autoConfiguresProcessEngine() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProcessEngine.class);
            ProcessEngine engine = context.getBean(ProcessEngine.class);
            assertThat(engine.getProcessEngineConfiguration().getDatabaseSchemaUpdate()).isEqualTo("true");
        });
    }
}
