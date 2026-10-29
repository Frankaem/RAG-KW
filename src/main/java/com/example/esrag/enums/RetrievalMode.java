
package com.example.esrag.enums;

import lombok.Getter;

@Getter
public enum RetrievalMode {
    VECTOR("vector", "纯向量检索"),
    KEYWORD("keyword", "纯关键词检索"),
    HYBRID("hybrid", "混合检索");

    private final String code;
    private final String description;

    RetrievalMode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static RetrievalMode fromCode(String code) {
        for (RetrievalMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return HYBRID; // 默认使用混合检索
    }
}
