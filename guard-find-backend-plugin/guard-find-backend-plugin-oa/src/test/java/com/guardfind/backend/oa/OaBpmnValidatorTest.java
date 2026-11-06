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

import com.guardfind.backend.oa.bpmn.OaBpmnValidationResult;
import com.guardfind.backend.oa.bpmn.OaBpmnValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 9.5 BPMN 安全校验：XXE、节点/属性白名单、受控服务任务、条件表达式与容量限制。
 */
class OaBpmnValidatorTest {

    private static final OaBpmnValidator VALIDATOR = new OaBpmnValidator();

    private static final String HEADER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                          xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                          xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                          xmlns:flowable="http://flowable.org/bpmn"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        """;

    private static final String SAFE_PROCESS = """
        <bpmn:startEvent id="start">
          <bpmn:outgoing>flow1</bpmn:outgoing>
        </bpmn:startEvent>
        <bpmn:userTask id="approve" flowable:candidateUsers="${approver}">
          <bpmn:incoming>flow1</bpmn:incoming>
          <bpmn:outgoing>flow2</bpmn:outgoing>
        </bpmn:userTask>
        <bpmn:boundaryEvent id="remind" attachedToRef="approve" cancelActivity="false">
          <bpmn:outgoing>flowRemind</bpmn:outgoing>
          <bpmn:timerEventDefinition id="timer1">
            <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT24H</bpmn:timeDuration>
          </bpmn:timerEventDefinition>
        </bpmn:boundaryEvent>
        <bpmn:exclusiveGateway id="gateway">
          <bpmn:incoming>flow2</bpmn:incoming>
          <bpmn:outgoing>flowHigh</bpmn:outgoing>
          <bpmn:outgoing>flowLow</bpmn:outgoing>
        </bpmn:exclusiveGateway>
        <bpmn:endEvent id="end">
          <bpmn:incoming>flowHigh</bpmn:incoming>
          <bpmn:incoming>flowLow</bpmn:incoming>
          <bpmn:incoming>flowRemind</bpmn:incoming>
        </bpmn:endEvent>
        <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="approve"/>
        <bpmn:sequenceFlow id="flow2" sourceRef="approve" targetRef="gateway"/>
        <bpmn:sequenceFlow id="flowHigh" sourceRef="gateway" targetRef="end">
          <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${amount &gt;= 1000}</bpmn:conditionExpression>
        </bpmn:sequenceFlow>
        <bpmn:sequenceFlow id="flowLow" sourceRef="gateway" targetRef="end">
          <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${amount &lt; 1000}</bpmn:conditionExpression>
        </bpmn:sequenceFlow>
        <bpmn:sequenceFlow id="flowRemind" sourceRef="remind" targetRef="end"/>
        """;

    @Test
    void acceptsSafeExecutableModel() {
        OaBpmnValidationResult result = validate(document(SAFE_PROCESS));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.nodeCount()).isEqualTo(5);
        assertThat(result.processKey()).isEqualTo("leave");
        assertThat(result.digest()).hasSize(64);
    }

    @Test
    void rejectsConditionOnDefaultFlow() {
        String model = document(SAFE_PROCESS)
            .replace("<bpmn:exclusiveGateway id=\"gateway\">", "<bpmn:exclusiveGateway id=\"gateway\" default=\"flowLow\">");

        OaBpmnValidationResult result = validate(model);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("默认连线 flowLow 不能配置条件"));
    }

    @Test
    void acceptsBpmnJsDiagramInterchange() {
        String diagram = """
            <bpmndi:BPMNDiagram id="BPMNDiagram_1">
              <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="leave">
                <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="start">
                  <dc:Bounds x="100" y="100" width="36" height="36"/>
                  <bpmndi:BPMNLabel>
                    <dc:Bounds x="108" y="143" width="20" height="14"/>
                  </bpmndi:BPMNLabel>
                </bpmndi:BPMNShape>
                <bpmndi:BPMNEdge id="Flow_1_di" bpmnElement="flow1">
                  <di:waypoint x="136" y="118"/>
                  <di:waypoint x="200" y="118"/>
                </bpmndi:BPMNEdge>
              </bpmndi:BPMNPlane>
            </bpmndi:BPMNDiagram>
            """;

        OaBpmnValidationResult result = validate(document(SAFE_PROCESS)
            .replace("</bpmn:definitions>", diagram + "</bpmn:definitions>"));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        String document = document(SAFE_PROCESS);
        int rootIndex = document.indexOf("<bpmn:definitions");
        String xml = document
            .substring(0, rootIndex) + "<!DOCTYPE definitions [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n" + document
                .substring(rootIndex);

        OaBpmnValidationResult result = validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("DTD"));
    }

    @Test
    void rejectsScriptTask() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:scriptTask id="script">
              <bpmn:script>java.lang.Runtime.getRuntime().exec('calc')</bpmn:script>
            </bpmn:scriptTask>
            <bpmn:endEvent id="end"/>
            """;

