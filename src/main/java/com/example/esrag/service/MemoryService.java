package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;

import com.example.esrag.entity.Conversation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 记忆服务 - 管理三层记忆架构
 * 
 * 【三层记忆架构】
 * 1. 短期记忆 (Short-term Memory): Redis 存储最近10轮对话，用于 LLM Context Window
 * 2. 长期记忆 (Long-term Memory): ES 存储对话摘要向量，支持跨会话语义检索
 * 3. 语义记忆 (Semantic Memory): ES 存储知识库文档向量，用于 RAG 检索增强
 * 
 * 【注意】MySQL 中的对话日志不属于"记忆系统"，而是"持久化存储/审计日志"
 */
@Slf4j
@Service
public class MemoryService {

    private final StringRedisTemplate redisTemplate;
    private final EmbeddingService embeddingService;
    private final ElasticsearchClient esClient;
    private final ZhipuAiClient llmClient;
    private final ConversationLogService conversationLogService;
    private final String chatModel;

    public MemoryService(StringRedisTemplate redisTemplate,
                        EmbeddingService embeddingService,
                        ElasticsearchClient esClient,
                        ZhipuAiClient llmClient,
                        ConversationLogService conversationLogService,
                        @Value("${llm.chat-model}") String chatModel) {
        this.redisTemplate = redisTemplate;
        this.embeddingService = embeddingService;
        this.esClient = esClient;
        this.llmClient = llmClient;
        this.conversationLogService = conversationLogService;
        this.chatModel = chatModel;
                        }

    private static final int MAX_HISTORY_TURNS = 10;
    private static final String MEMORY_INDEX = "conversation_memory";
    private static final long REDIS_TTL_DAYS = 7;

    /**
     * 【短期记忆】获取短期记忆（用于构造 Prompt）
     * 
     * 从 Redis 中获取当前会话的最近对话历史
     * Key 格式: chat:history:{userId}:{sessionId}
     * 
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 格式化的历史对话字符串
     */
    public String getShortTermMemory(Long userId, String sessionId) {
        String key = buildRedisKey(userId, sessionId);
        List<String> history = redisTemplate.opsForList().range(key, 0, -1);
        if (history == null || history.isEmpty()) return "";
        return String.join("\n", history);
    }

    /**
     * 【记忆系统核心方法】添加一轮对话到记忆系统
     * 
     * 处理流程：
     * 1. 短期记忆：存入 Redis（最近10轮对话）
     * 2. 对话日志：持久化到 MySQL（存档/审计）
     * 3. 长期记忆：异步生成摘要并向量化存入 ES
     * 
     * @param sessionId   会话ID
     * @param userId      用户ID
     * @param question    用户问题
     * @param answer      AI回答
     * @param referencedDocs 参考文档JSON
     * @param retrievalTimeMs 检索耗时
     * @param llmTimeMs   LLM耗时
     */
    public void addConversation(String sessionId, Long userId, String question,
                                String answer, String referencedDocs,
                                Integer retrievalTimeMs, Integer llmTimeMs) {
        String key = buildRedisKey(userId, sessionId);

        // 1. 存入 Redis (短期记忆 - 用于LLM上下文)
        String turn = "User: " + question + "\nAI: " + answer;
        redisTemplate.opsForList().rightPush(key, turn);
        
        // 设置过期时间（7天）
        redisTemplate.expire(key, REDIS_TTL_DAYS, TimeUnit.DAYS);

        // 2. 维护窗口大小（只留最近10条）
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > MAX_HISTORY_TURNS) {
            redisTemplate.opsForList().trim(key, size - MAX_HISTORY_TURNS, -1);
        }

        // 3. 持久化到 MySQL (对话日志存档 - 非记忆系统)
        conversationLogService.saveConversationLog(
                userId, sessionId, question, answer,
                referencedDocs, retrievalTimeMs, llmTimeMs
        );

