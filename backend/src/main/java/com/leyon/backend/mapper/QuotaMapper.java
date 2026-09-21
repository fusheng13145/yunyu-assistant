package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.Quota;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用量配额 Mapper
 * 对应数据表 quotas
 *
 * @author leyon
 */
@Mapper
public interface QuotaMapper extends BaseMapper<Quota> {
}