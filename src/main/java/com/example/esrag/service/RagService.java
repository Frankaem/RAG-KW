package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.alibaba.fastjson2.JSON;
import com.example.esrag.ChunkRepository;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.utils.TextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final ElasticsearchClient esClient;
    private final ZhipuAiClient client;
    private final MemoryService memoryService;
    private final String chatModel;
    
    // 长期记忆配置
    private final boolean longTermMemoryEnabled;
    private final int longTermMemoryTopK;
    private final double longTermMemoryRelevanceThreshold;

    public RagService(ChunkRepository chunkRepository,
                      EmbeddingService embeddingService,
                      ElasticsearchClient esClient,
                      ZhipuAiClient client, MemoryService memoryService,
                      @Value("${llm.chat-model}") String chatModel,
                      @Value("${memory.long-term.enabled:true}") boolean longTermMemoryEnabled,
                      @Value("${memory.long-term.top-k:3}") int longTermMemoryTopK,
                      @Value("${memory.long-term.relevance-threshold:0.7}") double longTermMemoryRelevanceThreshold) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.esClient = esClient;
        this.client = client;
        this.memoryService = memoryService;
        this.chatModel = chatModel;
        this.longTermMemoryEnabled = longTermMemoryEnabled;
        this.longTermMemoryTopK = longTermMemoryTopK;
        this.longTermMemoryRelevanceThreshold = longTermMemoryRelevanceThreshold;
    }

    // ========================
    //  离线阶段：文档入库
    // ========================
    public String ingest(String source, String documentText) {
        // 1. 分片
        List<String> chunks = TextSplitter.split(documentText);
        // 2. 批量向量化
        List<float[]> vectors = embeddingService.embedBatch(chunks);
        // 3. 存入ES
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk doc = new DocumentChunk();
            doc.setId(source + "_" + i);
//            doc.setSource(source);
            doc.setContent(chunks.get(i));
            doc.setVector(vectors.get(i));
            chunkRepository.save(doc);
        }
        return "成功入库 " + chunks.size() + " 个切片";
    }

    public String ingestFile(String fileName, String fileType, String documentText, long fileSize, int totalPages) {
        List<String> chunks = TextSplitter.split(documentText);
        List<float[]> vectors = embeddingService.embedBatch(chunks);

        String source = fileName.replaceAll("[^a-zA-Z0-9]", "_");
        LocalDateTime uploadTime = java.time.LocalDateTime.now();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk doc = new DocumentChunk();
            doc.setId(source + "_" + System.currentTimeMillis() + "_" + i);
//            doc.setSource(source);
//            doc.setName(fileName);
            doc.setContent(chunks.get(i));
            doc.setVector(vectors.get(i));
            doc.setChunkIndex(i);
            doc.setTotalPages(totalPages);
            doc.setTotalChunks(chunks.size());
            doc.setFileName(fileName);
            doc.setFileType(fileType);
            doc.setUploadTime(uploadTime);
            doc.setFileSize(fileSize);
            chunkRepository.save(doc);
        }

        log.info("文件 {} 解析完成，共 {} 个切片", fileName, chunks.size());

        return "成功入库 " + chunks.size() + " 个切片";
    }


    // ========================
    //  在线阶段：RAG问答
    // ========================
    /**
     * 【在线阶段】RAG 问答（非流式）
     * 
     * 检索策略：
     * 1. 语义记忆（知识库）：从 doc_chunks 索引检索相关文档
     * 2. 长期记忆（可选）：从 conversation_memory 索引检索历史对话摘要
     * 3. 短期记忆：从 Redis 获取当前会话的最近对话
     */
    public String ask(String question, Long userId, String sessionId) {
        long startTime = System.currentTimeMillis();

        float[] qv = embeddingService.embed(question);

        // 1. 检索语义记忆（知识库文档）
        long knowledgeSearchStart = System.currentTimeMillis();
        List<DocumentChunk> knowledgeHits = knnSearch(qv, 20);
        long knowledgeSearchTime = System.currentTimeMillis() - knowledgeSearchStart;
        
        if (knowledgeHits.isEmpty()) {
            log.warn("问题: [{}] 未检索到任何相关文档 | 耗时: {}ms", question, knowledgeSearchTime);
            return "知识库中未找到相关信息";
        }

        // 去重：按文件名去重
        Map<String, DocumentChunk> uniqueHits = new LinkedHashMap<>();
        for (DocumentChunk hit : knowledgeHits) {
            uniqueHits.putIfAbsent(hit.getFileName(), hit);
        }
        List<DocumentChunk> finalKnowledgeHits = new ArrayList<>(uniqueHits.values());
        if (finalKnowledgeHits.size() > 5) {
            finalKnowledgeHits = finalKnowledgeHits.subList(0, 5);
        }

        log.info("【语义记忆】问题: [{}] 原始命中 {} 条，去重后保留 {} 条 | 耗时: {}ms", 
                question, knowledgeHits.size(), finalKnowledgeHits.size(), knowledgeSearchTime);
        log.info("检索结果详情:\n{}", finalKnowledgeHits.stream()
                .map(c -> String.format("- [%s] (相似度得分): %s...", c.getFileName(), c.getContent().substring(0, Math.min(50, c.getContent().length()))))
                .collect(Collectors.joining("\n")));

        // 2. 检索长期记忆（用户历史对话摘要）
        List<Map<String, Object>> longTermMemories = new ArrayList<>();
        long longTermSearchTime = 0;
        if (longTermMemoryEnabled && userId != null) {
            try {
                long longTermSearchStart = System.currentTimeMillis();
                longTermMemories = memoryService.searchLongTermMemory(userId, question, longTermMemoryTopK);
                longTermSearchTime = System.currentTimeMillis() - longTermSearchStart;
                log.info("【长期记忆】UserId: {} | 命中 {} 条 | 耗时: {}ms", userId, longTermMemories.size(), longTermSearchTime);
                
                if (!longTermMemories.isEmpty()) {
                    log.info("长期记忆内容:\n{}", longTermMemories.stream()
                            .map(m -> "- " + m.get("summary"))
                            .collect(Collectors.joining("\n")));
                }
            } catch (Exception e) {
                log.warn("长期记忆检索失败，继续使用知识库检索 | Error: {}", e.getMessage());
            }
        } else {
            log.debug("长期记忆未启用或 userId 为空 | Enabled: {}, UserId: {}", longTermMemoryEnabled, userId);
        }

        // 3. 构造 Prompt（包含三层记忆）
        String prompt = buildPromptWithAllContext(question, finalKnowledgeHits, longTermMemories, userId, sessionId);

        long llmStart = System.currentTimeMillis();
        String answer = callLlm(prompt);
        long llmTime = System.currentTimeMillis() - llmStart;

        long totalTime = System.currentTimeMillis() - startTime;
        
        log.info("【RAG完成】问题: [{}] | 总耗时: {}ms | 知识检索: {}ms | 长期记忆: {}ms | LLM: {}ms", 
                question, totalTime, knowledgeSearchTime, longTermSearchTime, llmTime);

        return answer;
    }

    /**
     * 构造包含三层记忆的 Prompt
     * 
     * @param question          用户问题
     * @param knowledgeHits     知识库文档片段
     * @param longTermMemories  长期记忆（历史对话摘要）
     * @param userId            用户ID
     * @param sessionId         会话ID
     * @return 完整的 Prompt
     */
    private String buildPromptWithAllContext(String question, 
                                              List<DocumentChunk> knowledgeHits,
                                              List<Map<String, Object>> longTermMemories,
                                              Long userId, String sessionId) {
        StringBuilder prompt = new StringBuilder();

        // 1. 长期记忆（历史相关对话摘要）
        if (!longTermMemories.isEmpty()) {
            prompt.append("【历史相关记忆】\n");
            for (int i = 0; i < longTermMemories.size(); i++) {
                Map<String, Object> memory = longTermMemories.get(i);
                String summary = (String) memory.get("summary");
                if (summary != null && !summary.isEmpty()) {
                    prompt.append((i + 1)).append(". ").append(summary).append("\n");
                }
            }
            prompt.append("\n");
        }

        // 2. 短期记忆（当前会话历史）
        if (userId != null && sessionId != null) {
            String shortTermMemory = memoryService.getShortTermMemory(userId, sessionId);
            if (shortTermMemory != null && !shortTermMemory.isEmpty()) {
                prompt.append("【当前对话历史】\n").append(shortTermMemory).append("\n\n");
            }
        }

        // 3. 语义记忆（知识库文档）
        String ctx = knowledgeHits.stream()
                .map(c -> "[文件名: " + c.getFileName() + "] " + c.getContent())
                .collect(Collectors.joining("\n\n"));
        
        prompt.append("【参考资料】\n").append(ctx).append("\n\n");
        
        prompt.append("问题：").append(question).append("\n\n");
        prompt.append("请严格根据以上【参考资料】回答问题。如果资料中没有相关信息，请直接回答：根据现有资料无法找到答案。");

        return prompt.toString();
    }

    /**
     * ES的kNN向量检索核心代码（就这几行）
     */
    /**
     * ES kNN向量检索（通用兼容写法）
     */
    private List<DocumentChunk> knnSearch(float[] queryVector, int topK) {
        try {
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
                                    .numCandidates(100L))
//                              只返回需要的字段，不返回向量
                            .source(src -> src.filter(f -> f.excludes("vector"))),
                    DocumentChunk.class);

            // 解析结果
            List<DocumentChunk> results = new ArrayList<>();
            for (Hit<DocumentChunk> hit : resp.hits().hits()) {
                if (hit.source() != null) {
                    results.add(hit.source());
                }
            }

            // RAG 必备日志（能看到到底命中了哪段）
            log.info("向量检索命中 {} 条 | topK:{}", results.size(), topK);
            for (DocumentChunk chunk : results) {
                log.info("命中片段 [{}] >>> {}", chunk.getFileName(), chunk.getContent());
            }

            return results;
        } catch (IOException e) {
            log.error("ES 向量检索异常", e);
            throw new RuntimeException("ES向量检索失败: " + e.getMessage(), e);
        }
    }


    private String callLlm(String prompt) {


        List<ChatMessage> messages = Arrays.asList(
                ChatMessage.builder()
                        .role(ChatMessageRole.SYSTEM.value())
                        .content("你是知识库助手，仅根据以下资料回答，不要编造。如果资料中没有相关信息请说明。")
                        .build(),
                ChatMessage.builder()
                        .role(ChatMessageRole.USER.value())
                        .content(prompt)
                        .build()
        );
        // 创建聊天完成请求
        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(chatModel)
                .messages(messages)
                .thinking(ChatThinking.builder().type("enabled").build())
                .maxTokens(65536)
                .temperature(0.7f)
                .build();

        // 发送请求
        ChatCompletionResponse response = client.chat().createChatCompletion(request);

        // 获取回复
        if (response.isSuccess()) {
            ChatMessage message = (ChatMessage) response.getData().getChoices().get(0).getMessage();
            return message.getContent().toString();
        } else {
            return response.getMsg();
        }
    }

    public void askStream(String question, String sessionId, Long userId, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        StringBuilder fullAnswer = new StringBuilder();
        List<DocumentChunk> referencedDocs = new ArrayList<>();
        
        try {
            // 自动恢复会话（如果 Redis 中没有，从 MySQL 加载）
            memoryService.restoreSessionFromMySQL(sessionId, userId);

            // 获取短期记忆
            String shortTermMemory = memoryService.getShortTermMemory(userId, sessionId);
            log.debug("【短期记忆】UserId: {} | SessionId: {} | 历史对话长度: {}", 
                    userId, sessionId, shortTermMemory != null ? shortTermMemory.length() : 0);

            float[] qv = embeddingService.embed(question);

            // 1. 检索语义记忆（知识库文档）
            long knowledgeSearchStart = System.currentTimeMillis();
            List<DocumentChunk> hits = knnSearch(qv, 5);
            long knowledgeSearchTime = System.currentTimeMillis() - knowledgeSearchStart;
            
            if (hits.isEmpty()) {
                String noResultMsg = "知识库中未找到相关信息";
                emitter.send(SseEmitter.event().name("message").data(noResultMsg));
                emitter.complete();
                
                long totalTime = System.currentTimeMillis() - startTime;
                log.warn("【RAG完成】问题: [{}] | 未检索到文档 | 总耗时: {}ms", question, totalTime);
                
                memoryService.addConversation(sessionId, userId, question, 
                    noResultMsg, "[]", (int)totalTime, 0);
                return;
            }

            referencedDocs.addAll(hits);
            log.info("【语义记忆】问题: [{}] | 命中 {} 条 | 耗时: {}ms", question, hits.size(), knowledgeSearchTime);

            // 2. 检索长期记忆（用户历史对话摘要）
            List<Map<String, Object>> longTermMemories = new ArrayList<>();
            long longTermSearchTime = 0;
            if (longTermMemoryEnabled && userId != null) {
                try {
                    long longTermSearchStart = System.currentTimeMillis();
                    longTermMemories = memoryService.searchLongTermMemory(userId, question, longTermMemoryTopK);
                    longTermSearchTime = System.currentTimeMillis() - longTermSearchStart;
                    log.info("【长期记忆】UserId: {} | 命中 {} 条 | 耗时: {}ms", userId, longTermMemories.size(), longTermSearchTime);
                } catch (Exception e) {
                    log.warn("长期记忆检索失败，继续使用知识库检索 | Error: {}", e.getMessage());
                }
            }

            // 3. 构造包含三层记忆的 Prompt
            String prompt = buildPromptWithAllContext(question, hits, longTermMemories, userId, sessionId);

            long retrievalTime = System.currentTimeMillis() - startTime;
            
            long llmStart = System.currentTimeMillis();
            streamCallLlm(prompt, emitter, fullAnswer);
            long llmTime = System.currentTimeMillis() - llmStart;

            long totalTime = System.currentTimeMillis() - startTime;

            String referencedDocsJson = JSON.toJSONString(referencedDocs.stream()
                    .map(doc -> {
                        Map<String, Object> docInfo = new HashMap<>();
                        docInfo.put("fileName", doc.getFileName());
                        docInfo.put("fileType", doc.getFileType());
                        docInfo.put("chunkIndex", doc.getChunkIndex());
                        docInfo.put("contentPreview", doc.getContent().substring(0, Math.min(100, doc.getContent().length())));
                        return docInfo;
                    })
                    .collect(Collectors.toList()));

            // 使用 MemoryService 统一管理：Redis + MySQL + ES
            memoryService.addConversation(sessionId, userId, question, 
                fullAnswer.toString(), referencedDocsJson, 
                (int)retrievalTime, (int)llmTime);

            log.info("【RAG完成】问题: [{}] | 总耗时: {}ms | 检索: {}ms | LLM: {}ms", 
                    question, totalTime, retrievalTime, llmTime);

        } catch (Exception e) {
            log.error("RAG处理失败 | Question: [{}] | Error: {}", question, e.getMessage(), e);
            emitter.completeWithError(e);
            
            String errorMsg = "处理失败: " + e.getMessage();
            memoryService.addConversation(sessionId, userId, question, 
                errorMsg, "[]", (int)(System.currentTimeMillis() - startTime), 0);
        }
    }

    private String buildPromptWithContext(String shortTermMemory, String context, String question) {
        StringBuilder prompt = new StringBuilder();

        if (shortTermMemory != null && !shortTermMemory.isEmpty()) {
            prompt.append("历史对话:\n").append(shortTermMemory).append("\n\n");
        }

        prompt.append("参考资料:\n").append(context).append("\n\n");
        prompt.append("问题：").append(question);

        return prompt.toString();
    }

    private void streamCallLlm(String prompt, SseEmitter emitter, StringBuilder fullAnswer) throws IOException {

        List<ChatMessage> conversation = Arrays.asList(
                ChatMessage.builder()
                        .role(ChatMessageRole.SYSTEM.value())
                        .content("你是知识库助手，仅根据以下资料回答，不要编造。如果资料中没有相关信息请说明。")
                        .build(),
                ChatMessage.builder()
                        .role(ChatMessageRole.USER.value())
                        .content(prompt)
                        .build()
        );

        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(chatModel)
                .stream(true)
                .messages(conversation)
                .maxTokens(65536)
                .temperature(0.7f)
                .build();
        ChatCompletionResponse response = client.chat().createChatCompletion(request);

        if (response.isSuccess()) {
            response.getFlowable().subscribe(
                    data -> {
                        try {
                            if (data.getChoices() != null && !data.getChoices().isEmpty()) {
                                Delta delta = data.getChoices().get(0).getDelta();
                                if (delta != null && delta.getContent() != null) {
                                    String content = delta.getContent();
                                    if (!content.isEmpty()) {
                                        fullAnswer.append(content);
                                        emitter.send(SseEmitter.event().name("message").data(content));
                                    }
                                }
                            }
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    },
                    error -> {
                        log.error("Stream error: {}", error.getMessage(), error);
                        emitter.completeWithError(error);
                    },
                    () -> {
                        log.info("Streaming response completed");
                        emitter.complete();
                    }
            );
        } else {
            String errorMsg = "Error: " + response.getMsg();
            fullAnswer.append(errorMsg);
            emitter.send(SseEmitter.event().name("error").data(errorMsg));
            emitter.complete();
        }
    }
}
