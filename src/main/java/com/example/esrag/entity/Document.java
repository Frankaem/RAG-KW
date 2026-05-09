package com.example.esrag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;

@Data
@TableName("documents")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("file_md5")
    private String fileMd5;

    @TableField("total_pages")
    private Integer totalPages;

    @TableField("total_chunks")
    private Integer totalChunks;

    @TableField("upload_user_id")
    private Long uploadUserId;

    @TableField("upload_time")
    private LocalDateTime uploadTime;

    @TableField("status")
    private Integer status;

    @TableField("task_id")
    private String taskId;

    @TableField("error_message")
    private String errorMessage;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField("is_deleted")
    @TableLogic
    private Integer isDeleted;

    public enum Status {
        PROCESSING(0, "处理中"),
        COMPLETED(1, "已完成"),
        FAILED(2, "失败");


        private final int code;
        private final String desc;

        Status(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }
        public int getCode() {
            return code;
        }
    }
}

