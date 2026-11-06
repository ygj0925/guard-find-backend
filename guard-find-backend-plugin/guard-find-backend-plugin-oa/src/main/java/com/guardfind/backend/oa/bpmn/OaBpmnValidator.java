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

package com.guardfind.backend.oa.bpmn;

import com.guardfind.backend.oa.staff.OaAssigneeSpec;
import com.guardfind.backend.oa.staff.OaAssigneeSpecParser;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BPMN 发布前安全校验：禁用 DTD/外部实体，只放行白名单节点与 Flowable 属性。
 *
 * <p>
 * 校验对象是待发布的原始 XML；通过后由发布流程保存 SHA-256 指纹再交给引擎部署。
 * 首版只允许用户任务、受控服务任务、排他/并行网关、定时边界事件与顺序流，
 * 条件只能是受限表达式，服务任务处理器必须显式配置白名单。
 * </p>
 */
public class OaBpmnValidator {

    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_ELEMENTS = 2000;
    public static final int MAX_FLOW_NODES = 200;
    private static final int MAX_DEPTH = 50;
    private static final int MAX_ERRORS = 40;

    static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String BPMNDI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";
    static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";
    static final String DI_NS = "http://www.omg.org/spec/DD/20100524/DI";
    static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    private static final Set<String> FLOW_NODE_TYPES = Set
        .of("startEvent", "endEvent", "userTask", "serviceTask", "exclusiveGateway", "parallelGateway", "boundaryEvent");

    private static final Set<String> ALLOWED_BPMN_ELEMENTS = allowedBpmnElements();

    private static final Map<String, Set<String>> ALLOWED_ATTRIBUTES = allowedAttributes();

    private static final Set<String> DIAGRAM_ELEMENTS = Set
        .of("BPMNDiagram", "BPMNPlane", "BPMNShape", "BPMNEdge", "BPMNLabel");
    private static final Set<String> SHAPE_ELEMENTS = Set.of("Bounds");
    private static final Set<String> WAYPOINT_ELEMENTS = Set.of("waypoint");

    private static final Set<String> TIMER_TYPES = Set.of("timeDuration", "timeDate", "timeCycle");

