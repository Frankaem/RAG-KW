package com.example.esrag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_chunks_meta")
public class DocumentChunkMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("document_id")
    private Long documentId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("es_chunk_id")
    private String esChunkId;

    @TableField("content_preview")
    private String contentPreview;

    @TableField("char_count")
    private Integer charCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
