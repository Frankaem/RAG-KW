package com.example.esrag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversations")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("question")
    private String question;

    @TableField("answer")
    private String answer;

    @TableField("referenced_docs")
    private String referencedDocs;

    @TableField("retrieval_time_ms")
    private Integer retrievalTimeMs;

    @TableField("llm_time_ms")
    private Integer llmTimeMs;

    @TableField("feedback")
    private Integer feedback;

    @TableField("feedback_comment")
    private String feedbackComment;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
