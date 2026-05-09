package com.example.esrag.service;

import com.example.esrag.entity.Document;
import com.example.esrag.mapper.DocumentMapper;
import com.example.esrag.mapper.DocumentChunkMetaMapper;
import com.example.esrag.ChunkRepository;
import com.example.esrag.utils.FileParser; 
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.junit.jupiter.SpringExtension; // 如果需要更高级的异步支持

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMetaMapper chunkMetaMapper;

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private FileParser fileParser; 

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void testUploadAndProcessAsync_DuplicateFile() {
        // 准备数据
        String md5 = "test_md5";
        Document existingDoc = new Document();
        existingDoc.setId(1L);
        existingDoc.setTaskId("old_task_id");
        
        // 模拟去重逻辑
        when(documentMapper.selectOne(any())).thenReturn(existingDoc);

        // 执行
        String taskId = documentService.uploadAndProcessAsync("test.pdf", "pdf", new byte[0], 0, 1L);

        // 验证：直接返回了旧的任务ID，没有执行后续插入逻辑
        assertEquals("old_task_id", taskId);
        verify(documentMapper, never()).insert(any(Document.class));

    }

    @Test
    void testUploadAndProcessAsync_NewFile() throws InterruptedException, IOException {
        // 1. 模拟去重返回 null
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        
        // 2. 【关键】模拟 FileParser 返回足够长的内容，确保能切出片
        // 假设 TextSplitter 最小切片长度是 50，我们给一段长一点的文本
        String longContent = "这是一个用于测试的模拟文档内容。" + 
                             "Spring Boot 是一个非常好用的框架。" + 
                             "Elasticsearch 提供了强大的向量检索功能。" +
                             "Redis 可以作为高速缓存使用。";
        FileParser.ParsedResult mockResult = new FileParser.ParsedResult(longContent, 1);
        when(fileParser.parseFile(any(byte[].class), anyString())).thenReturn(mockResult);

        // 3. 模拟 Redis set 操作
        lenient().doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any());

        // 4. 【新增】模拟 EmbeddingService，防止向量化时调用真实 API 报错
        // 假设切片后产生了 1 个 chunk，我们需要返回 1 个对应的向量数组
        when(embeddingService.embedBatch(anyList())).thenReturn(Collections.singletonList(new float[512]));

        // 执行
        String taskId = documentService.uploadAndProcessAsync("new.pdf", "pdf", new byte[10], 10, 1L);

        // 5. 等待异步线程执行
        Thread.sleep(1000); 

        // 验证
        assertNotNull(taskId);
        verify(documentMapper, times(1)).insert(any(Document.class));
        verify(fileParser, times(1)).parseFile(any(byte[].class), anyString());
        // 验证是否调用了向量化服务
        verify(embeddingService, times(1)).embedBatch(anyList());
    }

    @Test
    void testUploadAndProcessAsync_ParseFailure() throws InterruptedException {
        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        // 【修复】抛出 IOException 以匹配方法签名
        try {
            doThrow(new IOException("解析失败")).when(fileParser).parseFile(any(), any());
        } catch (IOException e) {
            // Mockito 的 doThrow 在处理受检异常时有时会触发编译器警告，用 try-catch 包裹是最稳妥的
            throw new RuntimeException(e);
        }

        String taskId = documentService.uploadAndProcessAsync("bad.pdf", "pdf", new byte[10], 10, 1L);
        Thread.sleep(500); // 等待异步执行

        verify(documentMapper, times(1)).insert(any(Document.class));
    }
}