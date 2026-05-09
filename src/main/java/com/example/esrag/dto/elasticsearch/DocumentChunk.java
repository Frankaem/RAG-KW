
package com.example.esrag.dto.elasticsearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDateTime;

@Data
@Document(indexName = "doc_chunks")
@Setting(settingPath = "/elasticsearch/doc_chunks_settings.json")
public class DocumentChunk {

    @Id
    private String id;  // 格式: doc_{documentId}_chunk_{chunkIndex}

    // ========== 核心检索字段 ==========
    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String content;  // ✅ 支持中文全文检索

    @Field(type = FieldType.Dense_Vector, dims = 512)
    private float[] vector;  // ✅ 向量相似度检索

    // ========== 过滤字段（用于精确匹配）==========
    @Field(type = FieldType.Long)
    private Long documentId;  // ✅ 关联MySQL文档ID

    @Field(type = FieldType.Keyword)
    private String fileMd5;  // ✅ 用于去重

    // ========== 元数据字段（用于展示和排序）==========
    @Field(type = FieldType.Text)
    private String fileName;  // ✅ 改为Text，支持模糊搜索

    @Field(type = FieldType.Keyword)
    private String fileType;

    @Field(type = FieldType.Integer)
    private Integer chunkIndex;  // ✅ 切片序号

    @Field(type = FieldType.Integer)
    private Integer totalPages;

    @Field(type = FieldType.Integer)
    private Integer totalChunks;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime uploadTime;  // ✅ 改为Date类型，支持时间范围查询

    @Field(type = FieldType.Long)
    private Long fileSize;

    @Field(type = FieldType.Keyword)
    private String uploadUserId;  // ✅ 支持多租户隔离

    // ========== 辅助字段 ==========
    @Field(type = FieldType.Integer)
    private Integer charCount;  // 字符数，用于统计
}
