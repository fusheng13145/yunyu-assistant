package com.leyon.backend.service;

import com.leyon.backend.entity.ApiApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 能力（scopes）变更单元测试（v2.46 · 候选 ㉗ 的收口）。
 * <p>
 * 这批断言要守住的不是"能不能改"，而是**改的形状**：
 * 创建与变更对"空输入"的语义刻意相反（创建→默认 chat；变更→拒绝），
 * 一旦有人"顺手统一"成同一个归一函数，第三方应用的调用方就会在毫不知情的情况下被**追加**能力。
 * 因此 5. 空值两义 是本文件的核心断言，其余是它的支撑。
 * <p>
 * 替身用 {@link FakeApiAppMapper}（内存表）而不是 Mockito 打桩：需要断言的是"库里到底写了什么"，
 * 打桩只能证明"我调过某个方法"。真库那一轮见 scripts/smoke.sh §7.10。
 *
 * @author leyon
 */
class ApiAppServiceScopesTest {

    private static final String OWNER = "user-owner";
    private static final String OTHER = "user-other";

    private final FakeApiAppMapper mapper = new FakeApiAppMapper();
    private final ApiAppService service = new ApiAppService(mapper);

    @Nested
    @DisplayName("变更即时生效（判定每请求查库，无缓存）")
    class TakesEffectImmediately {

        @Test
        @DisplayName("收紧：chat,call → chat 之后 hasScope(call) 即为 false")
        void narrowingTakesEffectAtOnce() {
            String appId = mapper.seed(OWNER, "chat,call");

            ApiAppService.ScopeChange change = service.updateScopes(appId, OWNER, "chat");

            assertThat(change.scopes()).isEqualTo("chat");
            assertThat(mapper.appInDb(appId).hasScope(ApiApp.SCOPE_CALL)).isFalse();
            assertThat(mapper.appInDb(appId).hasScope(ApiApp.SCOPE_CHAT)).isTrue();
        }

        @Test
        @DisplayName("放宽：chat → chat,voice 之后 hasScope(voice) 即为 true")
        void wideningTakesEffectAtOnce() {
            String appId = mapper.seed(OWNER, "chat");

            service.updateScopes(appId, OWNER, "chat,voice");

            assertThat(mapper.appInDb(appId).hasScope(ApiApp.SCOPE_VOICE)).isTrue();
        }

        @Test
        @DisplayName("返回值报告改前的值：审计的 old→new 只有一个数据来源")
        void returnsPreviousScopesForAudit() {
            String appId = mapper.seed(OWNER, "chat");

            ApiAppService.ScopeChange change = service.updateScopes(appId, OWNER, "chat,call");

            assertThat(change.previousScopes()).isEqualTo("chat");
            assertThat(change.scopes()).isEqualTo("chat,call");
            assertThat(change.appId()).isEqualTo(appId);
        }
    }

    @Nested
    @DisplayName("存储形态归一：与创建侧同一套解析")
    class Normalization {

        @Test
        @DisplayName("乱序输入按白名单顺序落库，同一组能力的存储形态唯一")
        void ordersByWhitelist() {
            String appId = mapper.seed(OWNER, "chat");

            service.updateScopes(appId, OWNER, "voice,chat");

            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat,voice");
        }

        @Test
        @DisplayName("重复与空白项去重：\"chat, chat ,chat\" 不会写出三项")
        void deduplicates() {
            String appId = mapper.seed(OWNER, "chat");

            service.updateScopes(appId, OWNER, "chat, chat ,chat");

            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat");
        }

        @Test
        @DisplayName("大写归一为小写，且整串替换：旧能力不保留（接口没有「只加不减」的语义）")
        void lowercasesInputAndReplacesWholesale() {
            String appId = mapper.seed(OWNER, "chat");

            service.updateScopes(appId, OWNER, "CALL");

            // 期望是 "call" 而不是 "chat,call"：改成增量授予就等于再也无法通过本接口停掉任何能力，
            // 而"停掉外呼"正是这套能力白名单存在的理由。
            assertThat(mapper.scopesInDb(appId)).isEqualTo("call");
        }
    }

