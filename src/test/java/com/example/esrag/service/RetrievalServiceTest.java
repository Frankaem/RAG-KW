package com.example.esrag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.enums.RetrievalMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 统一检索服务单元测试：三种模式分发 + 内部向量化
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private EmbeddingService embeddingService;

    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() throws IOException {
        retrievalService = new RetrievalService(esClient, embeddingService, "hybrid", 100, 60, 50);

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId("doc1_chunk_0");
        chunk.setContent("测试内容");
        chunk.setFileName("test.md");

        Hit<DocumentChunk> hit = mock(Hit.class);
        lenient().when(hit.source()).thenReturn(chunk);

        HitsMetadata<DocumentChunk> hits = mock(HitsMetadata.class);
        lenient().when(hits.hits()).thenReturn((List) List.of(hit));

        SearchResponse<DocumentChunk> resp = mock(SearchResponse.class);
        lenient().when(resp.hits()).thenReturn(hits);

        lenient().when(esClient.search(any(Function.class), eq(DocumentChunk.class))).thenReturn(resp);
    }

    @Test
    void searchByMode_vector_returnsHits() {
        List<DocumentChunk> results = retrievalService.searchByMode(RetrievalMode.VECTOR, new float[8], "查询", 5);
        assertEquals(1, results.size());
        assertEquals("test.md", results.get(0).getFileName());
    }

    @Test
    void searchByMode_keyword_returnsHits() {
        List<DocumentChunk> results = retrievalService.searchByMode(RetrievalMode.KEYWORD, new float[8], "查询", 5);
        assertEquals(1, results.size());
    }

    @Test
    void searchByMode_hybrid_returnsHits() {
        List<DocumentChunk> results = retrievalService.searchByMode(RetrievalMode.HYBRID, new float[8], "查询", 5);
        assertEquals(1, results.size());
    }

    @Test
    void search_embedsQuestionThenSearches() throws Exception {
        when(embeddingService.embed("问题")).thenReturn(new float[8]);
        List<DocumentChunk> results = retrievalService.search("问题", 5);
        assertEquals(1, results.size());
        verify(embeddingService, times(1)).embed("问题");
        verify(esClient, times(1)).search(any(Function.class), eq(DocumentChunk.class));
    }

    @Test
    void searchByMode_esError_wrapsToRuntimeException() throws Exception {
        when(esClient.search(any(Function.class), eq(DocumentChunk.class))).thenThrow(new IOException("ES down"));
        assertThrows(RuntimeException.class,
                () -> retrievalService.searchByMode(RetrievalMode.VECTOR, new float[8], "查询", 5));
    }
}