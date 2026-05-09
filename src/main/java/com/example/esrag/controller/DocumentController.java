package com.example.esrag.controller;

import com.example.esrag.service.DocumentService;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
@Slf4j
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
@CrossOrigin
public class DocumentController {

    /**
     * DocumentController (文档管理)
     * ├─ POST /api/document/upload       - 上传文件(异步)
     * ├─ GET  /api/document/task-progress/{taskId} - 查询任务进度
     * └─ GET  /api/document/status/{documentId}    - 查询文档状态
     */

    private final DocumentService documentService;

    /**
     * 分片上传接口
     * @param file 分片文件
     * @param md5 整个文件的 MD5
     * @param chunkIndex 当前分片索引（从0开始）
     * @param totalChunks 总分片数
     * @param fileName 文件名
     */
    @PostMapping("/upload/chunk")
    public Map<String, Object> uploadChunk(
            @RequestParam("file") MultipartFile file,
            @RequestParam("md5") String md5,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("fileName") String fileName) {

        Map<String, Object> result = new HashMap<>();
        try {
            // 1. 定义临时存储路径
            String tempDir = System.getProperty("user.dir") + "/temp_uploads/" + md5;
            File dir = new File(tempDir);
            if (!dir.exists()) dir.mkdirs();

            // 2. 保存分片
            File chunkFile = new File(dir, String.valueOf(chunkIndex));
            file.transferTo(chunkFile);

            log.info("分片上传成功 | MD5: {} | Index: {}/{}", md5, chunkIndex + 1, totalChunks);

            result.put("success", true);
            result.put("message", "分片上传成功");
        } catch (Exception e) {
            log.error("分片上传失败", e);
            result.put("success", false);
            result.put("message", "分片上传失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 合并分片并触发处理
     */
    @PostMapping("/upload/merge")
    public Map<String, Object> mergeChunks(
            @RequestParam("md5") String md5,
            @RequestParam("fileName") String fileName,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam(required = false, defaultValue = "1") Long userId) {

        Map<String, Object> result = new HashMap<>();
        try {
            // 1. 调用 Service 进行合并和处理
            String taskId = documentService.mergeAndProcess(md5, fileName, totalChunks, userId);

            result.put("success", true);
            result.put("taskId", taskId);
            result.put("message", "文件合并成功，开始处理");
        } catch (Exception e) {
            log.error("分片合并失败", e);
            result.put("success", false);
            result.put("message", "合并失败: " + e.getMessage());
        }
        return result;
    }


    /**
     * 上传并处理文档（异步）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadDocuments(
            @RequestParam("files") @NotEmpty(message = "至少需要上传一个文件") MultipartFile[] files,
            @RequestParam(required = false, defaultValue = "1") Long userId) {

        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> taskInfos = new ArrayList<>();
        List<Map<String, String>> failedFiles = new ArrayList<>();

        if (files == null || files.length == 0) {
            result.put("message", "没有上传文件");
            result.put("tasks", taskInfos);
            result.put("failedFiles", failedFiles);
            return result;
        }

        int validFileCount = 0;
        for (MultipartFile file : files) {
            if (!file.isEmpty()) {
                validFileCount++;
            }
        }

        if (validFileCount == 0) {
            result.put("message", "没有上传有效的文件");
            result.put("tasks", taskInfos);
            result.put("failedFiles", failedFiles);
            return result;
        }

        log.info("收到 {} 个文件上传请求，有效文件数: {} | UserId: {}", files.length, validFileCount, userId);

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            Map<String, String> fileInfo = new HashMap<>();
            String originalFilename = file.getOriginalFilename();
            fileInfo.put("fileName", originalFilename);

            try {
                byte[] fileBytes = file.getBytes();
                String fileType = getFileType(originalFilename);

                // 异步处理，立即返回 taskId
                String taskId = documentService.uploadAndProcessAsync(
                        originalFilename, fileType, fileBytes, file.getSize(), userId
                );

                fileInfo.put("taskId", taskId);
                fileInfo.put("status", "processing");
                fileInfo.put("message", "文件处理任务已创建，请使用 taskId 查询进度");
                taskInfos.add(fileInfo);

                log.info("文件上传任务已创建 | FileName: {} | TaskId: {} | UserId: {}",
                        originalFilename, taskId, userId);

            } catch (Exception e) {
                fileInfo.put("error", e.getMessage());
                failedFiles.add(fileInfo);
                log.error("文件上传失败 | FileName: {} | Error: {}", originalFilename, e.getMessage(), e);
            }
        }

        result.put("message", "文件处理任务已创建，请使用 taskId 查询进度");
        result.put("tasks", taskInfos);
        result.put("failedFiles", failedFiles);
        result.put("successCount", taskInfos.size());
        result.put("failedCount", failedFiles.size());
        result.put("totalCount", validFileCount);

        return result;
    }

    /**
     * 查询任务进度
     */
    @GetMapping("/{taskId}/progress")
    public Map<String, Object> getTaskProgress(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> progress = documentService.getTaskProgress(taskId);
            result.put("success", true);
            result.put("data", progress);
        } catch (Exception e) {
            log.error("查询任务进度失败 | TaskId: {}", taskId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 查询文档状态
     */
    @GetMapping("/status/{documentId}")
    public Map<String, Object> getDocumentStatus(@PathVariable Long documentId) {
        Map<String, Object> result = new HashMap<>();
        try {
            var document = documentService.getDocumentById(documentId);
            if (document != null) {
                result.put("success", true);
                result.put("data", document);
            } else {
                result.put("success", false);
                result.put("error", "文档不存在");
            }
        } catch (Exception e) {
            log.error("查询文档状态失败 | DocumentId: {}", documentId, e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String getFileType(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        String lowerCaseFileName = fileName.toLowerCase();
        if (lowerCaseFileName.endsWith(".pdf")) {
            return "pdf";
        } else if (lowerCaseFileName.endsWith(".docx")) {
            return "docx";
        } else if (lowerCaseFileName.endsWith(".doc")) {
            return "doc";
        } else if (lowerCaseFileName.endsWith(".txt")) {
            return "txt";
        } else {
            return "other";
        }
    }
}
