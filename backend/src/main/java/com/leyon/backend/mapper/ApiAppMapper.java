package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.ApiApp;
import org.apache.ibatis.annotations.Mapper;

/**
 * 第三方应用 Mapper
 * 对应数据表 api_apps
 *
 * @author leyon
 */
@Mapper
public interface ApiAppMapper extends BaseMapper<ApiApp> {
}