package com.example.esrag.eval;

import com.example.esrag.ChunkRepository;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.enums.RetrievalMode;
import com.example.esrag.service.EmbeddingService;
import com.example.esrag.service.RetrievalService;
import com.example.esrag.utils.TextSplitter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索评估器：
 * 1. 把演示知识库切片后写入 ES（id 前缀 eval_demo_，评估完自动清理）
 * 2. 对每条测试题按 vector / keyword / hybrid 三种模式检索
 * 3. 计算 Recall@k、HitRate@k、MRR，输出可直接贴进 README 的对比表
 *
 * 面试要点：评估数据证明「混合检索 + RRF 优于单一检索」，而不是口头宣称。
 */
@Slf4j
@Service
public class RetrievalEvaluator {

    private static final String DEMO_ID_PREFIX = "eval_demo_";

    private final RetrievalService retrievalService;
    private final EmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;
    private final int topK;
    private final String testsetPath;
    private final String demoDocumentPath;
    private final boolean cleanupDemo;
    private final List<RetrievalMode> modes;

    public RetrievalEvaluator(RetrievalService retrievalService,
                              EmbeddingService embeddingService,
                              ChunkRepository chunkRepository,
                              ObjectMapper objectMapper,
                              @Value("${eval.top-k:5}") int topK,
                              @Value("${eval.testset:classpath:eval/retrieval-testset.json}") String testsetPath,
                              @Value("${eval.demo-document:classpath:eval/demo-knowledge.md}") String demoDocumentPath,
                              @Value("${eval.cleanup-demo:true}") boolean cleanupDemo,
                              @Value("${eval.modes:vector,keyword,hybrid}") String modesConfig) {
        this.retrievalService = retrievalService;
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
        this.objectMapper = objectMapper;
        this.topK = topK;
        this.testsetPath = testsetPath;
        this.demoDocumentPath = demoDocumentPath;
        this.cleanupDemo = cleanupDemo;
        this.modes = Arrays.stream(modesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(RetrievalMode::fromCode)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 运行完整评估，返回报告（含 Markdown 对比表）
     */
    public RetrievalEvaluationReport runEvaluation() throws IOException {
        List<DocumentChunk> demoChunks = indexDemoDocument();
        log.info("演示知识库切片完成，共 {} 个 chunk | 测试集 topK={}", demoChunks.size(), topK);
        try {
            List<RetrievalTestCase> testCases = loadTestCases();
            Map<String, DocumentChunk> chunksById = demoChunks.stream()
                    .collect(Collectors.toMap(DocumentChunk::getId, c -> c, (a, b) -> a));

            // 每个测试问题只做一次向量化，三种模式复用，省 2/3 的 Embedding API 调用
            List<EvalModeResult> modeResults = new ArrayList<>();
            for (RetrievalMode mode : modes) {
                modeResults.add(evaluateMode(mode, testCases, chunksById));
            }

            return RetrievalEvaluationReport.builder()
                    .topK(topK)
                    .testCaseCount(testCases.size())
                    .demoDocumentName(demoDocumentPath)
                    .generatedAt(LocalDateTime.now())
                    .modeResults(modeResults)
                    .markdownTable(buildMarkdownTable(modeResults))
                    .build();
        } finally {
            if (cleanupDemo && !demoChunks.isEmpty()) {
                chunkRepository.deleteAll(demoChunks);
                log.info("评估完成，已清理 {} 个演示 chunk", demoChunks.size());
            }
        }
    }

    /**
     * 评估单个检索模式在整个测试集上的表现
     */
    private EvalModeResult evaluateMode(RetrievalMode mode,
                                        List<RetrievalTestCase> testCases,
                                        Map<String, DocumentChunk> chunksById) {
        List<CaseEvaluation> caseEvaluations = new ArrayList<>();
        double recallSum = 0, hitSum = 0, rrSum = 0, latencySum = 0;
        int validCases = 0;

        for (RetrievalTestCase tc : testCases) {
            List<String> expectedIds = resolveExpectedIds(tc, chunksById);
            if (expectedIds.isEmpty()) {
                log.warn("测试用例 [{}] 未解析出标准答案 chunk，已跳过", tc.getId());
                continue;
            }

            float[] queryVector = embeddingService.embed(tc.getQuestion());
            long start = System.currentTimeMillis();
            List<DocumentChunk> hits = retrievalService.searchByMode(mode, queryVector, tc.getQuestion(), topK);
            long latency = System.currentTimeMillis() - start;

            List<String> retrievedIds = hits.stream()
                    .map(DocumentChunk::getId)
                    .collect(Collectors.toList());

            CaseEvaluation eval = evaluateCase(tc, expectedIds, retrievedIds);
            caseEvaluations.add(eval);
            recallSum += eval.getRecall();
            hitSum += eval.isHit() ? 1 : 0;
            rrSum += eval.getReciprocalRank();
            latencySum += latency;
            validCases++;
        }

        double count = Math.max(validCases, 1);
        return EvalModeResult.builder()
                .mode(mode.getCode())
                .testCaseCount(validCases)
                .avgRecall(recallSum / count)
                .avgHitRate(hitSum / count)
                .avgMrr(rrSum / count)
                .avgLatencyMs(latencySum / count)
                .cases(caseEvaluations)
                .build();
    }

    /**
     * 单条用例指标：Hit / Recall / 倒数排名
     */
    private CaseEvaluation evaluateCase(RetrievalTestCase tc,
                                        List<String> expectedIds,
                                        List<String> retrievedIds) {
        Set<String> expectedSet = new HashSet<>(expectedIds);
        int matched = 0;
        double reciprocalRank = 0;
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (expectedSet.contains(retrievedIds.get(i))) {
                matched++;
                if (reciprocalRank == 0) {
                    reciprocalRank = 1.0 / (i + 1);
                }
            }
        }
        boolean hit = matched > 0;
        double recall = expectedIds.isEmpty() ? 0 : (double) matched / expectedIds.size();
        return CaseEvaluation.builder()
                .caseId(tc.getId())
                .question(tc.getQuestion())
                .expectedChunkIds(expectedIds)
                .retrievedChunkIds(retrievedIds)
                .hit(hit)
                .recall(recall)
                .reciprocalRank(reciprocalRank)
                .build();
    }

    /**
     * 解析标准答案 chunk：
     * 优先用 expectedChunkIds；否则用 expectedKeywords 求「同时包含所有关键词」的 chunk 交集，
     * 这样分块策略调整后测试集依然有效。
     */
    private List<String> resolveExpectedIds(RetrievalTestCase tc, Map<String, DocumentChunk> chunksById) {
        if (tc.getExpectedChunkIds() != null && !tc.getExpectedChunkIds().isEmpty()) {
            return tc.getExpectedChunkIds();
        }
        if (tc.getExpectedKeywords() == null || tc.getExpectedKeywords().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> candidates = null;
        for (String keyword : tc.getExpectedKeywords()) {
            Set<String> matched = chunksById.values().stream()
                    .filter(c -> c.getContent() != null && c.getContent().contains(keyword))
                    .map(DocumentChunk::getId)
                    .collect(Collectors.toSet());
            if (candidates == null) {
                candidates = matched;
            } else {
                candidates.retainAll(matched);
            }
            if (candidates.isEmpty()) {
                break;
            }
        }
        return candidates == null ? Collections.emptyList() : new ArrayList<>(candidates);
    }

    /**
     * 加载演示知识库 -> 分块 -> 向量化 -> 写入 ES（确定性 id，便于标准答案匹配与清理）
     */
    private List<DocumentChunk> indexDemoDocument() throws IOException {
        String demoText = loadResource(demoDocumentPath);
        // 统一换行符，避免 Windows CRLF 导致段落级分割失效
        demoText = demoText.replace("\r\n", "\n");
        List<String> chunks = TextSplitter.split(demoText);
        List<float[]> vectors = embeddingService.embedBatch(chunks);

        List<DocumentChunk> docs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk doc = new DocumentChunk();
            doc.setId(DEMO_ID_PREFIX + i);
            doc.setContent(chunks.get(i));
            doc.setVector(vectors.get(i));
            doc.setFileName("RAG-KW演示知识库.md");
            doc.setFileType("md");
            doc.setChunkIndex(i);
            doc.setTotalChunks(chunks.size());
            doc.setUploadTime(LocalDateTime.now());
            docs.add(doc);
        }
        chunkRepository.saveAll(docs);
        return docs;
    }

    private List<RetrievalTestCase> loadTestCases() throws IOException {
        String json = loadResource(testsetPath);
        return objectMapper.readValue(json, new TypeReference<List<RetrievalTestCase>>() {});
    }

    private String loadResource(String path) throws IOException {
        DefaultResourceLoader loader = new DefaultResourceLoader();
        try (InputStream in = loader.getResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String buildMarkdownTable(List<EvalModeResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 检索模式 | 用例数 | Recall@").append(topK).append(" | HitRate@").append(topK).append(" | MRR | 平均耗时 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (EvalModeResult r : results) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f | %.2f | %.1fms |%n",
                    RetrievalMode.fromCode(r.getMode()).getDescription(),
                    r.getTestCaseCount(),
                    r.getAvgRecall(),
                    r.getAvgHitRate(),
                    r.getAvgMrr(),
                    r.getAvgLatencyMs()));
        }
        return sb.toString();
    }
}