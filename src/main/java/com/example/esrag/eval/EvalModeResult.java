package com.example.esrag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 某个检索模式（vector/keyword/hybrid）在整个测试集上的汇总结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalModeResult {
    private String mode;
    private int testCaseCount;
    /** 平均召回率 Recall@k */
    private double avgRecall;
    /** 命中率 HitRate@k：至少命中一个标准答案的用例占比 */
    private double avgHitRate;
    /** 平均倒数排名 MRR */
    private double avgMrr;
    /** 平均检索耗时（毫秒，不含向量化） */
    private double avgLatencyMs;
    private List<CaseEvaluation> cases;
}