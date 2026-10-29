package com.example.esrag.service;

import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.enums.RetrievalMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索模式集成测试（需要真实的 Elasticsearch + Embedding API 才能跑出结果）
 */
@SpringBootTest
public class HybridSearchTest {

    @Autowired
    private RetrievalService retrievalService;

    @Test
    public void testVectorSearch() throws Exception {
        List<DocumentChunk> results = retrievalService.searchByMode(RetrievalMode.VECTOR, "Spring Boot配置文件", 5);
        assertNotNull(results);
        System.out.println("纯向量检索结果数量: " + results.size());
        printResults("纯向量检索", results);
    }

    @Test
    public void testKeywordSearch() throws Exception {
        List<DocumentChunk> results = retrievalService.searchByMode(RetrievalMode.KEYWORD, "Spring Boot配置文件", 5);
        assertNotNull(results);
        System.out.println("纯关键词检索结果数量: " + results.size());
        printResults("纯关键词检索", results);
    }

    @Test
    public void testHybridSearch() throws Exception {
        List<DocumentChunk> results = retrievalService.searchByMode(RetrievalMode.HYBRID, "Spring Boot配置文件", 5);
        assertNotNull(results);
        System.out.println("混合检索结果数量: " + results.size());
        printResults("混合检索", results);
    }

    private void printResults(String mode, List<DocumentChunk> results) {
        for (int i = 0; i < results.size(); i++) {
            DocumentChunk doc = results.get(i);
            System.out.println(String.format("%d. [%s] %s...",
                i + 1,
                doc.getFileName(),
                doc.getContent().substring(0, Math.min(80, doc.getContent().length()))
            ));
        }
        System.out.println("---");
    }
}