        assertInvalid(document(process), "不允许的 BPMN 节点: scriptTask");
    }

    @Test
    void rejectsUnsupportedNodesAndExtensionElements() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:userTask id="approve" flowable:candidateGroups="manager">
              <bpmn:extensionElements>
                <flowable:executionListener class="com.evil.Listener"/>
              </bpmn:extensionElements>
            </bpmn:userTask>
            <bpmn:callActivity id="call" calledElement="other"/>
            <bpmn:endEvent id="end"/>
            """;

        OaBpmnValidationResult result = validate(document(process));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("extensionElements"));
        assertThat(result.errors()).anyMatch(error -> error.contains("callActivity"));
        assertThat(result.errors()).anyMatch(error -> error.contains("不允许的命名空间"));
    }

    @Test
    void rejectsServiceTaskUnlessHandlerAllowlisted() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:serviceTask id="notify" flowable:class="com.example.ApproveHandler"/>
            <bpmn:endEvent id="end"/>
            """;
        String xml = document(process);

        assertInvalid(xml, "服务任务处理器未在白名单中");

        OaBpmnValidator allowlisted = new OaBpmnValidator(Set.of("com.example.ApproveHandler"));
        assertThat(allowlisted.validate(xml.getBytes(StandardCharsets.UTF_8)).valid()).isTrue();
    }

    @Test
    void rejectsServiceTaskWithExpressionOrDelegate() {
        String expression = """
            <bpmn:startEvent id="start"/>
            <bpmn:serviceTask id="notify" flowable:expression="${evil}"/>
            <bpmn:endEvent id="end"/>
            """;
        String delegate = """
            <bpmn:startEvent id="start"/>
            <bpmn:serviceTask id="notify" flowable:delegateExpression="${evil}"/>
            <bpmn:endEvent id="end"/>
            """;

        assertInvalid(document(expression), "不允许属性 flowable:expression");
        assertInvalid(document(delegate), "不允许属性 flowable:delegateExpression");
    }

    @Test
    void rejectsUserTaskWithoutAssignment() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:userTask id="approve"/>
            <bpmn:endEvent id="end"/>
            """;

        assertInvalid(document(process), "必须配置审批人变量");
    }

    @Test
    void rejectsUnsafeCandidateExpression() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:userTask id="approve" flowable:candidateUsers="${T(java.lang.Runtime).getRuntime()}"/>
            <bpmn:endEvent id="end"/>
            """;

        assertInvalid(document(process), "只能使用简单变量表达式");
    }

    @Test
    void rejectsUnsafeConditionExpression() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:exclusiveGateway id="gateway"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="gateway"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="gateway" targetRef="end">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${T(java.lang.Runtime).getRuntime().exec('calc')}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            """;

        assertInvalid(document(process), "条件不安全");
    }

    @Test
    void rejectsConditionOutsideExclusiveGateway() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:parallelGateway id="gateway"/>
            <bpmn:endEvent id="end"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="gateway"/>
            <bpmn:sequenceFlow id="flow2" sourceRef="gateway" targetRef="end">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">${amount &gt; 1}</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            """;

        assertInvalid(document(process), "条件表达式只能用于排他网关");
    }

    @Test
    void rejectsBoundaryTimerOnNonUserTask() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:serviceTask id="notify" flowable:class="com.example.ApproveHandler"/>
            <bpmn:boundaryEvent id="remind" attachedToRef="notify">
              <bpmn:timerEventDefinition id="timer1">
                <bpmn:timeDuration>PT1H</bpmn:timeDuration>
              </bpmn:timerEventDefinition>
            </bpmn:boundaryEvent>
            <bpmn:endEvent id="end"/>
            """;
        OaBpmnValidator allowlisted = new OaBpmnValidator(Set.of("com.example.ApproveHandler"));

        OaBpmnValidationResult result = allowlisted.validate(document(process).getBytes(StandardCharsets.UTF_8));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("只能挂在用户任务上"));
    }

    @Test
    void rejectsMissingStartAndEnd() {
        assertInvalid(document("<bpmn:userTask id=\"approve\" flowable:assignee=\"1\"/>"), "缺少开始事件");
    }

    @Test
    void rejectsDuplicateIdsAndBrokenReferences() {
        String process = """
            <bpmn:startEvent id="start"/>
            <bpmn:endEvent id="start"/>
            <bpmn:sequenceFlow id="flow1" sourceRef="start" targetRef="missing"/>
            """;

        OaBpmnValidationResult result = validate(document(process));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("id 重复"));
        assertThat(result.errors()).anyMatch(error -> error.contains("目标节点无效"));
    }

    @Test
    void rejectsOversizedDocument() {
        String xml = document(SAFE_PROCESS).concat(" ".repeat(OaBpmnValidator.MAX_BYTES));

        assertInvalid(xml, "超过 512KB 限制");
    }

    @Test
    void rejectsTooManyFlowNodes() {
        StringBuilder process = new StringBuilder();
        for (int index = 0; index < OaBpmnValidator.MAX_FLOW_NODES + 5; index++) {
            process.append("<bpmn:startEvent id=\"start-").append(index).append("\"/>");
        }
        process.append("<bpmn:endEvent id=\"end\"/>");

        assertInvalid(document(process.toString()), "流程节点数量超过");
    }

    @Test
    void digestChangesWithContent() {
        String first = validate(document(SAFE_PROCESS)).digest();
        String second = validate(document(SAFE_PROCESS.replace("PT24H", "PT48H"))).digest();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    private static String document(String process) {
        return HEADER + """
            <bpmn:process id="leave" name="leave" isExecutable="true">
            """.concat(process).concat("""
              </bpmn:process>
            </bpmn:definitions>
            """);
    }

    private static OaBpmnValidationResult validate(String xml) {
        return VALIDATOR.validate(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(String xml, String fragment) {
        OaBpmnValidationResult result = validate(xml);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains(fragment));
    }
}
