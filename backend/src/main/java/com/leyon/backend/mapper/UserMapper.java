package com.leyon.backend.mapper;

import com.leyon.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper {

    int insert(User user);

    User selectByName(String name);

    User selectById(String id);
}
