package com.example.esrag.service;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;
import ai.z.openapi.service.embedding.EmbeddingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmbeddingService {

    private final ZhipuAiClient client;
    private final String model;
    private final int dimensions;

    private static final int BATCH_SIZE = 50; // zhipu embedding3限制 【输入字符串数组中，单条请求最多支持 3072 个 Tokens，且数组最大不得超过 64 条】

    public EmbeddingService(ZhipuAiClient client,
                            @Value("${llm.embedding-model}") String model,
                            @Value("${llm.embedding-dimensions:2048}") int dimensions) {
        this.client = client;
        this.model = model;
        this.dimensions = dimensions;
    }

    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[dimensions] : results.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("待向量化的文本列表不能为空");
        }


        List<float[]> allVectors = new ArrayList<>();

        log.info("开始向量化，总文本数: {}, 模型: {}, 维度: {}", texts.size(), model, dimensions);

        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);

            try {
                EmbeddingCreateParams request = EmbeddingCreateParams.builder()
                        .model(model)
                        .input(batch)
                        .dimensions(dimensions)
                        .build();

                EmbeddingResponse response = client.embeddings().createEmbeddings(request);

                if (response.isSuccess()) {
                    EmbeddingResult result = response.getData();
                    if (result != null && result.getData() != null) {
                        List<float[]> batchVectors = result.getData().stream()
                                .map(obj -> {
                                    // 假设 EmbeddingObject 有 getEmbedding() 方法
                                    List<Double> embedding = obj.getEmbedding();
                                    float[] vec = new float[embedding.size()];
                                    for (int j = 0; j < embedding.size(); j++) {
                                        vec[j] = embedding.get(j).floatValue();
                                    }
                                    return vec;
                                })
                                .collect(Collectors.toList());
                        allVectors.addAll(batchVectors);
                    }
                } else {
                    log.error("向量化失败: {}", response.getMsg());
                    throw new RuntimeException("向量化API调用失败: " + response.getMsg());
                }

            } catch (Exception e) {
                log.error("向量化请求异常: {}", e.getMessage(), e);
                throw new RuntimeException("向量化处理失败: " + e.getMessage(), e);
            }
        }

        log.info("向量化完成，共生成 {} 个向量", allVectors.size());
        return allVectors;
    }
}
