package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.InviteCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 注册邀请码 Mapper
 * 对应数据表 invite_code
 *
 * @author leyon
 */
@Mapper
public interface InviteCodeMapper extends BaseMapper<InviteCode> {

    /**
     * 原子领取邀请码：判"未使用"与写入使用者在同一条 UPDATE 内完成（InnoDB 行锁），
     * 返回 1 表示本次调用独占了这个码。并发下"先查未使用再更新"的写法会让同一码被多人注册，
     * 因此这里不提供任何两步式入口。
     */
    @Update("UPDATE invite_code SET used_by = #{usedBy}, used_at = NOW() "
            + "WHERE code = #{code} AND used_by IS NULL")
    int claimByCode(@Param("code") String code, @Param("usedBy") String usedBy);
}
