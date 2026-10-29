package com.example.esrag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一条检索评估测试用例。
 * expectedKeywords 比 expectedChunkIds 更抗分块变化：
 * 评估器会把"同时包含所有关键词的 chunk"解析为标准答案 chunk。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalTestCase {
    private String id;
    private String question;
    /** 可选：精确指定标准答案 chunk id（优先级高于 keywords） */
    private List<String> expectedChunkIds;
    /** 可选：标准答案 chunk 必须包含的关键词（交集匹配） */
    private List<String> expectedKeywords;
    private String note;
}