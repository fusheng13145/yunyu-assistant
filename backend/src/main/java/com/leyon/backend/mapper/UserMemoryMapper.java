package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户长期记忆 Mapper
 * 数据操作接口，对应数据表 user_memories
 *
 * @author leyon
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {

}
