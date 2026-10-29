package com.example.esrag.agent;

/**
 * 一次工具调用的执行结果（含耗时，用于追踪与面试讲解）
 */
public record ToolResult(String name, String arguments, String result, long durationMs) {
}