    @Nested
    @DisplayName("拒绝形状：不认识的一律不写")
    class Rejections {

        @Test
        @DisplayName("未知能力整体拒绝，且库里保持原值（不是部分写入、也不是静默丢弃）")
        void unknownScopeRejectedAtomically() {
            String appId = mapper.seed(OWNER, "chat,call");

            assertThatThrownBy(() -> service.updateScopes(appId, OWNER, "chat,bogus"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bogus");

            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat,call");
        }

        @Test
        @DisplayName("空值两义分岔：变更拒绝空集，创建却默认 chat（同一函数会静默授予能力）")
        void emptyMeansRejectOnUpdateButDefaultOnCreate() {
            String appId = mapper.seed(OWNER, "chat,call,voice");

            assertThatThrownBy(() -> service.updateScopes(appId, OWNER, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("吊销");

            // 库里三项能力原封不动——"取消全部勾选"绝不能变成"只保留 chat"
            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat,call,voice");

            // 同一次测试里并排断言创建侧：不传能力时得到 chat（这条是既有契约，不许跟着变更侧一起改）
            ApiApp created = service.create(OWNER, "新应用", null, "");
            assertThat(created.getScopes()).isEqualTo(ApiApp.SCOPE_CHAT);
        }

        @Test
        @DisplayName("全为逗号的输入等同空集，同样拒绝")
        void commasOnlyRejected() {
            String appId = mapper.seed(OWNER, "chat");

            assertThatThrownBy(() -> service.updateScopes(appId, OWNER, ", ,"))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat");
        }
    }

    @Nested
    @DisplayName("归属与可见性：改不到别人的、已吊销的、不存在的应用")
    class Ownership {

        @Test
        @DisplayName("非属主变更返回 null 且库里不变")
        void nonOwnerCannotEdit() {
            String appId = mapper.seed(OWNER, "chat,call,voice");

            assertThat(service.updateScopes(appId, OTHER, "chat")).isNull();
            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat,call,voice");
        }

        @Test
        @DisplayName("不存在的应用返回 null，不因缺行抛异常")
        void missingAppReturnsNull() {
            assertThat(service.updateScopes("app-not-there", OWNER, "chat")).isNull();
        }

        @Test
        @DisplayName("id 为空直接拒绝：空串不该被当成「所有应用」或某条真实记录")
        void blankIdRejected() {
            String appId = mapper.seed(OWNER, "chat");

            assertThat(service.updateScopes("", OWNER, "chat")).isNull();
            assertThat(service.updateScopes(null, OWNER, "chat")).isNull();
            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat");
        }

        @Test
        @DisplayName("已吊销（逻辑删除）的应用改不到：吊销是终态，不能靠改能力复活")
        void revokedAppCannotBeEdited() {
            String appId = mapper.seed(OWNER, "chat");
            assertThat(service.revoke(appId, OWNER)).isTrue();

            assertThat(service.updateScopes(appId, OWNER, "chat,call,voice")).isNull();
            assertThat(mapper.isDeletedInDb(appId)).isTrue();
            assertThat(mapper.scopesInDb(appId)).isEqualTo("chat");
        }
    }

    @Nested
    @DisplayName("不越界：改能力只写 scopes 一列")
    class DoesNotTouchCredentials {

        @Test
        @DisplayName("凭据列不被整行覆盖抹掉：哈希与签名密钥必须原样在库里")
        void keepsKeyHashAndSecret() {
            String appId = mapper.seed(OWNER, "chat");
            ApiApp before = mapper.appInDb(appId);

            service.updateScopes(appId, OWNER, "chat,call");

            ApiApp after = mapper.appInDb(appId);
            assertThat(after.getAppKeyHash()).isEqualTo(before.getAppKeyHash());
            assertThat(after.getWebhookSecret()).isEqualTo(before.getWebhookSecret());
            assertThat(after.getAppName()).isEqualTo(before.getAppName());
            assertThat(after.getUserId()).isEqualTo(before.getUserId());
        }
    }
}
