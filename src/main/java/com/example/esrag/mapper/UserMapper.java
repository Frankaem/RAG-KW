package com.example.esrag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.esrag.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM users WHERE username = #{username}")
    User selectByUsername(@Param("username") String username);

    @Select("SELECT * FROM users WHERE api_key = #{apiKey}")
    User selectByApiKey(@Param("apiKey") String apiKey);

    @Select("SELECT COUNT(*) FROM users WHERE username = #{username}")
    boolean existsByUsername(@Param("username") String username);
}