        // 4. 异步处理长期记忆（摘要 + 向量化）
        processLongTermMemoryAsync(sessionId, userId, question, answer);
    }

    /**
     * 【长期记忆】异步处理长期记忆：摘要压缩并向量化存储到 ES
     * 
     * 这是真正的"长期记忆"实现：
     * - 使用 LLM 生成对话摘要
     * - 向量化摘要内容
     * - 存入 ES 的 conversation_memory 索引
     * - 支持跨会话的语义检索
     * 
     * @param sessionId 会话ID
     * @param userId    用户ID
     * @param question  用户问题
     * @param answer    AI回答
     */
    @Async
    public void processLongTermMemoryAsync(String sessionId, Long userId, String question, String answer) {
        try {
            // 1. 使用 LLM 生成高质量摘要
            String summary = generateSummaryWithLLM(question, answer);

            // 2. 向量化摘要
            float[] vector = embeddingService.embed(summary);

            // 3. 存入 ES 的 memory_index
            saveToElasticsearch(sessionId, userId, summary, vector, question, answer);
            
            log.info("长期记忆处理完成 | UserId: {} | SessionId: {} | Summary: {}", userId, sessionId, summary);

        } catch (Exception e) {
            log.error("长期记忆处理失败 | UserId: {} | SessionId: {} | Error: {}", userId, sessionId, e.getMessage(), e);
        }
    }

    /**
     * 使用 LLM 生成对话摘要
     */
    private String generateSummaryWithLLM(String question, String answer) {
        try {
            String prompt = String.format(
                "请将以下对话压缩为一句话摘要（不超过50字），保留核心主题和关键信息：\n" +
                "用户：%s\n" +
                "助手：%s\n" +
                "摘要：",
                question.substring(0, Math.min(200, question.length())),
                answer.substring(0, Math.min(200, answer.length()))
            );

            List<ChatMessage> messages = Collections.singletonList(
                ChatMessage.builder()
                    .role(ChatMessageRole.USER.value())
                    .content(prompt)
                    .build()
            );

            ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(chatModel)
                .messages(messages)
                .maxTokens(100)
                .temperature(0.3f)
                .build();

            ChatCompletionResponse response = llmClient.chat().createChatCompletion(request);

            if (response.isSuccess() && response.getData() != null && !response.getData().getChoices().isEmpty()) {
                String summary = response.getData().getChoices().get(0).getMessage().getContent().toString();
                return summary.trim();
            }

        } catch (Exception e) {
            log.warn("LLM 生成摘要失败，使用简化方案 | Error: {}", e.getMessage());
        }

        // 降级方案：简单截取
        return "关于 " + question.substring(0, Math.min(30, question.length())) + " 的咨询";
    }

    /**
     * 将摘要存入 Elasticsearch
     */
    private void saveToElasticsearch(String sessionId, Long userId, String summary, 
                                      float[] vector, String question, String answer) {
        try {
            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            Map<String, Object> memoryDoc = new HashMap<>();
            memoryDoc.put("sessionId", sessionId);
            memoryDoc.put("userId", userId);
            memoryDoc.put("summary", summary);
            memoryDoc.put("vector", vectorList);
            memoryDoc.put("questionPreview", question.substring(0, Math.min(100, question.length())));
            memoryDoc.put("answerPreview", answer.substring(0, Math.min(100, answer.length())));
            memoryDoc.put("createdAt", new Date());

            String docId = "mem_" + userId + "_" + System.currentTimeMillis();

            IndexRequest<Map<String, Object>> request = IndexRequest.of(i -> i
                .index(MEMORY_INDEX)
                .id(docId)
                .document(memoryDoc)
            );

            esClient.index(request);
            
            log.debug("长期记忆已存入 ES | DocId: {} | SessionId: {}", docId, sessionId);

        } catch (Exception e) {
            log.error("存入 ES 失败 | SessionId: {} | Error: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 【会话恢复】从 MySQL 加载历史对话到 Redis（会话恢复场景）
     * 
     * 当用户重新打开会话时，从对话日志中恢复短期记忆
     * 需要同时验证 userId 和 sessionId，确保数据隔离
     * 
     * @param sessionId 会话ID
     * @param userId    用户ID
     */
    public void restoreSessionFromMySQL(String sessionId, Long userId) {
        String key = buildRedisKey(userId, sessionId);

        // 检查 Redis 中是否已有数据
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            log.debug("会话已在 Redis 中存在 | UserId: {} | SessionId: {}", userId, sessionId);
            return;
        }

        // 从 MySQL 加载最近的对话日志（通过 ConversationLogService 增加 userId 校验）
        List<Conversation> conversations = conversationLogService.getConversationLogsBySessionIdAndUserId(sessionId, userId);

        if (conversations.isEmpty()) {
            log.debug("会话无历史记录 | UserId: {} | SessionId: {}", userId, sessionId);
            return;
        }

        // 恢复到 Redis（最多10条）
        List<String> history = conversations.stream()
                .limit(MAX_HISTORY_TURNS)
                .map(conv -> "User: " + conv.getQuestion() + "\nAI: " + conv.getAnswer())
                .collect(Collectors.toList());

        redisTemplate.opsForList().rightPushAll(key, history);
        
        // 设置过期时间
        redisTemplate.expire(key, REDIS_TTL_DAYS, TimeUnit.DAYS);

        log.info("会话恢复完成 | UserId: {} | SessionId: {} | 恢复 {} 条记录", userId, sessionId, history.size());
    }

    /**
     * 【短期记忆】清除会话的短期记忆（Redis）
     * 
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    public void clearShortTermMemory(Long userId, String sessionId) {
        String key = buildRedisKey(userId, sessionId);
        redisTemplate.delete(key);
        log.info("清除短期记忆 | UserId: {} | SessionId: {}", userId, sessionId);
    }

    /**
     * 【长期记忆】搜索长期记忆（从 ES 中语义检索历史对话摘要）
     * 
     * 用于 RAG 检索时补充用户的历史相关记忆
     * 
     * @param userId 用户ID
     * @param query  查询文本
     * @param topK   返回数量
     * @return 相关的历史记忆摘要列表
     */
    public List<Map<String, Object>> searchLongTermMemory(Long userId, String query, int topK) {
        try {
            // 1. 向量化查询
            float[] queryVector = embeddingService.embed(query);
            List<Float> vectorList = new ArrayList<>();
            for (float v : queryVector) {
                vectorList.add(v);
            }

            // 2. ES kNN 搜索
            var response = esClient.search(s -> s
                .index(MEMORY_INDEX)
                .knn(k -> k
                    .field("vector")
                    .queryVector(vectorList)
                    .k((long) topK)
                    .filter(f -> f.term(t -> t.field("userId").value(userId)))
                )
                .source(src -> src.filter(f -> f.excludes("vector"))),
                Map.class
            );

            List<Map<String, Object>> results = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                if (hit.source() != null) {
                    results.add((Map<String, Object>) hit.source());
                }
            }

            log.info("长期记忆检索完成 | UserId: {} | 命中 {} 条", userId, results.size());
            return results;

        } catch (Exception e) {
            log.error("长期记忆检索失败 | UserId: {} | Error: {}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建 Redis Key，包含 userId 以确保数据隔离
     * 格式: chat:history:{userId}:{sessionId}
     */
    private String buildRedisKey(Long userId, String sessionId) {
        return "chat:history:" + userId + ":" + sessionId;
    }
}
