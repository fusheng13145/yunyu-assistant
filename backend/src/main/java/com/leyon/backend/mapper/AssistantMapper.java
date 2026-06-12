package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.Assistant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 助手信息 Mapper
 * 数据操作接口，对应数据表 assistants
 *
 * @author leyon
 */
@Mapper
public interface AssistantMapper extends BaseMapper<Assistant> {

}