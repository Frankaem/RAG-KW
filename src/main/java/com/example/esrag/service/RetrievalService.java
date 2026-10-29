package com.example.esrag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.enums.RetrievalMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一检索服务：把「向量检索 / 关键词检索 / RRF 混合检索」从 RAG 服务中抽出，
 * 供普通 RAG 问答、Agent 工具调用、检索评估三处复用。
 */
@Slf4j
@Service
public class RetrievalService {

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;

    /** 默认检索模式：vector | keyword | hybrid */
    private final RetrievalMode retrievalMode;
    private final int numCandidates;
    private final int rrfRankConstant;
    private final int rrfWindowSize;

    public RetrievalService(ElasticsearchClient esClient,
                            EmbeddingService embeddingService,
                            @Value("${rag.retrieval.mode:hybrid}") String retrievalMode,
                            @Value("${rag.retrieval.num-candidates:100}") int numCandidates,
                            @Value("${rag.retrieval.rrf.rank-constant:60}") int rrfRankConstant,
                            @Value("${rag.retrieval.rrf.window-size:50}") int rrfWindowSize) {
        this.esClient = esClient;
        this.embeddingService = embeddingService;
        this.retrievalMode = RetrievalMode.fromCode(retrievalMode);
        this.numCandidates = numCandidates;
        this.rrfRankConstant = rrfRankConstant;
        this.rrfWindowSize = rrfWindowSize;
    }

    /**
     * 按配置的默认模式检索（内部完成向量化）
     */
    public List<DocumentChunk> search(String question, int topK) {
        float[] queryVector = embeddingService.embed(question);
        return searchByMode(retrievalMode, queryVector, question, topK);
    }

    /**
     * 按指定模式检索（内部完成向量化），供评估模块调用
     */
    public List<DocumentChunk> searchByMode(RetrievalMode mode, String question, int topK) {
        float[] queryVector = embeddingService.embed(question);
        return searchByMode(mode, queryVector, question, topK);
    }

    /**
     * 按指定模式检索（复用已算好的问题向量，评估时一个提问只调一次 Embedding API）
     */
    public List<DocumentChunk> searchByMode(RetrievalMode mode, float[] queryVector, String question, int topK) {
        try {
            switch (mode) {
                case VECTOR:
                    return vectorSearch(queryVector, topK);
                case KEYWORD:
                    return keywordSearch(question, topK);
                case HYBRID:
                default:
                    return hybridSearchWithRRF(queryVector, question, topK);
            }
        } catch (IOException e) {
            log.error("ES 检索异常 | Mode: {}", mode, e);
            throw new RuntimeException("ES检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 纯向量检索：kNN 相似度搜索
     */
    private List<DocumentChunk> vectorSearch(float[] queryVector, int topK) throws IOException {
        List<Float> vectorList = new ArrayList<>();
        for (float v : queryVector) {
            vectorList.add(v);
        }

        SearchResponse<DocumentChunk> resp = esClient.search(s -> s
                        .index("doc_chunks")
                        .knn(k -> k
                                .field("vector")
                                .queryVector(vectorList)
                                .k((long) topK)
                                .numCandidates((long) numCandidates))
                        .source(src -> src.filter(f -> f.excludes("vector"))),
                DocumentChunk.class);

        return parseSearchHits(resp);
    }

    /**
     * 纯关键词检索：BM25（IK 中文分词）
     */
    private List<DocumentChunk> keywordSearch(String question, int topK) throws IOException {
        SearchResponse<DocumentChunk> resp = esClient.search(s -> s
                        .index("doc_chunks")
                        .query(q -> q
                                .multiMatch(m -> m
                                        .fields("content", "fileName")
                                        .query(question)
                                        .operator(Operator.Or)
                                        .analyzer("ik_smart")))
                        .size(topK)
                        .source(src -> src.filter(f -> f.excludes("vector"))),
                DocumentChunk.class);

        return parseSearchHits(resp);
    }

    /**
     * 混合检索：向量 + 关键词，使用 RRF（Reciprocal Rank Fusion）融合排序
     */
    private List<DocumentChunk> hybridSearchWithRRF(float[] queryVector, String question, int topK) throws IOException {
        List<Float> vectorList = new ArrayList<>();
        for (float v : queryVector) {
            vectorList.add(v);
        }

        SearchResponse<DocumentChunk> resp = esClient.search(s -> s
                        .index("doc_chunks")
                        // 向量检索部分
                        .knn(k -> k
                                .field("vector")
                                .queryVector(vectorList)
                                .k((long) topK)
                                .numCandidates((long) numCandidates))
                        // 关键词检索部分
                        .query(q -> q
                                .multiMatch(m -> m
                                        .fields("content", "fileName")
                                        .query(question)
                                        .operator(Operator.Or)
                                        .analyzer("ik_smart")))
                        // RRF 融合排序
                        .rank(r -> r
                                .rrf(rrf -> rrf
                                        .windowSize((long) rrfWindowSize)
                                        .rankConstant((long) rrfRankConstant)))
                        .size(topK)
                        .source(src -> src.filter(f -> f.excludes("vector"))),
                DocumentChunk.class);

        List<DocumentChunk> results = parseSearchHits(resp);

        log.info("混合检索命中 {} 条 | RRF窗口: {} | RRF常数: {}",
                results.size(), rrfWindowSize, rrfRankConstant);

        return results;
    }

    private List<DocumentChunk> parseSearchHits(SearchResponse<DocumentChunk> resp) {
        List<DocumentChunk> results = new ArrayList<>();
        for (Hit<DocumentChunk> hit : resp.hits().hits()) {
            if (hit.source() != null) {
                results.add(hit.source());
            }
        }
        return results;
    }
}
