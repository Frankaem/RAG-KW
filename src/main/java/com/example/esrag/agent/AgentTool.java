package com.example.esrag.agent;

import java.util.Map;

/**
 * Agent 工具抽象：一个工具 = 名字 + 描述 + 参数 JSON Schema + 执行逻辑。
 * 新增工具只需实现该接口并注册为 Spring Bean，ToolRegistry 会自动收集。
 */
public interface AgentTool {

    /** 工具名（模型调用时使用，建议 snake_case） */
    String name();

    /** 工具描述（决定模型何时调用，越具体越好） */
    String description();

    /** 参数 JSON Schema：{ "type":"object", "properties": {...}, "required": [...] } */
    Map<String, Object> parametersSchema();

    /** 执行工具，返回给模型的 JSON 字符串结果 */
    String execute(Map<String, Object> arguments);
}