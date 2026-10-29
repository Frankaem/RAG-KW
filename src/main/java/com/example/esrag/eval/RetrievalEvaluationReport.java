package com.example.esrag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索评估报告：包含各模式对比指标和可直接贴进 README 的 Markdown 表格
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalEvaluationReport {
    private int topK;
    private int testCaseCount;
    private String demoDocumentName;
    private LocalDateTime generatedAt;
    private List<EvalModeResult> modeResults;
    /** 对比表（Markdown），可直接展示 */
    private String markdownTable;
}