package com.kean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kean.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
