package com.example.esrag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.esrag.entity.Conversation;
import com.example.esrag.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;

    /**
     * 保存单条对话记录
     * 
     * @param userId           用户ID
     * @param sessionId        会话ID
     * @param question         用户问题
     * @param answer           AI回答
     * @param referencedDocs   参考文档（JSON格式）
     * @param retrievalTimeMs  检索耗时（毫秒）
     * @param llmTimeMs        LLM调用耗时（毫秒）
     * @return 保存后的对话记录ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveConversation(Long userId, String sessionId, String question, 
                                  String answer, String referencedDocs,
                                  Integer retrievalTimeMs, Integer llmTimeMs) {
        try {
            Conversation conversation = new Conversation();
            conversation.setUserId(userId);
            conversation.setSessionId(sessionId);
            conversation.setQuestion(question);
            conversation.setAnswer(answer);
            conversation.setReferencedDocs(referencedDocs);
            conversation.setRetrievalTimeMs(retrievalTimeMs);
            conversation.setLlmTimeMs(llmTimeMs);
            
            // createdAt 由 MyMetaObjectHandler 自动填充，无需手动设置
            
            conversationMapper.insert(conversation);
            
            log.info("对话记录保存成功 | ID: {} | SessionId: {} | UserId: {}", 
                    conversation.getId(), sessionId, userId);
            
            return conversation.getId();
            
        } catch (Exception e) {
            log.error("保存对话记录失败 | SessionId: {} | UserId: {} | Error: {}", sessionId, userId, e.getMessage(), e);
            throw new RuntimeException("保存对话记录失败", e);
        }
    }

    /**
     * 批量保存对话记录（适用于批量导入场景）
     * 
     * @param conversations 对话记录列表
     * @return 成功保存的数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int batchSaveConversations(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            log.warn("批量保存对话记录：列表为空");
            return 0;
        }
        
        try {
            int successCount = 0;
            for (Conversation conversation : conversations) {
                conversationMapper.insert(conversation);
                successCount++;
            }
            
            log.info("批量保存对话记录成功 | 数量: {}", successCount);
            return successCount;
            
        } catch (Exception e) {
            log.error("批量保存对话记录失败 | Error: {}", e.getMessage(), e);
            throw new RuntimeException("批量保存对话记录失败", e);
        }
    }

    /**
     * 根据会话ID和用户ID查询历史对话（按时间升序）
     * 增加 userId 校验，确保数据隔离
     * 
     * @param sessionId 会话ID
     * @param userId    用户ID
     * @return 对话记录列表
     */
    public List<Conversation> getConversationsBySessionIdAndUserId(String sessionId, Long userId) {
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Conversation::getSessionId, sessionId)
                   .eq(Conversation::getUserId, userId)
                   .orderByAsc(Conversation::getCreatedAt);
            
            List<Conversation> conversations = conversationMapper.selectList(wrapper);
            log.debug("查询会话历史 | SessionId: {} | UserId: {} | 记录数: {}", sessionId, userId, conversations.size());
            return conversations;
        } catch (Exception e) {
            log.error("查询会话历史失败 | SessionId: {} | UserId: {} | Error: {}", sessionId, userId, e.getMessage(), e);
            throw new RuntimeException("查询会话历史失败", e);
        }
    }

    /**
     * 根据会话ID查询历史对话（按时间升序）
     * ⚠️ 注意：此方法不校验 userId，仅用于内部管理场景
     * 
     * @param sessionId 会话ID
     * @return 对话记录列表
     */
    public List<Conversation> getConversationsBySessionId(String sessionId) {
        try {
            List<Conversation> conversations = conversationMapper.selectBySessionId(sessionId);
            log.debug("查询会话历史（无用户校验） | SessionId: {} | 记录数: {}", sessionId, conversations.size());
            return conversations;
        } catch (Exception e) {
            log.error("查询会话历史失败 | SessionId: {} | Error: {}", sessionId, e.getMessage(), e);
            throw new RuntimeException("查询会话历史失败", e);
        }
    }

    /**
     * 查询用户最近的对话记录
     * 
     * @param userId 用户ID
     * @param limit  返回数量限制
     * @return 对话记录列表（按创建时间降序）
     */
    public List<Conversation> getRecentConversations(Long userId, int limit) {
        try {
            List<Conversation> conversations = conversationMapper.selectRecentByUserId(userId, limit);
            log.debug("查询用户最近对话 | UserId: {} | Limit: {} | 记录数: {}", userId, limit, conversations.size());
            return conversations;
        } catch (Exception e) {
            log.error("查询用户最近对话失败 | UserId: {} | Error: {}", userId, e.getMessage(), e);
            throw new RuntimeException("查询用户最近对话失败", e);
        }
    }

    /**
     * 根据时间范围统计用户的对话数量
     * 
     * @param userId 用户ID
     * @param start  开始时间
     * @param end    结束时间
     * @return 对话数量
     */
    public long countConversationsByTimeRange(Long userId, LocalDateTime start, LocalDateTime end) {
        try {
            long count = conversationMapper.countByUserIdAndTimeRange(userId, start, end);
            log.debug("统计对话数量 | UserId: {} | TimeRange: {} ~ {} | Count: {}", 
                    userId, start, end, count);
            return count;
        } catch (Exception e) {
            log.error("统计对话数量失败 | UserId: {} | Error: {}", userId, e.getMessage(), e);
            throw new RuntimeException("统计对话数量失败", e);
        }
    }

    /**
     * 根据对话ID查询单条记录
     * 
     * @param id 对话ID
     * @return 对话记录
     */
    public Conversation getConversationById(Long id) {
        try {
            Conversation conversation = conversationMapper.selectById(id);
            if (conversation == null) {
                log.warn("对话记录不存在 | ID: {}", id);
            }
            return conversation;
        } catch (Exception e) {
            log.error("查询对话记录失败 | ID: {} | Error: {}", id, e.getMessage(), e);
            throw new RuntimeException("查询对话记录失败", e);
        }
    }

    /**
     * 更新对话反馈
     * 
     * @param id             对话ID
     * @param feedback       反馈（1:点赞, -1:点踩, 0:无反馈）
     * @param feedbackComment 反馈评论
     * @return 是否更新成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updateFeedback(Long id, Integer feedback, String feedbackComment) {
        try {
            Conversation conversation = conversationMapper.selectById(id);
            if (conversation == null) {
                log.warn("对话记录不存在，无法更新反馈 | ID: {}", id);
                return false;
            }
            
            conversation.setFeedback(feedback);
            conversation.setFeedbackComment(feedbackComment);
            
            int rows = conversationMapper.updateById(conversation);
            
            if (rows > 0) {
                log.info("对话反馈更新成功 | ID: {} | Feedback: {}", id, feedback);
                return true;
            } else {
                log.warn("对话反馈更新失败 | ID: {}", id);
                return false;
            }
            
        } catch (Exception e) {
            log.error("更新对话反馈失败 | ID: {} | Error: {}", id, e.getMessage(), e);
            throw new RuntimeException("更新对话反馈失败", e);
        }
    }

    /**
     * 更新对话反馈（带权限校验）
     * 
     * @param id             对话ID
     * @param userId         用户ID（用于权限校验）
     * @param feedback       反馈（1:点赞, -1:点踩, 0:无反馈）
     * @param feedbackComment 反馈评论
     * @return 是否更新成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean updateFeedbackWithPermission(Long id, Long userId, Integer feedback, String feedbackComment) {
        try {
            Conversation conversation = conversationMapper.selectById(id);
            if (conversation == null) {
                log.warn("对话记录不存在，无法更新反馈 | ID: {}", id);
                return false;
            }
            
            // ✅ 权限校验：确保只能更新自己的对话
            if (!conversation.getUserId().equals(userId)) {
                log.warn("无权更新此对话的反馈 | ID: {} | OwnerUserId: {} | RequestUserId: {}", 
                    id, conversation.getUserId(), userId);
                throw new SecurityException("无权操作此对话");
            }
            
            conversation.setFeedback(feedback);
            conversation.setFeedbackComment(feedbackComment);
            
            int rows = conversationMapper.updateById(conversation);
            
            if (rows > 0) {
                log.info("对话反馈更新成功 | ID: {} | UserId: {} | Feedback: {}", id, userId, feedback);
                return true;
            } else {
                log.warn("对话反馈更新失败 | ID: {}", id);
                return false;
            }
            
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            log.error("更新对话反馈失败 | ID: {} | Error: {}", id, e.getMessage(), e);
            throw new RuntimeException("更新对话反馈失败", e);
        }
    }

    /**
     * 删除指定会话的所有对话记录（软删除）
     * 
     * @param sessionId 会话ID
     * @return 删除的记录数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteConversationsBySessionId(String sessionId) {
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Conversation::getSessionId, sessionId);
            
            int rows = conversationMapper.delete(wrapper);
            
            log.info("删除会话对话记录 | SessionId: {} | 删除数量: {}", sessionId, rows);
            return rows;
            
        } catch (Exception e) {
            log.error("删除会话对话记录失败 | SessionId: {} | Error: {}", sessionId, e.getMessage(), e);
            throw new RuntimeException("删除会话对话记录失败", e);
        }
    }

    /**
     * 查询用户在指定时间段内的对话详情
     * 
     * @param userId 用户ID
     * @param start  开始时间
     * @param end    结束时间
     * @return 对话记录列表
     */
    public List<Conversation> getConversationsByTimeRange(Long userId, LocalDateTime start, LocalDateTime end) {
        try {
            LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Conversation::getUserId, userId)
                   .ge(Conversation::getCreatedAt, start)
                   .le(Conversation::getCreatedAt, end)
                   .orderByDesc(Conversation::getCreatedAt);
            
            List<Conversation> conversations = conversationMapper.selectList(wrapper);
            
            log.debug("查询时间范围内对话 | UserId: {} | TimeRange: {} ~ {} | 记录数: {}", 
                    userId, start, end, conversations.size());
            
            return conversations;
            
        } catch (Exception e) {
            log.error("查询时间范围内对话失败 | UserId: {} | Error: {}", userId, e.getMessage(), e);
            throw new RuntimeException("查询时间范围内对话失败", e);
        }
    }
}