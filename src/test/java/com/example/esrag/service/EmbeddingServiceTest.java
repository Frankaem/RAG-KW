package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;
import ai.z.openapi.service.embedding.EmbeddingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock
    private ZhipuAiClient zhipuAiClient;

    @Mock
    private ai.z.openapi.service.embedding.EmbeddingService embeddingServiceClient;

    // 【修复】不使用 @InjectMocks，手动创建实例
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        // 手动创建 EmbeddingService 实例
        embeddingService = new EmbeddingService(zhipuAiClient, "embedding-3", 512);
        
        lenient().when(zhipuAiClient.embeddings()).thenReturn(embeddingServiceClient);
    }

    @Test
    void testEmbed_SingleText_Success() {
        String text = "这是一个测试文本";
        
        Object embeddingObj = mock(Object.class);
        
        EmbeddingResult result = mock(EmbeddingResult.class);
        lenient().when(result.getData()).thenReturn((List)Collections.singletonList(embeddingObj));
        
        EmbeddingResponse response = mock(EmbeddingResponse.class);
        when(response.isSuccess()).thenReturn(true);
        lenient().when(response.getData()).thenReturn(result);
        
        try {
            when(embeddingServiceClient.createEmbeddings(any(EmbeddingCreateParams.class)))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        float[] vector = embeddingService.embed(text);

        assertNotNull(vector);
        assertEquals(2048, vector.length);
        verify(embeddingServiceClient, atLeastOnce()).createEmbeddings(any(EmbeddingCreateParams.class));
    }

    @Test
    void testEmbedBatch_MultipleTexts_Success() {
        List<String> texts = Arrays.asList("文本1", "文本2", "文本3");
        
        Object obj1 = mock(Object.class);
        Object obj2 = mock(Object.class);
        Object obj3 = mock(Object.class);
        
        EmbeddingResult result = mock(EmbeddingResult.class);
        lenient().when(result.getData()).thenReturn((List)Arrays.asList(obj1, obj2, obj3));
        
        EmbeddingResponse response = mock(EmbeddingResponse.class);
        when(response.isSuccess()).thenReturn(true);
        lenient().when(response.getData()).thenReturn(result);
        
        try {
            when(embeddingServiceClient.createEmbeddings(any(EmbeddingCreateParams.class)))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<float[]> vectors = embeddingService.embedBatch(texts);

        assertNotNull(vectors);
        assertEquals(3, vectors.size());
        verify(embeddingServiceClient, atLeastOnce()).createEmbeddings(any(EmbeddingCreateParams.class));
    }

    @Test
    void testEmbedBatch_EmptyList_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            embeddingService.embedBatch(Collections.emptyList());
        });
    }

    @Test
    void testEmbedBatch_NullList_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            embeddingService.embedBatch(null);
        });
    }

    @Test
    void testEmbedBatch_ApiError_ThrowsException() {
        List<String> texts = Collections.singletonList("测试文本");
        
        EmbeddingResponse response = mock(EmbeddingResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getMsg()).thenReturn("API错误");
        
        try {
            when(embeddingServiceClient.createEmbeddings(any(EmbeddingCreateParams.class)))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThrows(RuntimeException.class, () -> {
            embeddingService.embedBatch(texts);
        });
    }

    @Test
    void testEmbedBatch_Exception_ThrowsException() {
        List<String> texts = Collections.singletonList("测试文本");
        
        try {
            when(embeddingServiceClient.createEmbeddings(any(EmbeddingCreateParams.class)))
                .thenThrow(new RuntimeException("网络错误"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThrows(RuntimeException.class, () -> {
            embeddingService.embedBatch(texts);
        });
    }

    @Test
    void testEmbedBatch_LargeBatch_Success() {
        String[] textArray = new String[60];
        Arrays.fill(textArray, "测试文本");
        List<String> texts = Arrays.asList(textArray);
        
        Object embeddingObj = mock(Object.class);
        
        EmbeddingResult result = mock(EmbeddingResult.class);
        lenient().when(result.getData()).thenReturn((List)Collections.singletonList(embeddingObj));
        
        EmbeddingResponse response = mock(EmbeddingResponse.class);
        when(response.isSuccess()).thenReturn(true);
        lenient().when(response.getData()).thenReturn(result);
        
        try {
            when(embeddingServiceClient.createEmbeddings(any(EmbeddingCreateParams.class)))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<float[]> vectors = embeddingService.embedBatch(texts);

        assertNotNull(vectors);
        assertEquals(60, vectors.size());
        verify(embeddingServiceClient, times(2)).createEmbeddings(any(EmbeddingCreateParams.class));
    }

    @Test
    void testEmbedBatch_ResultDataNull() {
        List<String> texts = Collections.singletonList("测试文本");
        
        EmbeddingResult result = mock(EmbeddingResult.class);
        when(result.getData()).thenReturn(null);
        
        EmbeddingResponse response = mock(EmbeddingResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getData()).thenReturn(result);
        
        try {
            when(embeddingServiceClient.createEmbeddings(any(EmbeddingCreateParams.class)))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<float[]> vectors = embeddingService.embedBatch(texts);

        assertNotNull(vectors);
        assertEquals(0, vectors.size());
    }
}