package com.example.esrag.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * RAG 文本切片器（递归分块策略）
 * 支持多语言、多层次语义完整性保护
 */
public class TextSplitter {

    private static final int MAX_CHUNK = 800;
    private static final int MIN_CHUNK = 50;
    private static final int OVERLAP = 100; // chunk重叠长度，保持边界语义连贯

    // 多层级分隔符（优先级从高到低）
    private static final List<Pattern> SEPARATORS = List.of(
        Pattern.compile("\n\n+"),           // 1. 段落分隔
        Pattern.compile("(?<=[。！？；])"),   // 2. 中文句子
        Pattern.compile("(?<=[.!?;])\\s+"), // 3. 英文句子
        Pattern.compile("(?<=[，,])"),       // 4. 逗号/顿号（最后手段）
        Pattern.compile("\\s+")             // 5. 空格（兜底）
    );

    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        text = normalizeText(text);
        List<String> chunks = recursiveSplit(text, 0);
        
        // 后处理：合并过小的chunk
        return mergeSmallChunks(chunks);
    }

    /**
     * 递归分块：从粗粒度到细粒度逐级尝试
     */
    private static List<String> recursiveSplit(String text, int level) {
        List<String> chunks = new ArrayList<>();

        // 如果文本已经足够小，直接返回
        if (text.length() <= MAX_CHUNK) {
            chunks.add(text);
            return chunks;
        }

        // 如果已经达到最细粒度，强制截断
        if (level >= SEPARATORS.size()) {
            return forceSplit(text);
        }

        Pattern separator = SEPARATORS.get(level);
        String[] parts = separator.split(text);

        // 如果分隔符无效（无法分割），尝试下一级
        if (parts.length <= 1) {
            return recursiveSplit(text, level + 1);
        }

        StringBuilder currentChunk = new StringBuilder();
        
        for (String part : parts) {
            if (part.isEmpty()) continue;

            // 添加分隔符本身（保持语义完整）
            String partWithSep = addSeparator(part, separator);

            if (currentChunk.length() + partWithSep.length() <= MAX_CHUNK) {
                currentChunk.append(partWithSep);
            } else {
                // 当前chunk已满
                if (currentChunk.length() >= MIN_CHUNK) {
                    chunks.add(currentChunk.toString().trim());
                    // 添加重叠部分
                    String overlap = getOverlap(currentChunk.toString(), partWithSep);
                    currentChunk.setLength(0);
                    if (!overlap.isEmpty()) {
                        currentChunk.append(overlap);
                    }
                }
                currentChunk.append(partWithSep);
            }
        }

        // 处理最后一个chunk
        if (currentChunk.length() >= MIN_CHUNK) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 获取重叠部分（保持语义连贯）
     */
    private static String getOverlap(String previousChunk, String nextPart) {
        if (OVERLAP <= 0 || previousChunk.length() < OVERLAP) {
            return "";
        }
        
        // 从previousChunk末尾提取OVERLAP长度的文本
        String overlap = previousChunk.substring(previousChunk.length() - OVERLAP);
        
        // 确保不切断单词/中文词
        int cutPoint = findSafeCutPoint(overlap);
        return overlap.substring(cutPoint);
    }

    /**
     * 找到安全的切割点（不切断词语）
     */
    private static int findSafeCutPoint(String text) {
        // 优先在空格处切割
        int spaceIdx = text.indexOf(' ');
        if (spaceIdx > 0 && spaceIdx < text.length() / 2) {
            return spaceIdx + 1;
        }
        
        // 否则在标点处切割
        for (int i = 0; i < Math.min(text.length() / 2, 50); i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == '、' || c == ',' || c == '.') {
                return i + 1;
            }
        }
        
        return 0; // 默认从头开始
    }

    /**
     * 强制分割超长文本
     */
    private static List<String> forceSplit(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK, text.length());
            
            // 尝试在安全位置切割
            if (end < text.length()) {
                int safeEnd = findSafeCutPoint(text.substring(start, end));
                if (safeEnd > 0) {
                    end = start + safeEnd;
                }
            }
            
            chunks.add(text.substring(start, end).trim());
            start = end;
        }
        
        return chunks;
    }

    /**
     * 合并过小的chunk
     */
    private static List<String> mergeSmallChunks(List<String> chunks) {
        if (chunks.size() <= 1) {
            return chunks;
        }

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String chunk : chunks) {
            if (current.length() + chunk.length() <= MAX_CHUNK && 
                current.length() < MIN_CHUNK) {
                current.append(chunk).append("\n");
            } else {
                if (current.length() >= MIN_CHUNK) {
                    merged.add(current.toString().trim());
                }
                current.setLength(0);
                current.append(chunk).append("\n");
            }
        }

        if (current.length() >= MIN_CHUNK) {
            merged.add(current.toString().trim());
        }

        return merged;
    }

    /**
     * 规范化文本
     */
    private static String normalizeText(String text) {
        // 统一换行符
        text = text.replaceAll("\r\n", "\n");
        // 去除多余空白
        text = text.replaceAll("\n[ \t]+", "\n");
        // 压缩连续空行
        text = text.replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }

    /**
     * 恢复分隔符（保持原文格式）
     */
    private static String addSeparator(String part, Pattern separator) {
        String patternStr = separator.pattern();
        
        if (patternStr.contains("\\n")) {
            return part + "\n\n";
        } else if (patternStr.contains("。") || patternStr.contains("！")) {
            return part; // 中文标点在lookbehind中已保留
        } else if (patternStr.contains("[.!?;]")) {
            return part + " ";
        }
        
        return part;
    }
}