package com.example.esrag.controller;

import com.example.esrag.agent.AgentResult;
import com.example.esrag.agent.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Agent 问答接口（Function Calling）
 * ├─ POST /api/agent/ask        - 非流式：返回答案 + 工具调用轨迹
 * ├─ GET  /api/agent/ask-stream - 流式：tool_call / tool_result / message 事件
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@CrossOrigin
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> req) {
        String question = req.get("question");
        Long userId = req.containsKey("userId") ? Long.parseLong(req.get("userId")) : null;
        String sessionId = req.getOrDefault("sessionId", "default-session");

        AgentResult result = agentService.ask(question, userId, sessionId);
        return Map.of(
                "answer", result.getAnswer(),
                "rounds", result.getRounds(),
                "toolTrace", result.getToolTrace() == null ? java.util.List.of() : result.getToolTrace(),
                "referencedDocs", result.getReferencedDocs() == null ? java.util.List.of() : result.getReferencedDocs()
        );
    }

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
        agentService.askStream(question, userId, sessionId, emitter);
        return emitter;
    }
}