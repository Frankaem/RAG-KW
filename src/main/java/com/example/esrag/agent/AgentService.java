package com.example.esrag.agent;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.alibaba.fastjson2.JSON;
import com.example.esrag.service.MemoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * Agent 编排服务：Function Calling 循环
 *
 * 与普通 RAG 的区别（面试重点）：
 * RAG   = 固定流程：问题 -> 必检索 -> 拼 Prompt -> 生成
 * Agent = 自主循环：问题 -> 模型决定是否调工具 -> 执行工具 -> 结果回填 -> 再决策 -> 生成
 *
 * 循环安全：最大轮数 maxRounds 兜底；每轮工具结果以 FUNCTION 角色消息回填上下文；
 * 最终答案流式输出（SSE），工具决策轮用非流式（结构化数据无需流式）。
 */
@Slf4j
@Service
public class AgentService {

    private final ZhipuAiClient client;
    private final ToolRegistry toolRegistry;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;
    private final String chatModel;
    private final int maxRounds;

    public AgentService(ZhipuAiClient client,
                        ToolRegistry toolRegistry,
                        MemoryService memoryService,
                        ObjectMapper objectMapper,
                        @Value("${llm.chat-model}") String chatModel,
                        @Value("${agent.max-rounds:4}") int maxRounds) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        this.maxRounds = maxRounds;
    }

    /**
     * 非流式问答（测试与普通接口用）
     */
    public AgentResult ask(String question, Long userId, String sessionId) {
        long start = System.currentTimeMillis();
        List<ChatMessage> messages = buildInitialMessages(question, userId, sessionId);
        List<ToolResult> trace = new ArrayList<>();
        List<Map<String, Object>> referencedDocs = new ArrayList<>();

        LoopOutcome outcome;
        try {
            outcome = runToolLoop(messages, trace, referencedDocs, null, null, false);
        } catch (IOException e) {
            throw new RuntimeException("Agent调用失败: " + e.getMessage(), e);
        }

        String answer = outcome.finalMessage == null || outcome.finalMessage.getContent() == null
                ? ""
                : String.valueOf(outcome.finalMessage.getContent());
        saveConversation(sessionId, userId, question, answer, referencedDocs, (int) (System.currentTimeMillis() - start));
        return AgentResult.builder()
                .answer(answer)
                .rounds(outcome.rounds)
                .toolTrace(trace)
                .referencedDocs(referencedDocs)
                .build();
    }

    /**
     * 流式问答：工具执行事件 + 最终答案逐字 SSE 推送
     */
    public void askStream(String question, Long userId, String sessionId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        StringBuilder streamedAnswer = new StringBuilder();
        List<ToolResult> trace = new ArrayList<>();
        List<Map<String, Object>> referencedDocs = new ArrayList<>();

        try {
            memoryService.restoreSessionFromMySQL(sessionId, userId);
            List<ChatMessage> messages = buildInitialMessages(question, userId, sessionId);
            runToolLoop(messages, trace, referencedDocs, emitter, streamedAnswer, true);
            saveConversation(sessionId, userId, question, streamedAnswer.toString(), referencedDocs, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("Agent处理失败 | Question: [{}] | Error: {}", question, e.getMessage(), e);
            safeSendError(emitter, "处理失败: " + e.getMessage());
            saveConversation(sessionId, userId, question, "处理失败: " + e.getMessage(), referencedDocs, (int) (System.currentTimeMillis() - start));
        }
    }

    // ========================
    //  核心：工具调用循环
    // ========================

    private LoopOutcome runToolLoop(List<ChatMessage> messages,
                                    List<ToolResult> trace,
                                    List<Map<String, Object>> referencedDocs,
                                    SseEmitter emitter,
                                    StringBuilder streamedAnswer,
                                    boolean streaming) throws IOException {
        int rounds = 0;
        for (int round = 1; round <= maxRounds; round++) {
            rounds = round;
            ChatMessage message = chatOnce(messages, true);
            messages.add(message);

            List<ToolCalls> toolCalls = message.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 模型认为信息足够，进入最终回答
                if (streaming) {
                    streamFinalAnswer(messages, emitter, streamedAnswer);
                    return new LoopOutcome(null, rounds);
                }
                return new LoopOutcome(message, rounds);
            }

            log.info("【Agent第{}轮】模型请求调用 {} 个工具", round, toolCalls.size());
            for (ToolCalls call : toolCalls) {
                ChatFunctionCall fn = call.getFunction();
                if (fn == null || fn.getName() == null) {
                    continue;
                }
                JsonNode argsNode = fn.getArguments();
                String argsText = argsNode == null ? "{}" : argsNode.toString();

                ToolResult toolResult = toolRegistry.execute(fn.getName(), argsNode);
                trace.add(toolResult);
                log.info("工具 [{}] 执行 {}ms | 结果预览: {}", fn.getName(), toolResult.durationMs(), preview(toolResult.result()));

                // 工具结果以 FUNCTION 角色回填，模型下一轮才能"看到"结果
                messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.FUNCTION.value())
                        .name(fn.getName())
                        .toolCallId(call.getId())
                        .content(toolResult.result())
                        .build());

                if (streaming) {
                    emitter.send(SseEmitter.event().name("tool_call").data(jsonEvent("tool_call", Map.of(
                            "name", fn.getName(),
                            "arguments", argsText))));
                    emitter.send(SseEmitter.event().name("tool_result").data(jsonEvent("tool_result", Map.of(
                            "name", fn.getName(),
                            "result", preview(toolResult.result())))));
                }

                if ("knowledge_base_search".equals(fn.getName())) {
                    collectReferencedDocs(toolResult.result(), referencedDocs);
                }
            }
        }

        // 达到最大轮数仍未结束：移除工具，强制模型基于已有上下文作答（防死循环兜底）
        log.warn("达到最大轮数 {}，强制模型基于已有上下文回答", maxRounds);
        ChatMessage forced = chatOnce(messages, false);
        messages.add(forced);
        if (streaming) {
            streamFinalAnswer(messages, emitter, streamedAnswer);
            return new LoopOutcome(null, rounds);
        }
        return new LoopOutcome(forced, rounds);
    }

    /**
     * 非流式调用一次模型（可选携带工具定义）
     */
    private ChatMessage chatOnce(List<ChatMessage> messages, boolean includeTools) throws IOException {
        var builder = ChatCompletionCreateParams.builder()
                .model(chatModel)
                .messages(messages)
                .stream(false)
                .maxTokens(65536)
                .temperature(0.7f);
        if (includeTools) {
            builder.tools(toolRegistry.toChatTools());
        }
        ChatCompletionResponse response = client.chat().createChatCompletion(builder.build());
        if (!response.isSuccess() || response.getData() == null) {
            throw new RuntimeException("LLM调用失败: " + response.getMsg());
        }
        ChatMessage message = response.getData().getChoices().get(0).getMessage();
        if (message == null) {
            throw new RuntimeException("LLM返回为空");
        }
        return message;
    }

    /**
     * 最终答案流式输出（复用 SSE 事件流）
     */
    private void streamFinalAnswer(List<ChatMessage> messages, SseEmitter emitter, StringBuilder answerBuilder) throws IOException {
        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(chatModel)
                .messages(messages)
                .stream(true)
                .maxTokens(65536)
                .temperature(0.7f)
                .build();
        ChatCompletionResponse response = client.chat().createChatCompletion(request);
        if (!response.isSuccess()) {
            emitter.send(SseEmitter.event().name("error").data("LLM调用失败: " + response.getMsg()));
            emitter.complete();
            return;
        }
        response.getFlowable().subscribe(
                data -> {
                    try {
                        if (data.getChoices() != null && !data.getChoices().isEmpty()) {
                            Delta delta = data.getChoices().get(0).getDelta();
                            if (delta != null && delta.getContent() != null && !delta.getContent().isEmpty()) {
                                answerBuilder.append(delta.getContent());
                                emitter.send(SseEmitter.event().name("message").data(delta.getContent()));
                            }
                        }
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                error -> {
                    log.error("Agent流式输出异常", error);
                    emitter.completeWithError(error);
                },
                () -> {
                    log.info("Agent流式输出完成");
                    emitter.complete();
                }
        );
    }

    // ========================
    //  辅助方法
    // ========================

    private List<ChatMessage> buildInitialMessages(String question, Long userId, String sessionId) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder()
                .role(ChatMessageRole.SYSTEM.value())
                .content("你是知识库智能助手。你可以调用工具获取资料后回答问题。规则：1) 需要参考知识库资料时调用 knowledge_base_search，可多次调用检索不同文档；2) 用户问题涉及数值计算时调用 calculator；3) 只能基于工具返回的内容回答，不要编造；4) 资料不足时明确说明无法回答。")
                .build());
        if (userId != null && sessionId != null) {
            String history = memoryService.getShortTermMemory(userId, sessionId);
            if (history != null && !history.isEmpty()) {
                messages.add(ChatMessage.builder()
                        .role(ChatMessageRole.SYSTEM.value())
                        .content("【历史对话】\n" + history)
                        .build());
            }
        }
        messages.add(ChatMessage.builder()
                .role(ChatMessageRole.USER.value())
                .content(question)
                .build());
        return messages;
    }

    private void collectReferencedDocs(String toolResultJson, List<Map<String, Object>> referencedDocs) {
        try {
            List<Map<String, Object>> items = objectMapper.readValue(toolResultJson, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> item : items) {
                if (item.containsKey("error")) {
                    continue;
                }
                String fileName = String.valueOf(item.getOrDefault("fileName", ""));
                Object chunkIndex = item.get("chunkIndex");
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("fileName", fileName);
                ref.put("chunkIndex", chunkIndex);
                ref.put("contentPreview", item.getOrDefault("contentPreview", ""));
                boolean exists = referencedDocs.stream().anyMatch(d ->
                        fileName.equals(d.get("fileName")) && Objects.equals(chunkIndex, d.get("chunkIndex")));
                if (!exists) {
                    referencedDocs.add(ref);
                }
            }
        } catch (Exception e) {
            log.warn("解析引用文档失败: {}", e.getMessage());
        }
    }

    private void saveConversation(String sessionId, Long userId, String question,
                                  String answer, List<Map<String, Object>> referencedDocs, int totalTime) {
        try {
            memoryService.addConversation(sessionId, userId, question, answer,
                    JSON.toJSONString(referencedDocs), totalTime, 0);
        } catch (Exception e) {
            log.warn("保存对话失败: {}", e.getMessage());
        }
    }

    private void safeSendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (Exception ignored) {
            // emitter 可能已经完成
        }
    }

    private String jsonEvent(String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("data", payload);
        return objectMapper.writeValueAsString(event);
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static final class LoopOutcome {
        private final ChatMessage finalMessage;
        private final int rounds;

        private LoopOutcome(ChatMessage finalMessage, int rounds) {
            this.finalMessage = finalMessage;
            this.rounds = rounds;
        }
    }
}
