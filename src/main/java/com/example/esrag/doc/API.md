
## 📡 API 接口文档

### 基础信息

- **Base URL**: `http://localhost:8080/api`
- **Content-Type**: `application/json`（除非特别说明）
- **认证方式**: 当前版本无需认证（生产环境建议添加 JWT）

### 1. 文档管理

#### 1.1 上传文档

**接口：** `POST /api/document/upload`

**请求类型：** `multipart/form-data`

**参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| file | File | ✅ | 文档文件（支持 PDF/DOC/DOCX/TXT） |
| userId | Long | ❌ | 用户ID，默认为 1 |

**示例：**
```bash
curl -X POST http://localhost:8080/api/document/upload -F "file=@/path/to/document.pdf" -F "userId=1"
```
**响应：**
```json
{ 
  "success": true, 
  "taskId": "550e8400-e29b-41d4-a716-446655440000", 
  "message": "文档处理任务已创建", 
  "documentId": 123
}
```
#### 1.2 查询任务进度

**接口：** `GET /api/document/{taskId}/progress`

**路径参数：**
- `taskId` - 任务ID（上传文档时返回）

**示例：**
```bash
curl http://localhost:8080/api/document/550e8400-e29b-41d4-a716-446655440000/progress
```
**响应：**
```json
{ 
  "success": true, 
  "data": { 
    "taskId": "550e8400-e29b-41d4-a716-446655440000", 
    "progress": 60, 
    "currentStep": "向量化进度: 30/50"
  }
}
```
**进度说明：**
- `0-10%`: 解析文件中
- `10-30%`: 文本切片中
- `30-70%`: 向量化处理中
- `70-90%`: 写入存储中
- `100%`: 处理完成
- `-1`: 处理失败

---

### 2. RAG 问答

#### 2.1 流式问答（推荐）

**接口：** `GET /api/rag/ask-stream`

**响应类型：** `text/event-stream`

**查询参数：**

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| question | String | ✅ | - | 用户问题 |
| sessionId | String | ❌ | default-session | 会话ID |
| userId | Long | ❌ | 1 | 用户ID |

**示例：**
```bash
curl -N "http://localhost:8080/api/rag/ask-stream?question=什么是RAG&sessionId=session-1&userId=1"
```
**响应（SSE 事件流）：**
```
event: message data: RAG是检索增强生成（Retrieval-Augmented Generation）的缩写...
event: message data:它结合了向量检索和大型语言模型（LLM）的能力...
event: references data: [{"fileName":"技术文档.pdf","chunkIndex":5,"score":0.92}]
event: complete data: {"retrievalTime":120,"llmTime":850,"totalTime":970}
```

#### 2.2 普通问答（非流式）

**接口：** `POST /api/rag/ask`

**请求体：**
```json
{ 
  "question": "什么是RAG?", 
  "sessionId": "session-1", 
  "userId": 1
}
```
**响应：**
```json
{ 
  "success": true, 
  "data": { 
    "answer": "RAG是检索增强生成的缩写...", 
    "references": [ 
      { 
        "fileName": "技术文档.pdf", 
        "chunkIndex": 5, 
        "contentPreview": "RAG结合了...", 
        "score": 0.92
      } 
    ], 
    "retrievalTime": 120, 
    "llmTime": 850, 
    "totalTime": 970
  }
}
```
---

### 3. 会话管理

#### 3.1 查询会话历史

**接口：** `GET /api/memory/history/{sessionId}?userId=1`

**路径参数：**
- `sessionId` - 会话ID

**查询参数：**
- `userId` - 用户ID（用于权限校验）

**示例：**
```bash
curl "http://localhost:8080/api/memory/history/session-1?userId=1"
```
**响应：**
```json
{ 
  "success": true, 
  "data": [ 
    { 
      "id": 1, 
      "sessionId": "session-1", 
      "userId": 1, 
      "question": "什么是RAG?", 
      "answer": "RAG是检索增强生成...", 
      "referencedDocs": [{"fileName":"技术文档.pdf"}],
      "retrievalTimeMs": 120, 
      "llmTimeMs": 850, 
      "feedback": null, 
      "createdAt": "2024-01-01T12:00:00"
    }, 
    { 
      "id": 2, 
      "sessionId": "session-1", 
      "userId": 1, 
      "question": "RAG有哪些优势?", 
      "answer": "RAG的主要优势包括...", 
      "feedback": 1, 
      "createdAt": "2024-01-01T12:05:00"
    } 
  ], 
  "count": 2
}
```
> 🔒 **安全提示**：此接口会校验 `userId`，只能查询属于自己的对话记录

#### 3.2 更新对话反馈

**接口：** `POST /api/memory/feedback`

**请求参数：**

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| conversationId | Long | ✅ | 对话ID |
| feedback | Integer | ✅ | 反馈：1=点赞, -1=点踩, 0=无反馈 |
| comment | String | ❌ | 反馈评论 |
| userId | Long | ❌ | 用户ID（用于权限校验） |

**示例：**
```bash
curl -X POST "http://localhost:8080/api/memory/feedback" -d "conversationId=1" -d "feedback=1" -d "comment=回答很准确" -d "userId=1"
```
**响应：**
```json
{ 
  "success": true, 
  "message": "反馈提交成功"
}
```
#### 3.3 清除会话记忆

**接口：** `DELETE /api/memory/clear/{sessionId}?userId=1`

**说明：** 清除 Redis 中的短期记忆（对话历史仍保留在 MySQL 中）

**示例：**
```bash
curl -X DELETE "http://localhost:8080/api/memory/clear/session-1?userId=1"
```
**响应：**
```json
{ 
  "success": true, 
  "message": "会话记忆已清除"
}
```
---


