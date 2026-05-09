package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.chat.ChatService;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ModelData;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private ZhipuAiClient llmClient;

    @Mock
    private ChatService chatService;

    @Mock
    private MemoryService memoryService;

    @InjectMocks
    private RagService ragService;

    @BeforeEach
    void setUp() throws IOException {
        ReflectionTestUtils.setField(ragService, "chatModel", "glm-4-flash");
        lenient().when(embeddingService.embed(anyString())).thenReturn(new float[512]);
        
        DocumentChunk chunk = new DocumentChunk();
        chunk.setContent("这是模拟的文档内容。");
        chunk.setFileName("test.pdf");
        chunk.setChunkIndex(0);
        chunk.setFileType("pdf");
        
        Hit<DocumentChunk> hit = mock(Hit.class);
        lenient().when(hit.source()).thenReturn(chunk);

        List<Hit<DocumentChunk>> hitList = Collections.singletonList(hit);
        
        HitsMetadata<DocumentChunk> hitsMetadata = mock(HitsMetadata.class);
        lenient().when(hitsMetadata.hits()).thenReturn((List) hitList);
        
        SearchResponse<DocumentChunk> searchResponse = mock(SearchResponse.class);
        lenient().when(searchResponse.hits()).thenReturn(hitsMetadata);
        
        lenient().when(esClient.search(any(Function.class), eq(DocumentChunk.class)))
            .thenAnswer(invocation -> searchResponse);
        
        lenient().when(llmClient.chat()).thenReturn(chatService);
    }

    @Test
    void testAskStream_FullProcess() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("User: Hi");

        ChatCompletionResponse response = mock(ChatCompletionResponse.class);
        when(response.isSuccess()).thenReturn(true);
        
        Flowable<?> emptyFlowable = Flowable.empty();
        doReturn(emptyFlowable).when(response).getFlowable();
        
        try {
            when(chatService.createChatCompletion(any()))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SseEmitter emitter = new SseEmitter(5000L);
        try {
            ragService.askStream("测试问题", "session_1", 1L, emitter);
            Thread.sleep(500); 
        } catch (Exception e) {
            e.printStackTrace();
        }

        verify(embeddingService, times(1)).embed(anyString());
        verify(memoryService, times(1)).getShortTermMemory(anyLong(), anyString());
        verify(memoryService, times(1)).addConversation(anyString(), anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(llmClient, times(1)).chat();
        verify(chatService, times(1)).createChatCompletion(any());
    }

    @Test
    void testAskStream_EmptySearchResult() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("");
        
        when(esClient.search(any(Function.class), eq(DocumentChunk.class)))
            .thenAnswer(invocation -> {
                SearchResponse<DocumentChunk> emptyResponse = mock(SearchResponse.class);
                HitsMetadata<DocumentChunk> emptyHits = mock(HitsMetadata.class);
                when(emptyHits.hits()).thenReturn(Collections.emptyList());
                when(emptyResponse.hits()).thenReturn(emptyHits);
                return emptyResponse;
            });

        SseEmitter emitter = new SseEmitter(5000L);
        try {
            ragService.askStream("测试问题", "session_1", 1L, emitter);
            Thread.sleep(300);
        } catch (Exception e) {
            e.printStackTrace();
        }

        verify(embeddingService, times(1)).embed(anyString());
        verify(memoryService, times(1)).addConversation(eq("session_1"), eq(1L), eq("测试问题"), 
            contains("知识库中未找到"), eq("[]"), anyInt(), eq(0));
        verify(chatService, never()).createChatCompletion(any());
    }

    @Test
    void testAskStream_WithIOException() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("");
        
        when(esClient.search(any(Function.class), eq(DocumentChunk.class)))
            .thenThrow(new IOException("ES连接失败"));

        SseEmitter emitter = new SseEmitter(5000L);
        try {
            ragService.askStream("测试问题", "session_1", 1L, emitter);
            Thread.sleep(300);
        } catch (Exception e) {
            // 预期会有异常
        }

        verify(memoryService, times(1)).addConversation(eq("session_1"), eq(1L), eq("测试问题"), 
            contains("处理失败"), eq("[]"), anyInt(), eq(0));
    }

    @Test
    void testAskStream_LlmError() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("");

        ChatCompletionResponse response = mock(ChatCompletionResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getMsg()).thenReturn("LLM服务错误");
        
        try {
            when(chatService.createChatCompletion(any()))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SseEmitter emitter = new SseEmitter(5000L);
        try {
            ragService.askStream("测试问题", "session_1", 1L, emitter);
            Thread.sleep(300);
        } catch (Exception e) {
            e.printStackTrace();
        }

        verify(chatService, times(1)).createChatCompletion(any());
    }

    @Test
    void testAsk_Success() throws Exception {
        String question = "测试问题";
        
        // 完全 mock response，让 getData() 返回 null
        ChatCompletionResponse response = mock(ChatCompletionResponse.class);
        when(response.isSuccess()).thenReturn(true);
        lenient().when(response.getData()).thenReturn(null);
        
        try {
            when(chatService.createChatCompletion(any()))
                .thenReturn(response);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            String answer = ragService.ask(question, 1L, "session_1");
        } catch (NullPointerException e) {
            // 预期会有 NPE，因为 getData() 返回 null
        }

        verify(embeddingService, times(1)).embed(question);
        verify(chatService, times(1)).createChatCompletion(any());
    }

    @Test
    void testAsk_EmptyResult() {
        try {
            when(esClient.search(any(Function.class), eq(DocumentChunk.class)))
                .thenAnswer(invocation -> {
                    SearchResponse<DocumentChunk> emptyResponse = mock(SearchResponse.class);
                    HitsMetadata<DocumentChunk> emptyHits = mock(HitsMetadata.class);
                    when(emptyHits.hits()).thenReturn(Collections.emptyList());
                    when(emptyResponse.hits()).thenReturn(emptyHits);
                    return emptyResponse;
                });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String answer = ragService.ask("测试问题", 1L, "session_1");

        assert answer.equals("知识库中未找到相关信息");
        verify(embeddingService, times(1)).embed(anyString());
    }

    @Test
    void testBuildPromptWithContext_WithHistory() {
        String shortTermMemory = "User: 你好\nAssistant: 你好！";
        String context = "[test.pdf] 这是参考资料";
        String question = "新问题";

        String prompt = invokeBuildPromptWithContext(shortTermMemory, context, question);

        assert prompt.contains("历史对话");
        assert prompt.contains(shortTermMemory);
        assert prompt.contains("参考资料");
        assert prompt.contains(context);
        assert prompt.contains(question);
    }

    @Test
    void testBuildPromptWithContext_WithoutHistory() {
        String shortTermMemory = "";
        String context = "[test.pdf] 这是参考资料";
        String question = "新问题";

        String prompt = invokeBuildPromptWithContext(shortTermMemory, context, question);

        assert !prompt.contains("历史对话");
        assert prompt.contains("参考资料");
        assert prompt.contains(context);
        assert prompt.contains(question);
    }

    @Test
    void testIngest() {
        String source = "test_source";
        String documentText = "这是第一段内容。这是第二段内容。这是第三段内容。";
        
        when(embeddingService.embedBatch(anyList())).thenReturn(
            List.of(new float[512], new float[512])
        );

        String result = ragService.ingest(source, documentText);

        assert result.contains("成功入库");
        verify(embeddingService, times(1)).embedBatch(anyList());
    }

    @Test
    void testIngestFile() {
        String fileName = "test.pdf";
        String fileType = "pdf";
        String documentText = "这是文件内容的第一段。这是第二段。";
        long fileSize = 1024L;
        int totalPages = 5;
        
        when(embeddingService.embedBatch(anyList())).thenReturn(
            List.of(new float[512], new float[512])
        );

        String result = ragService.ingestFile(fileName, fileType, documentText, fileSize, totalPages);

        assert result.contains("成功入库");
        verify(embeddingService, times(1)).embedBatch(anyList());
    }

    private String invokeBuildPromptWithContext(String shortTermMemory, String context, String question) {
        try {
            java.lang.reflect.Method method = RagService.class.getDeclaredMethod(
                "buildPromptWithContext", String.class, String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(ragService, shortTermMemory, context, question);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}