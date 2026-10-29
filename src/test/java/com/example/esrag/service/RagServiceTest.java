package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.chat.ChatService;
import ai.z.openapi.service.model.ChatCompletionResponse;
import com.example.esrag.ChunkRepository;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagService 单元测试（检索已抽到 RetrievalService，这里用 mock 验证问答编排逻辑）
 */
@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ZhipuAiClient llmClient;

    @Mock
    private ChatService chatService;

    @Mock
    private MemoryService memoryService;

    @Mock
    private RetrievalService retrievalService;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        // @InjectMocks 无法处理构造器中的 @Value 参数，这里显式构造
        ragService = new RagService(chunkRepository, embeddingService, llmClient, memoryService,
                retrievalService, "glm-4-flash", true, 3, 0.7, 5);
        lenient().when(llmClient.chat()).thenReturn(chatService);
        lenient().when(embeddingService.embedBatch(anyList()))
                .thenReturn(List.of(new float[512], new float[512]));

        DocumentChunk chunk = new DocumentChunk();
        chunk.setId("doc_1_chunk_0");
        chunk.setContent("这是模拟的文档内容。");
        chunk.setFileName("test.pdf");
        chunk.setChunkIndex(0);
        chunk.setFileType("pdf");
        lenient().when(retrievalService.search(anyString(), anyInt()))
                .thenReturn(List.of(chunk));
    }

    @Test
    void testAskStream_FullProcess() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("User: Hi");

        ChatCompletionResponse response = mock(ChatCompletionResponse.class);
        when(response.isSuccess()).thenReturn(true);
        doReturn(io.reactivex.rxjava3.core.Flowable.empty()).when(response).getFlowable();

        when(chatService.createChatCompletion(any())).thenReturn(response);

        SseEmitter emitter = new SseEmitter(5000L);
        ragService.askStream("测试问题", "session_1", 1L, emitter);
        Thread.sleep(500);

        verify(retrievalService, times(1)).search(anyString(), eq(5));
        verify(memoryService, times(2)).getShortTermMemory(anyLong(), anyString());
        verify(memoryService, times(1)).addConversation(anyString(), anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(llmClient, times(1)).chat();
        verify(chatService, times(1)).createChatCompletion(any());
    }

    @Test
    void testAskStream_EmptySearchResult() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("");
        when(retrievalService.search(anyString(), anyInt())).thenReturn(Collections.emptyList());

        SseEmitter emitter = new SseEmitter(5000L);
        ragService.askStream("测试问题", "session_1", 1L, emitter);
        Thread.sleep(300);

        verify(memoryService, times(1)).addConversation(eq("session_1"), eq(1L), eq("测试问题"),
                contains("知识库中未找到"), eq("[]"), anyInt(), eq(0));
        verify(chatService, never()).createChatCompletion(any());
    }

    @Test
    void testAskStream_WithIOException() throws Exception {
        when(memoryService.getShortTermMemory(anyLong(), anyString())).thenReturn("");
        when(retrievalService.search(anyString(), anyInt())).thenThrow(new RuntimeException("ES连接失败"));

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

        when(chatService.createChatCompletion(any())).thenReturn(response);

        SseEmitter emitter = new SseEmitter(5000L);
        ragService.askStream("测试问题", "session_1", 1L, emitter);
        Thread.sleep(300);

        verify(chatService, times(1)).createChatCompletion(any());
    }

    @Test
    void testAsk_Success() {
        String question = "测试问题";

        // 完全 mock response，让 getData() 返回 null，验证异常路径不会中断整体流程
        ChatCompletionResponse response = mock(ChatCompletionResponse.class);
        when(response.isSuccess()).thenReturn(true);
        lenient().when(response.getData()).thenReturn(null);

        when(chatService.createChatCompletion(any())).thenReturn(response);

        try {
            ragService.ask(question, 1L, "session_1");
        } catch (NullPointerException e) {
            // 预期会有 NPE，因为 getData() 返回 null
        }

        verify(retrievalService, times(1)).search(anyString(), anyInt());
        verify(chatService, times(1)).createChatCompletion(any());
    }

    @Test
    void testAsk_EmptyResult() {
        when(retrievalService.search(anyString(), anyInt())).thenReturn(Collections.emptyList());

        String answer = ragService.ask("测试问题", 1L, "session_1");

        assert answer.equals("知识库中未找到相关信息");
        verify(retrievalService, times(1)).search(anyString(), anyInt());
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

        String result = ragService.ingest(source, documentText);

        assert result.contains("成功入库");
        verify(embeddingService, times(1)).embedBatch(anyList());
        verify(chunkRepository, atLeast(1)).save(any(DocumentChunk.class));
    }

    @Test
    void testIngestFile() {
        String fileName = "test.pdf";
        String fileType = "pdf";
        String documentText = "这是文件内容的第一段。这是第二段。";
        long fileSize = 1024L;
        int totalPages = 5;

        String result = ragService.ingestFile(fileName, fileType, documentText, fileSize, totalPages);

        assert result.contains("成功入库");
        verify(embeddingService, times(1)).embedBatch(anyList());
        verify(chunkRepository, atLeast(1)).save(any(DocumentChunk.class));
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
