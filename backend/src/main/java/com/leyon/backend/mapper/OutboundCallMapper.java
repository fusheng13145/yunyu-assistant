package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.OutboundCall;
import org.apache.ibatis.annotations.Mapper;

/**
 * PSTN 外呼任务 Mapper
 * 对应数据表 outbound_calls
 *
 * @author leyon
 */
@Mapper
public interface OutboundCallMapper extends BaseMapper<OutboundCall> {
}