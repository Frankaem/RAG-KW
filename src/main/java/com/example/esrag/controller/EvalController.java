package com.example.esrag.controller;

import com.example.esrag.eval.RetrievalEvaluationReport;
import com.example.esrag.eval.RetrievalEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 检索评估接口
 * POST /api/eval/run - 运行评估，返回各检索模式对比指标 + Markdown 对比表
 */
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
@CrossOrigin
public class EvalController {

    private final RetrievalEvaluator retrievalEvaluator;

    @PostMapping("/run")
    public Map<String, Object> run() throws Exception {
        RetrievalEvaluationReport report = retrievalEvaluator.runEvaluation();
        return Map.of(
                "topK", report.getTopK(),
                "testCaseCount", report.getTestCaseCount(),
                "generatedAt", String.valueOf(report.getGeneratedAt()),
                "modeResults", report.getModeResults(),
                "markdownTable", report.getMarkdownTable()
        );
    }
}