package com.example.esrag.controller;

import com.example.esrag.service.ConversationLogService;
import com.example.esrag.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
@CrossOrigin
public class SessionController {

    private final ConversationLogService conversationLogService;
    private final MemoryService memoryService;

    /**
     * 查询会话历史对话日志
     * ⚠️ 必须传入 userId 以确保数据隔离
     */
    @GetMapping("/history/{sessionId}")
    public Map<String, Object> getHistory(
            @PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "1") Long userId) {

        Map<String, Object> result = new HashMap<>();
        try {
            // ✅ 使用带 userId 校验的方法，确保数据隔离
            var conversations = conversationLogService.getConversationLogsBySessionIdAndUserId(sessionId, userId);
            result.put("success", true);
            result.put("data", conversations);
            result.put("count", conversations.size());
            result.put("sessionId", sessionId);
            result.put("userId", userId);
        } catch (Exception e) {
            log.error("查询会话对话日志失败 | SessionId: {} | UserId: {}", sessionId, userId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 更新对话反馈
     */
    @PostMapping("/feedback")
    public Map<String, Object> updateFeedback(
            @RequestParam Long conversationId,
            @RequestParam Integer feedback,
            @RequestParam(required = false) String comment,
            @RequestParam(required = false, defaultValue = "1") Long userId) {

        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = conversationLogService.updateFeedbackWithPermission(conversationId, userId, feedback, comment);
            result.put("success", success);
            result.put("message", success ? "反馈提交成功" : "反馈提交失败");
        } catch (SecurityException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("更新反馈失败 | ConversationId: {} | UserId: {}", conversationId, userId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }


    /**
     * 清除会话的短期记忆（Redis）
     */
    @DeleteMapping("/clear/{sessionId}")
    public Map<String, Object> clearShortTermMemory(
            @PathVariable String sessionId,
            @RequestParam(required = false, defaultValue = "1") Long userId) {

        Map<String, Object> result = new HashMap<>();
        try {
            // TODO: 注入 MemoryService 并调用 clearShortTermMemory
            memoryService.clearShortTermMemory(userId, sessionId);
            result.put("success", true);
            result.put("message", "会话记忆已清除");
        } catch (Exception e) {
            log.error("清除会话记忆失败 | SessionId: {}", sessionId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
