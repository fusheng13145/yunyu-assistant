package com.leyon.backend.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器
 * 自动填充实体类的创建时间、更新时间字段
 *
 * @author leyon
 */
@Component
public class MyBatisPlusAutoFillHandler implements MetaObjectHandler {

    /**
     * 数据库字段名常量
     */
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    /**
     * 插入数据时自动填充：创建时间、更新时间
     *
     * @param metaObject 元数据对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, FIELD_CREATED_AT, LocalDateTime.class, now);
        strictInsertFill(metaObject, FIELD_UPDATED_AT, LocalDateTime.class, now);
    }

    /**
     * 更新数据时自动填充：更新时间
     *
     * @param metaObject 元数据对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, FIELD_UPDATED_AT, LocalDateTime.class, LocalDateTime.now());
    }
}