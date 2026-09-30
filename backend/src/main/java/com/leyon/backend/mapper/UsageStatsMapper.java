package com.leyon.backend.mapper;

import com.leyon.backend.entity.CallRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用量统计取数 Mapper（S-12 口径收口）
 * 必须用手写原生 SQL：活表与 {@code *_archive} 归档表要一起读，而逻辑删除条件由实体
 * {@code @TableLogic} 只追加在自动 SQL 上。
 *
 * <p>本 Mapper 只回答"取哪些行"：**通话是否已结算的判定只存在于 {@link UsageStatsService} 一处**，
 * SQL 不按 status 过滤，避免同一口径在 Java 与 SQL 两处漂移。
 * 由 {@code UsageStatsSqlGuardTest} 按注解值静态守这三条（双表覆盖 / 不读反列 / 不判 status）。
 *
 * @author leyon
 */
@Mapper
public interface UsageStatsMapper {

    /**
     * 活表中窗口内的通话行（按归属用户集合 + started_at 下界）
     */
    @Select("<script>"
            + "SELECT id, user_id, assistant_id, status, duration_sec, started_at, ended_at "
            + "FROM call_records "
            + "WHERE is_deleted = 0 AND started_at &gt;= #{since} AND user_id IN "
            + "<foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach>"
            + "</script>")
    List<CallRecord> selectLiveUsageCalls(@Param("userIds") List<String> userIds,
                                          @Param("since") LocalDateTime since);

    /**
     * 归档表中窗口内的通话行（列与活表查询一致，归档行不带录音与失败原因，口径不需要）
     */
    @Select("<script>"
            + "SELECT id, user_id, assistant_id, status, duration_sec, started_at, ended_at "
            + "FROM call_records_archive "
            + "WHERE is_deleted = 0 AND started_at &gt;= #{since} AND user_id IN "
            + "<foreach collection='userIds' item='uid' open='(' separator=',' close=')'>#{uid}</foreach>"
            + "</script>")
    List<CallRecord> selectArchivedUsageCalls(@Param("userIds") List<String> userIds,
                                              @Param("since") LocalDateTime since);

    /**
     * 活表消息数：按通话分组，只计 user(0)/assistant(1) 两轮，工具调用与结果不算一轮对话
     *
     * <p>这里**不 join 通话表**：归属与窗口已经由上面两条通话查询定死，传进来的是已结算的通话ID。
     * 一旦在此 join 通话表，"通话已归档、消息仍在活表"的组合就会两侧都读不到而静默少计
     * （两表保留期独立，该组合是运营可配置出来的）。
     */
    @Select("<script>"
            + "SELECT call_id AS callId, COUNT(id) AS cnt "
            + "FROM records "
            + "WHERE is_deleted = 0 AND role IN (0, 1) AND call_id IN "
            + "<foreach collection='callIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach> "
            + "GROUP BY call_id"
            + "</script>")
    List<Map<String, Object>> countLiveMessagesByCall(@Param("callIds") List<String> callIds);

    /**
     * 归档表消息数（与活表查询同构，只差表名）
     */
    @Select("<script>"
            + "SELECT call_id AS callId, COUNT(id) AS cnt "
            + "FROM records_archive "
            + "WHERE is_deleted = 0 AND role IN (0, 1) AND call_id IN "
            + "<foreach collection='callIds' item='cid' open='(' separator=',' close=')'>#{cid}</foreach> "
            + "GROUP BY call_id"
            + "</script>")
    List<Map<String, Object>> countArchivedMessagesByCall(@Param("callIds") List<String> callIds);
}
