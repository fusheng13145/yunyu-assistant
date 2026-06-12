package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.Record;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天记录 Mapper
 * 数据操作接口，对应数据表 records
 *
 * @author leyon
 */
@Mapper
public interface RecordMapper extends BaseMapper<Record> {

}