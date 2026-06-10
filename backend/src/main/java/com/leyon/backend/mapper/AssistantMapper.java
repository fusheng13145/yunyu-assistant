package com.leyon.backend.mapper;

import com.leyon.backend.entity.Assistant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AssistantMapper {

    int insert(Assistant assistant);

    List<Assistant> selectAll();

    List<Assistant> selectByUserId(String userId);

    Assistant selectById(String id);

    int deleteById(String id);

    int update(Assistant assistant);
}
