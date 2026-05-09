package com.example.esrag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.esrag.entity.Document;
import com.example.esrag.entity.DocumentChunkMeta;
import com.example.esrag.mapper.DocumentMapper;
import com.example.esrag.mapper.DocumentChunkMetaMapper;
import com.example.esrag.dto.elasticsearch.DocumentChunk;
import com.example.esrag.ChunkRepository;
import com.example.esrag.utils.FileParser;
import com.example.esrag.utils.TextSplitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMetaMapper chunkMetaMapper;
    private final ChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final FileParser fileParser;
    private final StringRedisTemplate redisTemplate;

    private static final String TASK_PROGRESS_KEY = "doc:task:";
    private static final int BATCH_SIZE = 100; // 批量处理大小

    /**
     * 上传并处理文档（异步）
     * 
     * @param fileName   文件名
     * @param fileType   文件类型
     * @param fileBytes  文件字节数组
     * @param fileSize   文件大小
     * @param userId     用户ID
     * @return 任务ID
     */
    public String uploadAndProcessAsync(String fileName, String fileType, byte[] fileBytes, 
                                         long fileSize, Long userId) {
        
        // 1. 计算 MD5
        String md5 = calculateMd5(fileBytes);
        
        // 2. 检查是否已存在
        Document existingDoc = checkDuplicate(md5);
        if (existingDoc != null) {
            log.info("文件已存在 | MD5: {} | DocumentId: {} | Status: {}", 
                md5, existingDoc.getId(), existingDoc.getStatus());
            
            // 如果已完成，直接返回；如果处理中，也返回 taskId 让前端查询进度
            return existingDoc.getTaskId() != null ? existingDoc.getTaskId() : existingDoc.getId().toString();
        }
        
        // 3. 创建任务ID
        String taskId = UUID.randomUUID().toString();
        
        // 4. 创建文档记录（状态：处理中）
        Document document = new Document();
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFileSize(fileSize);
        document.setFileMd5(md5);
        document.setUploadUserId(userId);
        document.setUploadTime(LocalDateTime.now());
        document.setStatus(Document.Status.PROCESSING.getCode());
        document.setTaskId(taskId);

        documentMapper.insert(document);
        Long documentId = document.getId();
        
        // 5. 初始化 Redis 进度（临时数据，7天过期）
        updateTaskProgress(taskId, 0, "准备处理");
        
        // 6. 异步处理
        processDocumentAsync(documentId, taskId, fileBytes, fileName, fileType);
        
        log.info("文档处理任务已创建 | DocumentId: {} | TaskId: {}", documentId, taskId);
        return taskId;
    }

    /**
     * 异步处理文档
     */
    @Async
    public void processDocumentAsync(Long documentId, String taskId, byte[] fileBytes,
                                     String fileName, String fileType) {
        try {
            // ========== 阶段1：解析和预处理（无脏数据风险）==========
            updateTaskProgress(taskId, 10, "解析文件中...");
            FileParser.ParsedResult parsedResult = parseFile(fileBytes, fileName, fileType);

            if (parsedResult.getContent() == null || parsedResult.getContent().trim().isEmpty()) {
                throw new RuntimeException("文件内容为空");
            }

            updateTaskProgress(taskId, 30, "文本切片中...");
            List<String> chunks = TextSplitter.split(parsedResult.getContent());
            if (chunks.isEmpty()) {
                throw new RuntimeException("文本切片结果为空");
            }

            log.info("文档切片完成 | DocumentId: {} | 切片数: {}", documentId, chunks.size());
            updateTaskProgress(taskId, 40, "向量化处理中...");

            // ========== 阶段2：向量化（内存操作，无脏数据风险）==========
            int totalChunks = chunks.size();
            List<DocumentChunk> allEsChunks = new ArrayList<>();
            List<DocumentChunkMeta> allMetaChunks = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
                int endIndex = Math.min(i + BATCH_SIZE, chunks.size());
                List<String> batchChunks = chunks.subList(i, endIndex);

                List<float[]> vectors = embeddingService.embedBatch(batchChunks);

                for (int j = 0; j < batchChunks.size(); j++) {
                    int chunkIndex = i + j;
                    String chunkContent = batchChunks.get(j);
                    float[] vector = vectors.get(j);

                    DocumentChunk esChunk = new DocumentChunk();
                    esChunk.setId("doc_" + documentId + "_chunk_" + chunkIndex);
                    esChunk.setContent(chunkContent);
                    esChunk.setVector(vector);
                    esChunk.setDocumentId(documentId);
                    esChunk.setFileMd5(calculateMd5(fileBytes));
                    esChunk.setFileName(fileName);
                    esChunk.setFileType(fileType);
                    esChunk.setFileSize(getFileSizeFromBytes(fileBytes));
                    esChunk.setTotalPages(parsedResult.getTotalPages());
                    esChunk.setTotalChunks(totalChunks);
                    esChunk.setChunkIndex(chunkIndex);
                    esChunk.setUploadTime(LocalDateTime.now());

                    allEsChunks.add(esChunk);

                    DocumentChunkMeta meta = new DocumentChunkMeta();
                    meta.setDocumentId(documentId);
                    meta.setChunkIndex(chunkIndex);
                    meta.setEsChunkId(esChunk.getId());
                    meta.setContentPreview(chunkContent.substring(0, Math.min(200, chunkContent.length())));
                    meta.setCharCount(chunkContent.length());

                    allMetaChunks.add(meta);
                }

                int processedChunks = (i / BATCH_SIZE) * BATCH_SIZE + batchChunks.size();
                int progress = 40 + (int)((processedChunks * 30.0) / totalChunks);
                updateTaskProgress(taskId, progress,
                        String.format("向量化进度: %d/%d", processedChunks, totalChunks));
            }

            updateTaskProgress(taskId, 75, "写入存储中...");

            // ========== 阶段3：持久化（有脏数据风险，需要清理）==========
            saveChunksWithTransaction(documentId, taskId, allEsChunks, allMetaChunks);

            // ========== 阶段4：完成状态更新 ==========
            updateDocumentStatus(documentId, Document.Status.COMPLETED.getCode(),
                    totalChunks, null);
            updateTaskProgress(taskId, 100, "处理完成");
            cacheDocumentStatus(documentId, Document.Status.COMPLETED.getCode());

            log.info("文档处理完成 | DocumentId: {} | 切片数: {}", documentId, totalChunks);

        } catch (Exception e) {
            log.error("文档处理失败 | DocumentId: {} | TaskId: {} | Error: {}",
                    documentId, taskId, e.getMessage(), e);

            // 更新 MySQL 状态为失败
            updateDocumentStatus(documentId, Document.Status.FAILED.getCode(),
                    null, e.getMessage());
            updateTaskProgress(taskId, -1, "处理失败: " + e.getMessage());

            // ⚠️ 关键：判断是否需要清理脏数据
            // 只有在持久化阶段失败时才需要清理
            if (isPersistencePhaseError(e)) {
                cleanupFailedTaskAsync(documentId, taskId); // 异步清理
            }
        }
    }

    /**
     * 判断是否为持久化阶段的错误
     */
    private boolean isPersistencePhaseError(Exception e) {
        // 可以通过异常类型或消息判断
        return e.getMessage() != null &&
                (e.getMessage().contains("ES") ||
                        e.getMessage().contains("Elasticsearch") ||
                        e.getMessage().contains("document_chunks_meta"));
    }

    /**
     * 异步清理脏数据（避免阻塞主流程）
     */
    @Async
    public void cleanupFailedTaskAsync(Long documentId, String taskId) {
        try {
            log.warn("开始清理失败任务的脏数据 | DocumentId: {} | TaskId: {}", documentId, taskId);

            // 删除 ES 中已写入的分块
            chunkRepository.deleteByDocumentId(documentId);

            // 删除 MySQL Meta（理论上事务已回滚，但为了安全再次确认）
            chunkMetaMapper.deleteByDocumentId(documentId);

            log.info("失败任务脏数据清理完成 | DocumentId: {} | TaskId: {}", documentId, taskId);

        } catch (Exception e) {
            log.error("清理失败任务脏数据异常 | DocumentId: {} | Error: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * 事务性保存分块数据
     * 注意：@Transactional 只保证 MySQL 事务，ES 需要手动回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveChunksWithTransaction(Long documentId, String taskId,
                                          List<DocumentChunk> esChunks,
                                          List<DocumentChunkMeta> metaChunks) {
        List<String> savedEsIds = new ArrayList<>();

        try {
            // 1. 批量写入 ES（ES 不支持 Spring 事务，需要手动回滚）
            chunkRepository.saveAll(esChunks);

            // 记录已写入的 ES 文档 ID
            savedEsIds.addAll(esChunks.stream()
                    .map(DocumentChunk::getId)
                    .collect(Collectors.toList()));

            log.info("ES 写入成功 | DocumentId: {} | TaskId: {} | 数量: {}", documentId, taskId, esChunks.size());

            // 2. 批量写入 MySQL Meta（受 @Transactional 保护）
            for (DocumentChunkMeta meta : metaChunks) {
                chunkMetaMapper.insert(meta);
            }
            log.info("MySQL Meta 写入成功 | DocumentId: {} | TaskId: {} | 数量: {}", documentId, taskId, metaChunks.size());

        } catch (Exception e) {
            log.error("保存分块数据失败 | DocumentId: {} | TaskId: {} | Error: {}", documentId, taskId, e.getMessage(), e);

            // ⚠️ 手动回滚 ES 数据（因为 ES 不在 Spring 事务管理中）
            if (!savedEsIds.isEmpty()) {
                rollbackEsData(savedEsIds);
            }

            throw e; // 抛出异常触发 MySQL 事务回滚
        }
    }

    /**
     * 回滚 ES 中已写入的数据
     */
    private void rollbackEsData(List<String> esIds) {
        try {
            for (String id : esIds) {
                chunkRepository.deleteById(id);
            }
            log.warn("ES 数据回滚完成 | 删除 {} 条记录", esIds.size());
        } catch (Exception ex) {
            log.error("ES 数据回滚失败 | Error: {}", ex.getMessage(), ex);
            // 这里可以发送告警，需要人工介入
        }
    }

    /**
     * 查询任务进度
     */
    public Map<String, Object> getTaskProgress(String taskId) {
        String key = TASK_PROGRESS_KEY + taskId;

        String progressStr = redisTemplate.opsForValue().get(key + ":progress");
        String stepStr = redisTemplate.opsForValue().get(key + ":step");

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("progress", progressStr != null ? Integer.parseInt(progressStr) : 0);
        result.put("currentStep", stepStr != null ? stepStr : "未知");

        return result;
    }

    /**
     * 检查文件是否重复
     */
    private Document checkDuplicate(String md5) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Document::getFileMd5, md5);
        return documentMapper.selectOne(wrapper);
    }

    /**
     * 更新任务进度（仅更新 Redis，不更新 MySQL）
     */
    private void updateTaskProgress(String taskId, int progress, String step) {
        String key = TASK_PROGRESS_KEY + taskId;
        redisTemplate.opsForValue().set(key + ":progress", String.valueOf(progress), 7, TimeUnit.DAYS);
        redisTemplate.opsForValue().set(key + ":step", step, 7, TimeUnit.DAYS);
    }

    /**
     * 更新文档状态（仅更新 MySQL 的最终状态）
     */
    private void updateDocumentStatus(Long documentId, int status, Integer totalChunks, String errorMessage) {
        Document document = new Document();
        document.setId(documentId);
        document.setStatus(status);
        if (totalChunks != null) {
            document.setTotalChunks(totalChunks);
        }
        if (errorMessage != null) {
            document.setErrorMessage(errorMessage);
        }
        documentMapper.updateById(document);
    }

    /**
     * 缓存文档状态
     */
    private void cacheDocumentStatus(Long documentId, int status) {
        String key = "doc:status:" + documentId;
        redisTemplate.opsForValue().set(key, String.valueOf(status), 7, TimeUnit.DAYS);
    }

    /**
     * 计算 MD5
     */
    private String calculateMd5(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算MD5失败", e);
        }
    }

    /**
     * 获取文件大小
     */
    private long getFileSizeFromBytes(byte[] bytes) {
        return bytes.length;
    }

    /**
     * 解析文件（复用 FileParser）
     */
    private FileParser.ParsedResult parseFile(byte[] fileBytes, String fileName, String fileType) {
        try {
            // 直接复用 FileParser 的现有方法
            return fileParser.parseFile(fileBytes, fileName);

        } catch (Exception e) {
            log.error("文件解析失败 | FileName: {} | Error: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据文档ID查询文档
     */
    public Document getDocumentById(Long documentId) {
        return documentMapper.selectById(documentId);
    }

    /**
     * 合并分片并启动异步处理
     */
    public String mergeAndProcess(String md5, String fileName, int totalChunks, Long userId) throws IOException {
        String tempDir = System.getProperty("user.dir") + "/temp_uploads/" + md5;
        File mergedFile = new File(System.getProperty("user.dir") + "/temp_uploads/" + md5 + "_merged");

        // 1. 合并文件
        try (RandomAccessFile raf = new RandomAccessFile(mergedFile, "rw")) {
            for (int i = 0; i < totalChunks; i++) {
                File chunk = new File(tempDir, String.valueOf(i));
                if (!chunk.exists()) {
                    throw new RuntimeException("分片缺失: " + i);
                }
                byte[] bytes = Files.readAllBytes(chunk.toPath());
                raf.write(bytes);
            }
        }

        // 2. 读取合并后的文件字节
        byte[] fileBytes = Files.readAllBytes(mergedFile.toPath());

        // 3. 复用原有的上传逻辑（此时会触发 MD5 去重检查）
        String taskId = uploadAndProcessAsync(fileName, getFileExtension(fileName), fileBytes, fileBytes.length, userId);

        // 4. 清理临时文件
        deleteDirectory(new File(tempDir));
        mergedFile.delete();

        return taskId;
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) {
                deleteDirectory(f);
            }
        }
        dir.delete();
    }

    private String getFileExtension(String fileName) {
        return fileName.substring(fileName.lastIndexOf(".") + 1);
    }
}