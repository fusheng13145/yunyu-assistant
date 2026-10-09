package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户信息 Mapper
 * 数据操作接口，对应数据表 users
 *
 * @author leyon
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按用户名查活跃账号（v2.89）
     * <p>
     * 三条标识查询都返回 {@code List} 而不是 {@code selectOne}：库里同值出现两行（迁移 0013 未跑到的
     * 实例上完全可能）时，{@code selectOne} 会抛 TooManyResults 或由 {@code LIMIT 1} 随机挑一行，
     * 后者等于"两行都算登录成功"。挑不挑由调用方显式判定，不在 SQL 里默认。
     * <p>
     * 只按普通列查、不按 0013 的生成列查：代价是缺迁移时失去硬唯一保证，收益是缺迁移时不失去服务。
     * {@code is_deleted = 0} 必须写在这里——手写 SQL 不走 MyBatis-Plus 的逻辑删除自动拼接。
     *
     * @param username 用户名（不归一，与存量行的存法一致）
     * @return 命中行，无命中为空列表
     */
    @Select("SELECT * FROM users WHERE username = #{username} AND is_deleted = 0")
    List<User> selectActiveByUsername(@Param("username") String username);

    /**
     * 按邮箱查活跃账号（v2.89），入参必须是 {@code IdentifierPolicy.normalizeEmail} 的产物
     */
    @Select("SELECT * FROM users WHERE email = #{email} AND is_deleted = 0")
    List<User> selectActiveByEmail(@Param("email") String email);

    /**
     * 按手机号查活跃账号（v2.89），入参必须是 {@code IdentifierPolicy.normalizePhone} 的产物
     */
    @Select("SELECT * FROM users WHERE phone = #{phone} AND is_deleted = 0")
    List<User> selectActiveByPhone(@Param("phone") String phone);

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
