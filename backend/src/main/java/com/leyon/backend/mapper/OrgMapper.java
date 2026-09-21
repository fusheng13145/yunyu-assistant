package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.Org;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组织 Mapper
 * 对应数据表 orgs
 *
 * @author leyon
 */
@Mapper
public interface OrgMapper extends BaseMapper<Org> {
}