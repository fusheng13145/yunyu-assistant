package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户信息 Mapper
 * 数据操作接口，对应数据表 users
 *
 * @author leyon
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}