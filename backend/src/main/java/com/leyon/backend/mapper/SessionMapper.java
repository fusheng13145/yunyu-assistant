package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.Session;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话数据访问层
 *
 * @author leyon
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {
}