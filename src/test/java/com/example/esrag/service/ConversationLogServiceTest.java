package com.example.esrag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.esrag.entity.Conversation;
import com.example.esrag.mapper.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对话日志服务测试
 *
 * 【测试范围】
 * - 对话日志的持久化存储（MySQL）
 * - 历史查询与统计分析
 * - 用户反馈收集
 */
@ExtendWith(MockitoExtension.class)
class ConversationLogServiceTest {

    @Mock
    private ConversationMapper conversationMapper;

    @InjectMocks
    private ConversationLogService conversationLogService;

    private Conversation testConversation;
    private Long userId = 1L;
    private String sessionId = "session_1";

    @BeforeEach
    void setUp() {
        testConversation = new Conversation();
        testConversation.setId(100L);
        testConversation.setUserId(userId);
        testConversation.setSessionId(sessionId);
        testConversation.setQuestion("测试问题");
        testConversation.setAnswer("测试回答");
        testConversation.setReferencedDocs("[{\"fileName\":\"test.pdf\"}]");
        testConversation.setRetrievalTimeMs(100);
        testConversation.setLlmTimeMs(200);
        testConversation.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void testSaveConversationLog_Success() {
        // Mock insert 时，同时设置 ID 到 conversation 对象
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conv = invocation.getArgument(0);
            conv.setId(100L); // 模拟数据库自动生成 ID
            return 1;
        });

        Long id = conversationLogService.saveConversationLog(
            userId, sessionId, "问题", "回答", "[]", 100, 200
        );

        assertNotNull(id);
        assertEquals(100L, id);
        verify(conversationMapper, times(1)).insert(any(Conversation.class));
    }

    @Test
    void testSaveConversationLog_Exception() {
        when(conversationMapper.insert(any(Conversation.class)))
            .thenThrow(new RuntimeException("数据库错误"));

        assertThrows(RuntimeException.class, () -> {
            conversationLogService.saveConversationLog(userId, sessionId, "问题", "回答", "[]", 100, 200);
        });
    }

    @Test
    void testBatchSaveConversationLogs_Success() {
        List<Conversation> conversations = Arrays.asList(
            createConversation(1L),
            createConversation(2L)
        );
        when(conversationMapper.insert(any(Conversation.class))).thenReturn(1);

        int count = conversationLogService.batchSaveConversationLogs(conversations);

        assertEquals(2, count);
        verify(conversationMapper, times(2)).insert(any(Conversation.class));
    }

    @Test
    void testBatchSaveConversationLogs_EmptyList() {
        int count = conversationLogService.batchSaveConversationLogs(Collections.emptyList());

        assertEquals(0, count);
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    @Test
    void testBatchSaveConversationLogs_NullList() {
        int count = conversationLogService.batchSaveConversationLogs(null);

        assertEquals(0, count);
    }

    @Test
    void testGetConversationLogsBySessionIdAndUserId_Success() {
        List<Conversation> expected = Collections.singletonList(testConversation);
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(expected);

        List<Conversation> result = conversationLogService.getConversationLogsBySessionIdAndUserId(sessionId, userId);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(conversationMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void testGetConversationLogsBySessionIdAndUserId_Exception() {
        when(conversationMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenThrow(new RuntimeException("查询失败"));

        assertThrows(RuntimeException.class, () -> {
            conversationLogService.getConversationLogsBySessionIdAndUserId(sessionId, userId);
        });
    }

    @Test
    void testGetConversationLogsBySessionId_Success() {
        List<Conversation> expected = Collections.singletonList(testConversation);
        when(conversationMapper.selectBySessionId(sessionId)).thenReturn(expected);

        List<Conversation> result = conversationLogService.getConversationLogsBySessionId(sessionId);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(conversationMapper, times(1)).selectBySessionId(sessionId);
    }

    @Test
    void testGetRecentConversationLogs_Success() {
        List<Conversation> expected = Collections.singletonList(testConversation);
        when(conversationMapper.selectRecentByUserId(userId, 10)).thenReturn(expected);

        List<Conversation> result = conversationLogService.getRecentConversationLogs(userId, 10);

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(conversationMapper, times(1)).selectRecentByUserId(userId, 10);
    }

    @Test
    void testCountConversationLogsByTimeRange_Success() {
        LocalDateTime start = LocalDateTime.now().minusDays(7);
        LocalDateTime end = LocalDateTime.now();
        when(conversationMapper.countByUserIdAndTimeRange(userId, start, end)).thenReturn(5L);

        long count = conversationLogService.countConversationLogsByTimeRange(userId, start, end);

        assertEquals(5L, count);
        verify(conversationMapper, times(1)).countByUserIdAndTimeRange(userId, start, end);
    }

    @Test
    void testGetConversationLogById_Found() {
        when(conversationMapper.selectById(100L)).thenReturn(testConversation);

        Conversation result = conversationLogService.getConversationLogById(100L);

        assertNotNull(result);
        assertEquals(100L, result.getId());
        verify(conversationMapper, times(1)).selectById(100L);
    }

    @Test
    void testGetConversationLogById_NotFound() {
        when(conversationMapper.selectById(999L)).thenReturn(null);

        Conversation result = conversationLogService.getConversationLogById(999L);

        assertNull(result);
    }

    @Test
    void testUpdateFeedback_Success() {
        when(conversationMapper.selectById(100L)).thenReturn(testConversation);
        when(conversationMapper.updateById(any(Conversation.class))).thenReturn(1);

        boolean result = conversationLogService.updateFeedback(100L, 1, "很好的回答");

        assertTrue(result);
        verify(conversationMapper, times(1)).updateById(any(Conversation.class));
    }

    @Test
    void testUpdateFeedback_RecordNotFound() {
        when(conversationMapper.selectById(999L)).thenReturn(null);

        boolean result = conversationLogService.updateFeedback(999L, 1, "评论");

        assertFalse(result);
    }

    @Test
    void testUpdateFeedbackWithPermission_Success() {
        when(conversationMapper.selectById(100L)).thenReturn(testConversation);
        when(conversationMapper.updateById(any(Conversation.class))).thenReturn(1);

        boolean result = conversationLogService.updateFeedbackWithPermission(100L, userId, 1, "很好");

        assertTrue(result);
        assertEquals(1, testConversation.getFeedback());
    }

    @Test
    void testUpdateFeedbackWithPermission_DifferentUser() {
        when(conversationMapper.selectById(100L)).thenReturn(testConversation);

        assertThrows(SecurityException.class, () -> {
            conversationLogService.updateFeedbackWithPermission(100L, 999L, 1, "评论");
        });
    }

    @Test
    void testDeleteConversationLogsBySessionId_Success() {
        when(conversationMapper.delete(any(LambdaQueryWrapper.class))).thenReturn(3);

        int count = conversationLogService.deleteConversationLogsBySessionId(sessionId);

        assertEquals(3, count);
        verify(conversationMapper, times(1)).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void testGetConversationLogsByTimeRange_Success() {
        List<Conversation> expected = Collections.singletonList(testConversation);
        LocalDateTime start = LocalDateTime.now().minusDays(7);
        LocalDateTime end = LocalDateTime.now();

        when(conversationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(expected);

        List<Conversation> result = conversationLogService.getConversationLogsByTimeRange(userId, start, end);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    // Helper method
    private Conversation createConversation(Long id) {
        Conversation conv = new Conversation();
        conv.setId(id);
        conv.setUserId(userId);
        conv.setSessionId(sessionId);
        conv.setQuestion("问题" + id);
        conv.setAnswer("回答" + id);
        return conv;
    }
}