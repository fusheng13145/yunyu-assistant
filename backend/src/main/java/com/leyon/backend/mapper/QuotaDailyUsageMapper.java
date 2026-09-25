package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.QuotaDailyUsage;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 单日配额用量 Mapper
 * 对应数据表 quota_daily_usage
 *
 * @author leyon
 */
@Mapper
public interface QuotaDailyUsageMapper extends BaseMapper<QuotaDailyUsage> {

    /**
     * 带余额条件的原子扣减：检查与自增在同一条 UPDATE 里完成（InnoDB 行锁），
     * 返回 1 表示额度已在数据库层面被本次调用占用——这是并发下不超发的唯一依据，
     * 任何"先查再改"的写法都不行。返回 0 = 已达上限<b>或当日行尚不存在</b>（两者不可区分），
     * 故调用方须再判存在性（见 QuotaService#consumeDaily）。
     * <p>
     * {@code limit} 用 #{} 参数绑定而非 ${}：它是 quotas 表里的不可信配置值，且非数值列名。
     */
    @Update("UPDATE quota_daily_usage SET used = used + 1 "
            + "WHERE scope_type = #{scopeType} AND scope_id = #{scopeId} "
            + "AND usage_date = #{usageDate} AND metric = #{metric} AND used < #{limit}")
    int updateDailyUsage(@Param("scopeType") String scopeType,
                         @Param("scopeId") String scopeId,
                         @Param("metric") String metric,
                         @Param("usageDate") LocalDate usageDate,
                         @Param("limit") int limit);
}
