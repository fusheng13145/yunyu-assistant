package com.leyon.backend.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量统计 SQL 静态判据（S-12 收口的第三道锁）
 *
 * <p>判据只读 {@link Select} 注解值（不读源码文件），因此 mapper 方法改名、挪文件都不会让判据失效或空转。
 * 三条正向锚点对应三条口径不变式，两条反向锚点防的是"改回旧实现也能过单测"：
 * 单测桩住 Mapper，所以**消息数是不是取自反列、有没有漏读归档表、是否被 SQL 二次判定 status**
 * 这类事实只能由本判据守住。
 *
 * @author leyon
 */
class UsageStatsSqlGuardTest {

    private static final String LIVE_CALLS = "selectLiveUsageCalls";
    private static final String ARCHIVED_CALLS = "selectArchivedUsageCalls";
    private static final String LIVE_MESSAGES = "countLiveMessagesByCall";
    private static final String ARCHIVED_MESSAGES = "countArchivedMessagesByCall";

    private static String sql(String methodName) {
        Method target = null;
        for (Method method : UsageStatsMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                target = method;
            }
        }
        assertThat(target).as("UsageStatsMapper 必须有方法 %s（判据不能对着不存在的方法空转）", methodName).isNotNull();
        Select select = target.getAnnotation(Select.class);
        assertThat(select).as("方法 %s 必须用 @Select 手写 SQL", methodName).isNotNull();
        // <script> 内的 XML 实体还原成比较符，判据按语义而非字面实体匹配
        return String.join(" ", select.value())
                .replace("&gt;", ">")
                .replace("&lt;", "<");
    }

    private static List<String> allSql() {
        List<String> values = new ArrayList<>();
        for (Method method : UsageStatsMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            assertThat(select).as("方法 %s 必须带 @Select", method.getName()).isNotNull();
            values.add(sql(method.getName()));
        }
        assertThat(values).as("判据覆盖面必须等于 mapper 方法数").hasSize(4);
        return values;
    }

    @Test
    void callQueriesCoverBothLiveAndArchiveSides() {
        assertThat(sql(LIVE_CALLS)).contains("FROM call_records ");
        assertThat(sql(ARCHIVED_CALLS)).contains("FROM call_records_archive");
        // 归档表是另一张表，漏读它 = 超期历史用量对外消失（S-12 偏差③的原始形态）
        assertThat(sql(LIVE_CALLS)).doesNotContain("call_records_archive");
        assertThat(sql(ARCHIVED_CALLS)).contains("call_records_archive");
    }

    @Test
    void callQueriesFilterDeletedRowsAndBoundTheWindow() {
        for (String methodName : List.of(LIVE_CALLS, ARCHIVED_CALLS)) {
            String value = sql(methodName);
            assertThat(value).as(methodName).contains("is_deleted = 0");
            assertThat(value).as(methodName).contains("started_at >= #{since}");
            // 归属集合按参数绑定，不做字符串拼接（组织维度会传入他人用户ID）
            assertThat(value).as(methodName).contains("<foreach collection='userIds'");
            assertThat(value).as(methodName).doesNotContain("${");
            assertThat(value).as(methodName).contains("user_id IN");
        }
    }

    @Test
    void messageQueriesCountConversationTurnsFromBothSides() {
        assertThat(sql(LIVE_MESSAGES)).contains("FROM records ").contains("role IN (0, 1)");
        assertThat(sql(ARCHIVED_MESSAGES)).contains("FROM records_archive").contains("role IN (0, 1)");
        for (String methodName : List.of(LIVE_MESSAGES, ARCHIVED_MESSAGES)) {
            String value = sql(methodName);
            assertThat(value).as(methodName).contains("is_deleted = 0");
            assertThat(value).as(methodName).contains("GROUP BY call_id");
            assertThat(value).as(methodName).contains("<foreach collection='callIds'");
            assertThat(value).as(methodName).doesNotContain("${");
        }
    }

    @Test
    void noQueryReadsTheDenormalizedMessageCountColumn() {
        // call_records.message_count 是"说谎列"：口径一旦改成取自 records，任何读取路径都不该留下
        for (String value : allSql()) {
            assertThat(value).doesNotContain("message_count");
        }
    }

    @Test
    void noQueryDecidesSettlementStatusItself() {
        // 结算判定只在 UsageStatsService.classify 一处；SQL 里再判一次就会两处漂移
        for (String methodName : List.of(LIVE_CALLS, ARCHIVED_CALLS)) {
            String value = sql(methodName);
            assertThat(value).as(methodName).contains("status");
            assertThat(value).as(methodName).doesNotContain("status =").doesNotContain("status IN")
                    .doesNotContain("status >").doesNotContain("status <");
        }
        for (String methodName : List.of(LIVE_MESSAGES, ARCHIVED_MESSAGES)) {
            // 消息查询不 join 通话表：归属与窗口已由通话查询定死，join 会让"通话已归档、消息仍活"少计
            assertThat(sql(methodName)).doesNotContain("call_records").doesNotContain("JOIN");
        }
    }

    @Test
    void callQueriesSelectOnlyColumnsTheAggregationNeeds() {
        String value = sql(LIVE_CALLS);
        assertThat(value).contains("duration_sec").contains("started_at").contains("status");
        // 录音名与失败原因不参与用量口径；message_count 由反向锚点单独守（不读说谎列）
        assertThat(value).doesNotContain("recording_name").doesNotContain("fail_reason");
    }
}
