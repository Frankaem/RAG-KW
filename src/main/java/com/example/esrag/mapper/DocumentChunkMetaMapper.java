package com.example.esrag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.esrag.entity.DocumentChunkMeta;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentChunkMetaMapper extends BaseMapper<DocumentChunkMeta> {

    @Select("SELECT * FROM document_chunks_meta WHERE document_id = #{documentId} ORDER BY chunk_index ASC")
    List<DocumentChunkMeta> selectByDocumentId(@Param("documentId") Long documentId);

    @Select("SELECT * FROM document_chunks_meta WHERE es_chunk_id = #{esChunkId}")
    DocumentChunkMeta selectByEsChunkId(@Param("esChunkId") String esChunkId);

    @Delete("DELETE FROM document_chunks_meta WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Select("SELECT COUNT(*) FROM document_chunks_meta WHERE document_id = #{documentId}")
    long countByDocumentId(@Param("documentId") Long documentId);
}
