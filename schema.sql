-- ============================================
-- RAG-KW 数据库初始化脚本
-- ============================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS rag_kw CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE rag_kw;

-- ============================================
-- 1. 文档表
-- ============================================
CREATE TABLE IF NOT EXISTS documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文档ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type VARCHAR(50) NOT NULL COMMENT '文件类型',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    file_md5 VARCHAR(64) NOT NULL COMMENT '文件MD5',
    upload_user_id BIGINT NOT NULL COMMENT '上传用户ID',
    upload_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0=处理中，1=已完成，2=失败',
    task_id VARCHAR(64) COMMENT '任务ID',
    total_chunks INT DEFAULT 0 COMMENT '总分块数',
    error_message TEXT COMMENT '错误信息',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除，1=已删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_file_md5 (file_md5),
    INDEX idx_upload_user_id (upload_user_id),
    INDEX idx_status (status),
    INDEX idx_upload_time (upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档元数据表';

-- ============================================
-- 2. 文档分块元数据表
-- ============================================
CREATE TABLE IF NOT EXISTS document_chunks_meta (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    chunk_index INT NOT NULL COMMENT '分块索引',
    es_chunk_id VARCHAR(128) NOT NULL COMMENT 'ES分块ID',
    content_preview TEXT COMMENT '内容预览',
    char_count INT COMMENT '字符数',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_document_id (document_id),
    INDEX idx_es_chunk_id (es_chunk_id),
    UNIQUE KEY uk_document_chunk (document_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分块元数据表';

-- ============================================
-- 3. 对话历史表
-- ============================================
CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '对话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    session_id VARCHAR(128) NOT NULL COMMENT '会话ID',
    question TEXT NOT NULL COMMENT '用户问题',
    answer TEXT NOT NULL COMMENT 'AI回答',
    referenced_docs TEXT COMMENT '参考文档（JSON）',
    retrieval_time_ms INT COMMENT '检索耗时（毫秒）',
    llm_time_ms INT COMMENT 'LLM耗时（毫秒）',
    feedback TINYINT COMMENT '反馈：1=点赞，-1=点踩，0=无反馈',
    feedback_comment TEXT COMMENT '反馈评论',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_user_id (user_id),
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at),
    INDEX idx_feedback (feedback)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话历史表';

-- ============================================
-- 4. 用户表（可选）
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    email VARCHAR(128) COMMENT '邮箱',
    password_hash VARCHAR(255) COMMENT '密码哈希',
    is_active TINYINT NOT NULL DEFAULT 1 COMMENT '是否激活',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_username (username),
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================
-- 插入测试数据（可选）
-- ============================================
INSERT INTO users (username, email) VALUES
('admin', 'admin@example.com'),
('test_user', 'test@example.com')
ON DUPLICATE KEY UPDATE username=username;