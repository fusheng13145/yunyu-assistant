package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.OrgMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组织成员 Mapper
 * 对应数据表 org_members
 *
 * @author leyon
 */
@Mapper
public interface OrgMemberMapper extends BaseMapper<OrgMember> {
}