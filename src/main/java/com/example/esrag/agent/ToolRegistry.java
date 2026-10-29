package com.example.esrag.agent;

import ai.z.openapi.service.model.ChatFunction;
import ai.z.openapi.service.model.ChatTool;
import ai.z.openapi.service.model.ChatToolType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表：自动收集所有 AgentTool Bean，负责两件事
 * 1. 把工具定义转换成模型能识别的 ChatTool（Function Calling 声明）
 * 2. 根据模型返回的工具名执行对应工具
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> toolList, ObjectMapper objectMapper) {
        this.tools = toolList.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity(), (a, b) -> a));
        this.objectMapper = objectMapper;
        log.info("Agent 工具注册完成: {}", tools.keySet());
    }

    /**
     * 把全部工具声明为 Function Calling 的 ChatTool
     */
    public List<ChatTool> toChatTools() {
        return tools.values().stream().map(tool -> ChatTool.builder()
                .type(ChatToolType.FUNCTION.value())
                .function(ChatFunction.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(objectMapper.valueToTree(tool.parametersSchema()))
                        .build())
                .build()).collect(Collectors.toList());
    }

    /**
     * 执行工具调用（arguments 是模型返回的 JsonNode）
     */
    public ToolResult execute(String name, JsonNode arguments) {
        long start = System.currentTimeMillis();
        String argsText = arguments == null ? "{}" : arguments.toString();
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return new ToolResult(name, argsText, "{\"error\": \"未知工具: " + name + "\"}", 0);
        }
        try {
            Map<String, Object> args = arguments == null
                    ? Map.of()
                    : objectMapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {});
            String result = tool.execute(args);
            return new ToolResult(name, argsText, result, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("工具执行异常: {}", name, e);
            return new ToolResult(name, argsText, "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}", System.currentTimeMillis() - start);
        }
    }
}