    private static final Pattern SIMPLE_EXPRESSION = Pattern
        .compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*}");
    private static final Pattern LITERAL_ASSIGNEE = Pattern.compile("[A-Za-z0-9_.,:\\-]+");
    private static final Pattern ISO_DURATION = Pattern.compile("P[0-9A-Z.]+");
    private static final Pattern ISO_CYCLE = Pattern.compile("R[0-9]*/[0-9A-Z./]+");
    private static final Pattern CRON = Pattern.compile("[0-9*?/,: \\-]+");
    private static final Pattern ISO_DATE = Pattern.compile("[0-9T:.+\\-Z]+");

    private final Set<String> allowedServiceHandlers;

    public OaBpmnValidator() {
        this(Set.of());
    }

    public OaBpmnValidator(Collection<String> allowedServiceHandlers) {
        Set<String> handlers = new LinkedHashSet<>();
        if (allowedServiceHandlers != null) {
            for (String handler : allowedServiceHandlers) {
                if (handler != null && !handler.isBlank()) {
                    handlers.add(handler.trim());
                }
            }
        }
        this.allowedServiceHandlers = Set.copyOf(handlers);
    }

    public OaBpmnValidationResult validate(byte[] xml) {
        if (xml == null || xml.length == 0) {
            return OaBpmnValidationResult.failure("BPMN XML 不能为空", null);
        }
        String digest = digest(xml);
        if (xml.length > MAX_BYTES) {
            return OaBpmnValidationResult.failure("BPMN XML 超过 " + (MAX_BYTES / 1024) + "KB 限制", digest);
        }
        String text = new String(xml, StandardCharsets.UTF_8);
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            return OaBpmnValidationResult.failure("BPMN XML 不允许 DTD 或实体声明", digest);
        }

        Document document;
        try {
            document = parse(xml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return OaBpmnValidationResult.failure("BPMN XML 解析失败: " + safeMessage(e), digest);
        }
        Element root = document.getDocumentElement();
        if (root == null) {
            return OaBpmnValidationResult.failure("BPMN XML 缺少根节点", digest);
        }

        List<String> errors = new ArrayList<>();
        List<Element> elements = new ArrayList<>();
        List<OaAssigneeSpec> assignees = new ArrayList<>();
        collect(root, 1, elements, errors);

        for (Element element : elements) {
            validateNamespace(element, errors);
            validateAttributes(element, errors);
        }

        Map<String, Element> nodes = new HashMap<>();
        Map<String, Element> flows = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (Element element : elements) {
            if (!isBpmn(element)) {
                continue;
            }
            String id = element.getAttribute("id");
            if (!id.isBlank() && !ids.add(id)) {
                addError(errors, "BPMN 节点 id 重复: " + id);
            }
            String local = localName(element);
            if (FLOW_NODE_TYPES.contains(local)) {
                if (id.isBlank()) {
                    addError(errors, "流程节点 " + local + " 缺少 id");
                } else {
                    nodes.put(id, element);
                }
            } else if ("sequenceFlow".equals(local)) {
                if (id.isBlank()) {
                    addError(errors, "顺序流缺少 id");
                } else {
                    flows.put(id, element);
                }
            }
        }
        if (nodes.size() > MAX_FLOW_NODES) {
            addError(errors, "流程节点数量超过 " + MAX_FLOW_NODES);
        }

        Element definitions = isBpmn(root) && "definitions".equals(localName(root)) ? root : null;
        if (definitions == null) {
            addError(errors, "根节点必须是 BPMN definitions");
        } else if (!BPMN_NS.equals(root.getNamespaceURI())) {
            addError(errors, "根节点必须使用 BPMN 2.0 命名空间");
        }

        Element process = definitions == null ? null : resolveProcess(definitions, errors);
        if (process != null && !"true".equals(process.getAttribute("isExecutable"))) {
            addError(errors, "流程必须标记为 isExecutable=true");
        }
        if (process != null) {
            validateProcessChildren(process, errors);
        }

        for (Element element : elements) {
            if (!isBpmn(element)) {
                continue;
            }
            validateElement(element, nodes, flows, errors, assignees);
        }

        if (nodes.values().stream().noneMatch(node -> "startEvent".equals(localName(node)))) {
            addError(errors, "流程缺少开始事件");
        }
        if (nodes.values().stream().noneMatch(node -> "endEvent".equals(localName(node)))) {
            addError(errors, "流程缺少结束事件");
        }

        String processKey = process == null ? null : blankToNull(process.getAttribute("id"));
        return new OaBpmnValidationResult(errors.isEmpty(), errors, nodes.size(), digest, processKey, assignees);
    }

    private Element resolveProcess(Element definitions, List<String> errors) {
        Element process = null;
        int count = 0;
        for (Element child : childElements(definitions)) {
            if (isBpmn(child) && "process".equals(localName(child))) {
                count++;
                if (process == null) {
                    process = child;
                }
                continue;
            }
            if (BPMNDI_NS.equals(child.getNamespaceURI()) && DIAGRAM_ELEMENTS.contains(localName(child))) {
                continue;
            }
            addError(errors, "definitions 下不允许节点: " + describe(child));
        }
        if (count != 1) {
            addError(errors, "必须且只能包含一个流程定义");
            return count == 1 ? process : null;
        }
        return process;
    }

    private static void validateProcessChildren(Element process, List<String> errors) {
        if (process.getAttribute("id").isBlank()) {
            addError(errors, "流程定义缺少 id");
        }
        for (Element child : childElements(process)) {
            if (!isBpmn(child)) {
                addError(errors, "流程下不允许节点: " + describe(child));
                continue;
            }
            String local = localName(child);
            if (!FLOW_NODE_TYPES.contains(local) && !"sequenceFlow".equals(local)) {
                addError(errors, "流程下不允许节点: " + local);
            }
        }
    }

    private void validateElement(Element element,
                                 Map<String, Element> nodes,
                                 Map<String, Element> flows,
                                 List<String> errors,
                                 List<OaAssigneeSpec> assignees) {
        String local = localName(element);
        String id = element.getAttribute("id");
        switch (local) {
            case "startEvent" -> validateBoundaryChildren(element, Set.of("outgoing"), "开始事件 " + id, errors);
            case "endEvent" -> validateBoundaryChildren(element, Set.of("incoming"), "结束事件 " + id, errors);
            case "userTask" -> {
                validateTaskChildren(element, errors);
                validateUserTask(element, id, errors, assignees);
            }
            case "serviceTask" -> {
                validateTaskChildren(element, errors);
                validateServiceTask(element, id, errors);
            }
            case "exclusiveGateway" -> {
                validateGatewayChildren(element, errors);
                validateExclusiveGateway(element, id, flows, errors);
            }
            case "parallelGateway" -> validateGatewayChildren(element, errors);
            case "boundaryEvent" -> validateBoundaryEvent(element, id, nodes, errors);
            case "sequenceFlow" -> validateSequenceFlow(element, id, nodes, errors);
            case "timerEventDefinition" -> validateTimer(element, id, errors);
            case "conditionExpression" -> {
                if (!childElements(element).isEmpty()) {
                    addError(errors, "条件表达式不允许子节点");
                }
            }
            case "incoming", "outgoing" -> validateFlowReference(element, local, flows, errors);
            default -> {
                // 其余白名单元素在属性与结构检查中覆盖
            }
        }
    }

    private void validateUserTask(Element element, String id, List<String> errors, List<OaAssigneeSpec> assignees) {
        String assignee = flowableAttribute(element, "assignee");
        String candidateUsers = flowableAttribute(element, "candidateUsers");
        String candidateGroups = flowableAttribute(element, "candidateGroups");
        if (assignee.isBlank() && candidateUsers.isBlank() && candidateGroups.isBlank()) {
            addError(errors, "用户任务 " + id + " 必须配置审批人变量或候选范围");
            return;
        }
        validateAssignment("assignee", assignee, id, errors);
        validateAssignment("candidateUsers", candidateUsers, id, errors);
        validateAssignment("candidateGroups", candidateGroups, id, errors);
        collectAssignments("assignee", assignee, id, assignees, errors);
        collectAssignments("candidateUsers", candidateUsers, id, assignees, errors);
        collectAssignments("candidateGroups", candidateGroups, id, assignees, errors);
    }

    private void collectAssignments(String name,
                                    String value,
                                    String id,
                                    List<OaAssigneeSpec> assignees,
                                    List<String> errors) {
        if (value.isBlank() || value.contains("${")) {
            return;
        }
        for (String token : value.split(",")) {
            try {
                OaAssigneeSpecParser.parse(token).ifPresent(assignees::add);
            } catch (IllegalArgumentException e) {
                addError(errors, "用户任务 " + id + " 的 " + name + " 配置无效: " + e.getMessage());
            }
        }
    }

    private void validateServiceTask(Element element, String id, List<String> errors) {
        String handler = flowableAttribute(element, "class");
        if (handler.isBlank()) {
            addError(errors, "服务任务 " + id + " 必须配置 flowable:class");
            return;
        }
        if (!allowedServiceHandlers.contains(handler.trim())) {
            addError(errors, "服务任务处理器未在白名单中: " + handler.trim());
        }
    }

    private void validateAssignment(String name, String value, String id, List<String> errors) {
        if (value.isBlank()) {
            return;
        }
        if (value.contains("${")) {
            if (!SIMPLE_EXPRESSION.matcher(value).matches()) {
                addError(errors, "用户任务 " + id + " 的 " + name + " 只能使用简单变量表达式");
            }
            return;
        }
        if (!LITERAL_ASSIGNEE.matcher(value).matches()) {
            addError(errors, "用户任务 " + id + " 的 " + name + " 含有非法字符");
        }
    }

    private void validateExclusiveGateway(Element element, String id, Map<String, Element> flows, List<String> errors) {
        String defaultRef = element.getAttribute("default");
        List<String> outgoing = childTexts(element, "outgoing");
        if (!defaultRef.isBlank()) {
            Element flow = flows.get(defaultRef);
            if (flow == null || !id.equals(flow.getAttribute("sourceRef"))) {
                addError(errors, "排他网关 " + id + " 的默认连线无效");
            } else if (childElements(flow).stream()
                .anyMatch(child -> isBpmn(child) && "conditionExpression".equals(localName(child)))) {
                addError(errors, "排他网关 " + id + " 的默认连线 " + defaultRef + " 不能配置条件");
            }
        }
        if (outgoing.size() > 1) {
            for (String ref : outgoing) {
                if (ref.equals(defaultRef)) {
                    continue;
                }
                Element flow = flows.get(ref);
                if (flow == null) {
                    continue;
                }
                if (childElements(flow).stream()
                    .noneMatch(child -> isBpmn(child) && "conditionExpression".equals(localName(child)))) {
                    addError(errors, "排他网关 " + id + " 的输出连线 " + ref + " 缺少条件");
                }
            }
        }
    }

    private void validateBoundaryEvent(Element element, String id, Map<String, Element> nodes, List<String> errors) {
        String attachedTo = element.getAttribute("attachedToRef");
        if (attachedTo.isBlank()) {
            addError(errors, "边界事件 " + id + " 缺少 attachedToRef");
        } else {
            Element target = nodes.get(attachedTo);
            if (target == null || !"userTask".equals(localName(target))) {
                addError(errors, "边界定时事件只能挂在用户任务上");
            }
        }
        String cancelActivity = element.getAttribute("cancelActivity");
        if (!cancelActivity.isBlank() && !"true".equals(cancelActivity) && !"false".equals(cancelActivity)) {
            addError(errors, "边界事件 " + id + " 的 cancelActivity 只能是 true 或 false");
        }
        int timers = 0;
        for (Element child : childElements(element)) {
            if (!isBpmn(child)) {
                addError(errors, "边界事件 " + id + " 不允许子节点: " + describe(child));
                continue;
            }
            String childName = localName(child);
            if ("timerEventDefinition".equals(childName)) {
                timers++;
            } else if (!"outgoing".equals(childName)) {
                addError(errors, "边界事件 " + id + " 不允许子节点: " + childName);
            }
        }
        if (timers != 1) {
            addError(errors, "边界事件 " + id + " 必须且只能包含一个定时器");
        }
    }

    private void validateSequenceFlow(Element element, String id, Map<String, Element> nodes, List<String> errors) {
        String sourceRef = element.getAttribute("sourceRef");
        String targetRef = element.getAttribute("targetRef");
        Element source = nodes.get(sourceRef);
        Element target = nodes.get(targetRef);
        if (sourceRef.isBlank() || source == null) {
            addError(errors, "顺序流 " + id + " 的来源节点无效");
        }
        if (targetRef.isBlank() || target == null) {
            addError(errors, "顺序流 " + id + " 的目标节点无效");
        }
        if (target != null && "boundaryEvent".equals(localName(target))) {
            addError(errors, "顺序流 " + id + " 不能指向边界事件");
        }
        if (target != null && "startEvent".equals(localName(target))) {
            addError(errors, "顺序流 " + id + " 不能指向开始事件");
        }
        if (source != null && "endEvent".equals(localName(source))) {
            addError(errors, "结束事件不能有输出连线");
        }
        List<Element> conditions = new ArrayList<>();
        for (Element child : childElements(element)) {
            if (isBpmn(child) && "conditionExpression".equals(localName(child))) {
                conditions.add(child);
            } else {
                addError(errors, "顺序流 " + id + " 不允许子节点: " + describe(child));
            }
        }
        if (conditions.size() > 1) {
            addError(errors, "顺序流 " + id + " 只能有一个条件表达式");
        }
        for (Element condition : conditions) {
            if (source == null || !"exclusiveGateway".equals(localName(source))) {
                addError(errors, "条件表达式只能用于排他网关的输出连线");
            }
            String expression = condition.getTextContent().trim();
            try {
                OaConditionExpressionValidator.requireSafe(expression);
            } catch (IllegalArgumentException e) {
                addError(errors, "顺序流 " + id + " 条件不安全: " + e.getMessage());
            }
        }
    }

    private void validateTimer(Element element, String id, List<String> errors) {
        List<Element> children = childElements(element);
        if (children.size() != 1 || !isBpmn(children.get(0)) || !TIMER_TYPES.contains(localName(children.get(0)))) {
            addError(errors, "定时器 " + id + " 必须且只能包含一个时间设置");
            return;
        }
        Element timer = children.get(0);
        if (!childElements(timer).isEmpty()) {
            addError(errors, "定时器时间设置不允许子节点");
        }
        String value = timer.getTextContent().trim();
        if (value.isBlank()) {
            addError(errors, "定时器 " + id + " 的时间设置不能为空");
            return;
        }
        if (value.contains("${")) {
            if (!SIMPLE_EXPRESSION.matcher(value).matches()) {
                addError(errors, "定时器 " + id + " 只能使用简单变量表达式");
            }
            return;
        }
        boolean valid = switch (localName(timer)) {
            case "timeDuration" -> ISO_DURATION.matcher(value).matches();
            case "timeCycle" -> ISO_CYCLE.matcher(value).matches() || CRON.matcher(value).matches();
            default -> ISO_DATE.matcher(value).matches();
        };
        if (!valid) {
            addError(errors, "定时器 " + id + " 的时间格式非法");
        }
    }

    private static void validateFlowReference(Element element,
                                              String local,
                                              Map<String, Element> flows,
                                              List<String> errors) {
        Node parentNode = element.getParentNode();
        if (!(parentNode instanceof Element parent) || !isBpmn(parent)) {
            addError(errors, local + " 必须位于流程节点内");
            return;
        }
        String parentId = parent.getAttribute("id");
        String ref = element.getTextContent().trim();
        Element flow = flows.get(ref);
        if (ref.isBlank() || flow == null) {
            addError(errors, local + " 引用的顺序流不存在: " + ref);
            return;
        }
        if ("incoming".equals(local)) {
            if (!parentId.equals(flow.getAttribute("targetRef"))) {
                addError(errors, "incoming 引用与顺序流目标不一致: " + ref);
            }
        } else if (!parentId.equals(flow.getAttribute("sourceRef"))) {
            addError(errors, "outgoing 引用与顺序流来源不一致: " + ref);
        }
    }

    private static void validateBoundaryChildren(Element element,
                                                 Set<String> allowed,
                                                 String label,
                                                 List<String> errors) {
        for (Element child : childElements(element)) {
            if (!isBpmn(child) || !allowed.contains(localName(child))) {
                addError(errors, label + " 不允许子节点: " + describe(child));
            }
        }
    }

    private static void validateTaskChildren(Element element, List<String> errors) {
        validateBoundaryChildren(element, Set.of("incoming", "outgoing"), "任务 " + element.getAttribute("id"), errors);
    }

    private static void validateGatewayChildren(Element element, List<String> errors) {
        validateBoundaryChildren(element, Set.of("incoming", "outgoing"), "网关 " + element.getAttribute("id"), errors);
    }

    private void validateNamespace(Element element, List<String> errors) {
        String namespace = element.getNamespaceURI();
        String local = localName(element);
        if (BPMN_NS.equals(namespace)) {
            if (!ALLOWED_BPMN_ELEMENTS.contains(local)) {
                addError(errors, "不允许的 BPMN 节点: " + local);
            }
            return;
        }
        if (BPMNDI_NS.equals(namespace)) {
            if (!DIAGRAM_ELEMENTS.contains(local)) {
                addError(errors, "不允许的 BPMN DI 节点: " + local);
            }
            return;
        }
        if (DC_NS.equals(namespace)) {
            if (!SHAPE_ELEMENTS.contains(local)) {
                addError(errors, "不允许的图形节点: " + local);
            }
            return;
        }
        if (DI_NS.equals(namespace)) {
            if (!WAYPOINT_ELEMENTS.contains(local)) {
                addError(errors, "不允许的图形节点: " + local);
            }
            return;
        }
        addError(errors, "不允许的命名空间: " + namespace + " (" + describe(element) + ")");
    }

    private void validateAttributes(Element element, List<String> errors) {
        String namespace = element.getNamespaceURI();
        if (!BPMN_NS.equals(namespace)) {
            validateDiagramAttributes(element, errors);
            return;
        }
        String local = localName(element);
        Set<String> allowed = ALLOWED_ATTRIBUTES.get(local);
        if (allowed == null) {
            return;
        }
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Attr attr = (Attr)element.getAttributes().item(i);
            String attrNs = attr.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attrNs)) {
                continue;
            }
            String attrName = attrLocalName(attr);
            if (attrNs == null || attrNs.isEmpty()) {
                if (!allowed.contains(attrName)) {
                    addError(errors, "节点 " + local + " 不允许属性 " + attrName);
                }
                continue;
            }
            if (XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(attrNs)) {
                if ("definitions".equals(local) && "schemaLocation".equals(attrName)) {
                    continue;
                }
                if (("conditionExpression".equals(local) || TIMER_TYPES.contains(local)) && "type"
                    .equals(attrName) && isFormalExpression(attr.getValue())) {
                    continue;
                }
                addError(errors, "节点 " + local + " 不允许属性 xsi:" + attrName);
                continue;
            }
            if (FLOWABLE_NS.equals(attrNs)) {
                if (!isAllowedFlowableAttribute(local, attrName)) {
                    addError(errors, "节点 " + local + " 不允许属性 flowable:" + attrName);
                }
                continue;
            }
            addError(errors, "节点 " + local + " 不允许扩展属性 " + attrName);
        }
    }

    private static void validateDiagramAttributes(Element element, List<String> errors) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Attr attr = (Attr)element.getAttributes().item(i);
            String attrNs = attr.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attrNs) || attrNs == null || attrNs.isEmpty()) {
                continue;
            }
            addError(errors, "图形节点 " + localName(element) + " 不允许扩展属性 " + attrLocalName(attr));
        }
    }

    private static boolean isAllowedFlowableAttribute(String local, String attrName) {
        return switch (local) {
            case "userTask" -> Set.of("assignee", "candidateUsers", "candidateGroups").contains(attrName);
            case "serviceTask" -> "class".equals(attrName);
            default -> false;
        };
    }

    private static boolean isFormalExpression(String type) {
        return "bpmn:tFormalExpression".equals(type) || "tFormalExpression".equals(type);
    }

    private static void collect(Element element, int depth, List<Element> elements, List<String> errors) {
        if (elements.size() >= MAX_ELEMENTS) {
            addError(errors, "BPMN 元素数量超过 " + MAX_ELEMENTS);
            return;
        }
        if (depth > MAX_DEPTH) {
            addError(errors, "BPMN XML 嵌套层级超过 " + MAX_DEPTH);
            return;
        }
        elements.add(element);
        for (Element child : childElements(element)) {
            collect(child, depth + 1, elements, errors);
        }
    }

    private static Document parse(byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("不允许解析外部实体");
        });
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException e) throws SAXException {
                throw e;
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        InputSource source = new InputSource(new ByteArrayInputStream(xml));
        source.setSystemId("oa-bpmn.xml");
        return builder.parse(source);
    }

    private static String digest(byte[] xml) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(xml);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private static List<Element> childElements(Element element) {
        List<Element> children = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element)child);
            }
        }
        return children;
    }

    private static List<String> childTexts(Element element, String name) {
        List<String> values = new ArrayList<>();
        for (Element child : childElements(element)) {
            if (isBpmn(child) && name.equals(localName(child))) {
                values.add(child.getTextContent().trim());
            }
        }
        return values;
    }

    private static boolean isBpmn(Element element) {
        return BPMN_NS.equals(element.getNamespaceURI());
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        return local == null ? element.getTagName() : local;
    }

    private static String attrLocalName(Attr attr) {
        String local = attr.getLocalName();
        return local == null ? attr.getName() : local;
    }

    private static String describe(Element element) {
        return localName(element) + (element.getAttribute("id").isBlank()
            ? ""
            : "[" + element.getAttribute("id") + "]");
    }

    private static String flowableAttribute(Element element, String name) {
        return element.getAttributeNS(FLOWABLE_NS, name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        } else if (errors.size() == MAX_ERRORS) {
            errors.add("错误过多，其余已省略");
        }
    }

    private static Set<String> allowedBpmnElements() {
        Set<String> elements = new HashSet<>(FLOW_NODE_TYPES);
        elements.addAll(Set
            .of("definitions", "process", "sequenceFlow", "conditionExpression", "timerEventDefinition", "timeDuration", "timeDate", "timeCycle", "incoming", "outgoing"));
        return Set.copyOf(elements);
    }

    private static Map<String, Set<String>> allowedAttributes() {
        Map<String, Set<String>> attributes = new HashMap<>();
        attributes.put("definitions", Set.of("id", "targetNamespace", "name", "exporter", "exporterVersion"));
        attributes.put("process", Set.of("id", "name", "isExecutable"));
        attributes.put("startEvent", Set.of("id", "name"));
        attributes.put("endEvent", Set.of("id", "name"));
        attributes.put("userTask", Set.of("id", "name"));
        attributes.put("serviceTask", Set.of("id", "name"));
        attributes.put("exclusiveGateway", Set.of("id", "name", "default"));
        attributes.put("parallelGateway", Set.of("id", "name"));
        attributes.put("boundaryEvent", Set.of("id", "name", "attachedToRef", "cancelActivity"));
        attributes.put("sequenceFlow", Set.of("id", "name", "sourceRef", "targetRef"));
        attributes.put("conditionExpression", Set.of("id", "name"));
        attributes.put("timerEventDefinition", Set.of("id"));
        attributes.put("timeDuration", Set.of("id"));
        attributes.put("timeDate", Set.of("id"));
        attributes.put("timeCycle", Set.of("id"));
        attributes.put("incoming", Set.of());
        attributes.put("outgoing", Set.of());
        return Map.copyOf(attributes);
    }
}
