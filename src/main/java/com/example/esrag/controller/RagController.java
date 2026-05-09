package com.example.esrag.controller;

import com.example.esrag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@CrossOrigin
public class RagController {
    /**
     * RagController (问答相关)
     * ├─ POST /api/rag/ask          - 普通问答（支持三层记忆）
     * ├─ GET  /api/rag/ask-stream   - 流式问答（支持三层记忆）
     */
    private final RagService ragService;

    /**
     * 普通问答接口（支持三层记忆）
     * 
     * @param req 请求体，包含 question、userId（可选）、sessionId（可选）
     * @return AI 回答
     */
    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> req) {
        String question = req.get("question");
        Long userId = req.containsKey("userId") ? Long.parseLong(req.get("userId")) : null;
        String sessionId = req.getOrDefault("sessionId", "default-session");
        
        String answer = ragService.ask(question, userId, sessionId);
        return Map.of("answer", answer);
    }

    /**
     * 流式问答接口（支持三层记忆）
     * 
     * @param question  用户问题
     * @param sessionId 会话ID（可选，默认 default-session）
     * @param userId    用户ID（可选，默认 1）
     * @return SSE 流
     */
    @GetMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter askStream(
            @RequestParam String question,
            @RequestParam(required = false, defaultValue = "default-session") String sessionId,
            @RequestParam(required = false, defaultValue = "1") Long userId) {

        SseEmitter emitter = new SseEmitter(120000L);

        if (question == null || question.isEmpty()) {
            emitter.completeWithError(new IllegalArgumentException("question 参数不能为空"));
            return emitter;
        }

        try {
            ragService.askStream(question, sessionId, userId, emitter);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }

        return emitter;
    }
}
