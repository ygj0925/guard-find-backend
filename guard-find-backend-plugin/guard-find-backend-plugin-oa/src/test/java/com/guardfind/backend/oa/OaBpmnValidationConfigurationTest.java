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

package com.guardfind.backend.oa;

import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import com.guardfind.backend.oa.config.OaBpmnValidationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9.5 校验装配：服务任务白名单只能通过配置放行。
 */
class OaBpmnValidationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OaBpmnValidationConfiguration.class);

    @Test
    void rejectsServiceTaskByDefault() {
        runner.run(context -> {
            OaBpmnValidator validator = context.getBean(OaBpmnValidator.class);
            assertThat(validator.validate(model("com.example.ApproveHandler")).valid()).isFalse();
        });
    }

    @Test
    void allowsConfiguredHandler() {
        runner
            .withPropertyValues("oa.bpmn.allowed-service-handlers=com.example.ApproveHandler, com.example.NotifyHandler")
            .run(context -> {
                OaBpmnValidator validator = context.getBean(OaBpmnValidator.class);
                assertThat(validator.validate(model("com.example.NotifyHandler")).valid()).isTrue();
                assertThat(validator.validate(model("com.example.OtherHandler")).valid()).isFalse();
            });
    }

    private static byte[] model(String handler) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:flowable="http://flowable.org/bpmn"
                              id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
              <bpmn:process id="approval" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="notify" flowable:class="%s"/>
                <bpmn:endEvent id="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """.formatted(handler).getBytes(StandardCharsets.UTF_8);
    }
}
