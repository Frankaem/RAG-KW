package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.esrag.entity.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private ZhipuAiClient llmClient;

    @Mock
    private ConversationLogService conversationLogService;

    @InjectMocks
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        // 注入 chatModel 配置
        ReflectionTestUtils.setField(memoryService, "chatModel", "glm-4-flash");
        
        // 【修复】加上 lenient()，防止未使用的 stubbing 报错
        lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);

        // 【修复】让 LLM 调用返回 null，触发降级逻辑
        lenient().when(llmClient.chat()).thenReturn(null); 

        // 【修复】给 Embedding 一个假结果
        lenient().when(embeddingService.embed(anyString())).thenReturn(new float[512]);
    }


    @Test
    void testGetShortTermMemory() {
        // 1. 写剧本：模拟 Redis 返回两条历史记录
        // 注意：range 方法有多个参数，如果用了 anyString()，其他参数也要用匹配器
        when(listOperations.range(anyString(), anyLong(), anyLong()))
                .thenReturn(Arrays.asList("User: Hi", "AI: Hello"));

        // 2. 执行
        String memory = memoryService.getShortTermMemory(1L, "session_1");

        // 3. 验证
        assertTrue(memory.contains("Hi"));
        // 验证时也要保持参数风格一致，全部使用匹配器
        verify(redisTemplate, times(1)).opsForList();
    }

    @Test
    void testAddConversation() {
        // 1. 写剧本：模拟 Redis 操作
        when(listOperations.rightPush(anyString(), anyString())).thenReturn(1L);
        when(listOperations.size(anyString())).thenReturn(5L); // 未达到上限，不触发 trim
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);

        // 模拟 Service 层保存对话日志
        when(conversationLogService.saveConversationLog(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(1L);

        // 2. 执行：添加对话
        memoryService.addConversation("session_1", 1L, "Q", "A", "[]", 10, 20);

        // 3. 查考勤：验证是否调用了 Redis 和 MySQL
        verify(listOperations, times(1)).rightPush(anyString(), anyString());
        verify(conversationLogService, times(1)).saveConversationLog(anyLong(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void testClearShortTermMemory() {
        // 1. 执行：清除记忆
        memoryService.clearShortTermMemory(1L, "session_1");

        // 2. 验证：Redis delete 被调用
        verify(redisTemplate, times(1)).delete(anyString());
    }

    @Test
    void testRestoreSessionFromMySQL() {
        // 模拟 Redis 里没有数据
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        // 模拟 MySQL 里有数据
        Conversation conv = new Conversation();
        when(conversationLogService.getConversationLogsBySessionIdAndUserId(anyString(), anyLong()))
                .thenReturn(Arrays.asList(conv));

        memoryService.restoreSessionFromMySQL("session_1", 1L);

        // 验证是否尝试从 MySQL 捞数据并塞回 Redis
        verify(conversationLogService, times(1)).getConversationLogsBySessionIdAndUserId(anyString(), anyLong());
        verify(listOperations, times(1)).rightPushAll(anyString(), anyList());
    }
}