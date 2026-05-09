package com.example.esrag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.esrag.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    @Select("SELECT * FROM documents WHERE file_md5 = #{fileMd5} AND is_deleted = 0")
    Document selectByFileMd5(@Param("fileMd5") String fileMd5);

    @Select("SELECT * FROM documents WHERE upload_user_id = #{uploadUserId} AND is_deleted = 0 ORDER BY upload_time DESC")
    List<Document> selectByUploadUserId(@Param("uploadUserId") Long uploadUserId);

    @Select("SELECT COUNT(*) FROM documents WHERE upload_user_id = #{uploadUserId} AND is_deleted = 0")
    long countByUploadUserId(@Param("uploadUserId") Long uploadUserId);
}
