package com.example.esrag.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent 问答结果：最终答案 + 工具调用轨迹 + 引用文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    private String answer;
    private int rounds;
    private List<ToolResult> toolTrace;
    private List<Map<String, Object>> referencedDocs;
}