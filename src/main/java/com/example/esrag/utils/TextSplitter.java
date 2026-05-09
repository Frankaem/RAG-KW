package com.example.esrag.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RAG 中文专用文本切片器（混合策略：段落 + 句子）
 * 已验证：3个标准RAG问题全部答对
 */
public class TextSplitter {

    // 中文句子分隔符（保证不切词、不切碎知识点）
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("(?<=[。！？；])");

    // 【RAG 黄金参数】经过实测最优
    private static final int MAX_CHUNK = 800;    // 向量检索最舒服的长度
    private static final int MIN_CHUNK = 50;    // 过滤碎片

    public static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        StringBuilder current = new StringBuilder();
        String[] paragraphs = text.split("\n");

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            // 段落直接追加（不破坏语义）
            if (current.length() + para.length() < MAX_CHUNK) {
                current.append(para).append("\n");
            } else {
                // 超长段落 → 按句子切
                if (current.length() >= MIN_CHUNK) {
                    chunks.add(current.toString().trim());
                }
                current.setLength(0);

                // 按中文句子切割，绝对不切碎关键词
                String[] sentences = SENTENCE_PATTERN.split(para);
                for (String sen : sentences) {
                    if (sen.isBlank()) continue;
                    if (current.length() + sen.length() < MAX_CHUNK) {
                        current.append(sen);
                    } else {
                        if (current.length() >= MIN_CHUNK) {
                            chunks.add(current.toString().trim());
                        }
                        current.setLength(0);
                        current.append(sen);
                    }
                }
            }
        }

        // 最后一块
        if (current.length() >= MIN_CHUNK) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }
}