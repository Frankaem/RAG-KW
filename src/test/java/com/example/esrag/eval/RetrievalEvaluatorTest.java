package com.example.esrag.eval;

import com.example.esrag.ChunkRepository;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.enums.RetrievalMode;
import com.example.esrag.service.EmbeddingService;
import com.example.esrag.service.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 评估器单元测试（不依赖真实 ES/Embedding）：
 * mock 检索结果 = 包含问题首关键词的演示 chunk，
 * 因此每个用例都应命中，Recall 接近 1、HitRate = 1。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalEvaluatorTest {

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChunkRepository chunkRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runEvaluation_computesMetricsAndCleansDemoChunks() throws Exception {
        List<DocumentChunk> savedChunks = new ArrayList<>();
        when(chunkRepository.saveAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<DocumentChunk> list = inv.getArgument(0);
            savedChunks.addAll(list);
            return list;
        });
        when(embeddingService.embedBatch(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[512]).toList();
        });
        when(embeddingService.embed(anyString())).thenReturn(new float[512]);

        // 模拟检索：返回"包含问题首关键词"的 chunk，等价于理想检索器
        when(retrievalService.searchByMode(any(RetrievalMode.class), any(float[].class), anyString(), anyInt()))
                .thenAnswer(inv -> {
                    String question = inv.getArgument(2);
                    List<RetrievalTestCase> cases = loadCases();
                    String firstKeyword = cases.stream()
                            .filter(c -> c.getQuestion().equals(question))
                            .flatMap(c -> c.getExpectedKeywords().stream())
                            .findFirst()
                            .orElse(question);
                    return savedChunks.stream()
                            .filter(c -> c.getContent().contains(firstKeyword))
                            .toList();
                });

        RetrievalEvaluator evaluator = new RetrievalEvaluator(
                retrievalService, embeddingService, chunkRepository, objectMapper,
                5, "classpath:eval/retrieval-testset.json",
                "classpath:eval/demo-knowledge.md", true, "vector,keyword,hybrid");

        RetrievalEvaluationReport report = evaluator.runEvaluation();

        assertNotNull(report);
        assertEquals(3, report.getModeResults().size());
        assertEquals(12, report.getTestCaseCount());

        for (EvalModeResult modeResult : report.getModeResults()) {
            assertEquals(12, modeResult.getTestCaseCount());
            assertTrue(modeResult.getAvgHitRate() >= 0.99, modeResult.getMode() + " HitRate 应接近 1");
            assertTrue(modeResult.getAvgRecall() >= 0.99, modeResult.getMode() + " Recall 应接近 1");
            assertTrue(modeResult.getAvgMrr() > 0, modeResult.getMode() + " MRR 应大于 0");
        }

        // 演示 chunk 应使用 eval_demo_ 前缀并在评估后清理
        assertTrue(savedChunks.stream().allMatch(c -> c.getId().startsWith("eval_demo_")));
        verify(chunkRepository, times(1)).saveAll(any());
        verify(chunkRepository, times(1)).deleteAll(anyIterable());
    }

    private List<RetrievalTestCase> loadCases() throws Exception {
        String json = new String(getClass().getClassLoader()
                .getResourceAsStream("eval/retrieval-testset.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<RetrievalTestCase>>() {});
    }
}