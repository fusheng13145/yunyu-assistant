package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.CallRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通话记录 Mapper
 * 数据操作接口，对应数据表 call_records
 *
 * @author leyon
 */
@Mapper
public interface CallRecordMapper extends BaseMapper<CallRecord> {

}
