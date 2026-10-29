package com.example.esrag.agent;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.chat.ChatService;
import ai.z.openapi.service.model.*;
import com.example.esrag.service.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Agent 编排循环单元测试：
 * 1. 模型请求工具 -> 执行 -> FUNCTION 回填 -> 最终答案
 * 2. 最大轮数兜底（防死循环）
 * 3. 模型直接回答（不调工具）
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private ZhipuAiClient llmClient;

    @Mock
    private ChatService chatService;

    @Mock
    private MemoryService memoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        when(llmClient.chat()).thenReturn(chatService);
        // 只用计算器工具，测试不依赖 ES
        ToolRegistry registry = new ToolRegistry(List.of(new CalculatorTool()), objectMapper);
        agentService = new AgentService(llmClient, registry, memoryService, objectMapper, "glm-4-flash", 4);
        when(memoryService.getShortTermMemory(any(), any())).thenReturn("");
    }

    @Test
    void toolCallLoop_executesToolAndAnswers() throws Exception {
        // 注意：必须先把响应对象构建完成，再交给 when()，避免 Mockito 嵌套 stubbing
        ChatCompletionResponse toolRound = responseWithToolCall("calculator", "{\"expression\":\"(3+5)*2\"}", "call_1");
        ChatCompletionResponse finalRound = responseWithContent("答案是 16。");
        when(chatService.createChatCompletion(any())).thenReturn(toolRound, finalRound);

        AgentResult result = agentService.ask("请计算 (3+5)*2", 1L, "session-1");

        assertEquals("答案是 16。", result.getAnswer());
        assertEquals(2, result.getRounds());
        assertEquals(1, result.getToolTrace().size());
        assertEquals("calculator", result.getToolTrace().get(0).name());
        assertTrue(result.getToolTrace().get(0).result().contains("16"));

        // 共两次模型调用：工具决策轮 + 最终回答轮
        verify(chatService, times(2)).createChatCompletion(any());
        verify(memoryService, times(1)).addConversation(any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void maxRoundsGuard_stopsInfiniteLoop() throws Exception {
        // 模型每轮都要求调工具 -> 达到最大轮数后强制结束
        ChatCompletionResponse loopRound = responseWithToolCall("calculator", "{\"expression\":\"1+1\"}", "call_loop");
        ChatCompletionResponse forcedRound = responseWithContent("已尽力回答");
        when(chatService.createChatCompletion(any())).thenReturn(loopRound, loopRound, loopRound, loopRound, forcedRound);

        AgentResult result = agentService.ask("无限循环测试", 1L, "session-1");

        assertEquals(4, result.getRounds()); // 4 轮工具 + 1 轮强制回答
        assertEquals(4, result.getToolTrace().size());
        verify(chatService, times(5)).createChatCompletion(any());
    }

    @Test
    void noToolCall_answersDirectly() throws Exception {
        ChatCompletionResponse direct = responseWithContent("直接回答，不需要工具。");
        when(chatService.createChatCompletion(any())).thenReturn(direct);

        AgentResult result = agentService.ask("今天天气如何", 1L, "session-1");

        assertEquals(1, result.getRounds());
        assertTrue(result.getToolTrace().isEmpty());
        assertTrue(result.getAnswer().contains("直接回答"));
        verify(chatService, times(1)).createChatCompletion(any());
    }

    // ========================
    //  构造模拟响应
    // ========================

    private ChatCompletionResponse responseWithToolCall(String toolName, String argsJson, String callId) throws Exception {
        ChatCompletionResponse resp = mock(ChatCompletionResponse.class);
        when(resp.isSuccess()).thenReturn(true);

        ChatMessage msg = ChatMessage.builder()
                .role(ChatMessageRole.ASSISTANT.value())
                .content(null)
                .toolCalls(List.of(ToolCalls.builder()
                        .id(callId)
                        .type("function")
                        .function(ChatFunctionCall.builder()
                                .name(toolName)
                                .arguments(objectMapper.readTree(argsJson))
                                .build())
                        .build()))
                .build();

        ModelData data = mock(ModelData.class);
        Choice choice = mock(Choice.class);
        when(choice.getMessage()).thenReturn(msg);
        when(data.getChoices()).thenReturn(List.of(choice));
        when(resp.getData()).thenReturn(data);
        return resp;
    }

    private ChatCompletionResponse responseWithContent(String content) {
        ChatCompletionResponse resp = mock(ChatCompletionResponse.class);
        when(resp.isSuccess()).thenReturn(true);

        ChatMessage msg = ChatMessage.builder()
                .role(ChatMessageRole.ASSISTANT.value())
                .content(content)
                .build();

        ModelData data = mock(ModelData.class);
        Choice choice = mock(Choice.class);
        when(choice.getMessage()).thenReturn(msg);
        when(data.getChoices()).thenReturn(List.of(choice));
        when(resp.getData()).thenReturn(data);
        return resp;
    }
}