package com.example.esrag.agent;

import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.service.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具1：知识库检索（复用统一 RetrievalService，即混合检索 + RRF）
 * 这是把普通 RAG 升级为 Agentic RAG 的关键：
 * 检索不再是每次必做的固定步骤，而是模型按需决定是否调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSearchTool implements AgentTool {

    private static final int MAX_CONTENT_PREVIEW = 400;

    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "knowledge_base_search";
    }

    @Override
    public String description() {
        return "在知识库中检索与问题相关的文档片段。当用户问题需要参考知识库资料时调用，返回文件名称、片段序号和内容摘要。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "检索问题或关键词");

        Map<String, Object> topKProp = new LinkedHashMap<>();
        topKProp.put("type", "integer");
        topKProp.put("description", "返回片段数量，1-10，默认5");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProp);
        properties.put("top_k", topKProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            String query = arguments.get("query") == null ? "" : String.valueOf(arguments.get("query"));
            if (query.isBlank()) {
                return errorJson("query 参数不能为空");
            }
            int topK = parseInt(arguments.get("top_k"), 5, 1, 10);

            List<DocumentChunk> hits = retrievalService.search(query, topK);
            List<Map<String, Object>> items = hits.stream().map(chunk -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", chunk.getId());
                item.put("fileName", chunk.getFileName());
                item.put("chunkIndex", chunk.getChunkIndex());
                item.put("contentPreview", truncate(chunk.getContent(), MAX_CONTENT_PREVIEW));
                return item;
            }).toList();
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.error("知识库检索工具执行失败", e);
            return errorJson("检索失败: " + e.getMessage());
        }
    }

    private int parseInt(Object value, int defaultValue, int min, int max) {
        try {
            int v = value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String errorJson(String message) {
        return "{\"error\": \"" + message.replace("\"", "'") + "\"}";
    }
}