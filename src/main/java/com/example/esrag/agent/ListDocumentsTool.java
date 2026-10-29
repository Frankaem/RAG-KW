package com.example.esrag.agent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具2：列出知识库文档
 * 帮助模型先"看看有什么"再决定检索哪份文档，是多步规划的基础能力。
 * 当前实现为元数据级 match_all（排除大字段），规模大后可换成 terms 聚合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListDocumentsTool implements AgentTool {

    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "list_documents";
    }

    @Override
    public String description() {
        return "列出知识库中已入库的所有文档名称，用于了解知识库范围、帮助决定检索哪份文档。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        try {
            SearchResponse<DocumentChunk> resp = esClient.search(s -> s
                            .index("doc_chunks")
                            .query(q -> q.matchAll(m -> m))
                            .size(500)
                            .source(src -> src.filter(f -> f.excludes("vector", "content"))),
                    DocumentChunk.class);

            Set<String> fileNames = new LinkedHashSet<>();
            resp.hits().hits().forEach(hit -> {
                if (hit.source() != null && hit.source().getFileName() != null) {
                    fileNames.add(hit.source().getFileName());
                }
            });
            return objectMapper.writeValueAsString(List.copyOf(fileNames));
        } catch (Exception e) {
            log.error("文档列表工具执行失败", e);
            return "{\"error\": \"获取文档列表失败: " + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}