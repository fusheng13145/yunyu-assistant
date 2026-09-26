package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户信息 Mapper
 * 数据操作接口，对应数据表 users
 *
 * @author leyon
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 只取凭据版本列（v2.42）
     * <p>
     * 手写 SQL 而不用 LambdaQuery 有两处必要：① 每次请求都跑这语句，取整行等于把
     * 密码哈希也捞进内存；② 列缺失（迁移未跑到的旧行）与行不存在必须在调用方分得清，
     * 所以用 {@code IFNULL} 把前者折成 0、后者保持 NULL。
     *
     * @param userId 用户ID
     * @return 凭据版本；账号不存在或已逻辑删除返回 {@code null}
     */
    @Select("SELECT IFNULL(token_version, 0) FROM users WHERE id = #{userId} AND is_deleted = 0")
    Integer selectTokenVersion(@Param("userId") String userId);

    /**
     * 改密与凭据版本自增合并为一条语句（v2.42）
     * <p>
     * 分两步写的后果是"密码已换、旧令牌仍在"的中间态，且第二步失败即静默不失效；
     * 单条 UPDATE 由 InnoDB 行锁保证并发改密不会丢版本自增。
     *
     * @return 受影响行数（0 表示账号不存在或已删除）
     */
    @Update("UPDATE users SET password = #{password}, token_version = IFNULL(token_version, 0) + 1 "
            + "WHERE id = #{userId} AND is_deleted = 0")
    int updatePasswordAndBumpTokenVersion(@Param("userId") String userId, @Param("password") String password);
}
