package com.example.esrag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.esrag.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    @Select("SELECT * FROM conversations WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<Conversation> selectRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("SELECT * FROM conversations WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<Conversation> selectBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT COUNT(*) FROM conversations WHERE user_id = #{userId} AND created_at BETWEEN #{start} AND #{end}")
    long countByUserIdAndTimeRange(@Param("userId") Long userId,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);
}
