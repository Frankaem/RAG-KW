package com.example.esrag;

import com.example.esrag.dto.elasticsearch.DocumentChunk;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ChunkRepository extends ElasticsearchRepository<DocumentChunk, String> {
    List<DocumentChunk> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
