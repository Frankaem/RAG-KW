package com.example.esrag.eval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条用例在某个检索模式下的评估结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseEvaluation {
    private String caseId;
    private String question;
    private List<String> expectedChunkIds;
    private List<String> retrievedChunkIds;
    /** 是否命中（至少一个标准答案出现在检索结果中） */
    private boolean hit;
    /** 召回率：命中的标准答案数 / 标准答案总数 */
    private double recall;
    /** 首个标准答案的倒数排名，未命中为 0 */
    private double reciprocalRank;
}