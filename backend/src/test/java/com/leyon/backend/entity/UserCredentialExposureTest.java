package com.leyon.backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.backend.common.ApiResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户凭据的外发面单元测试（v2.48 · C-107）
 * <p>
 * 变更前 {@code password} 的抑制靠每个出口手写 {@code setPassword(null)}，漏一处即把 BCrypt 哈希
 * 发给前端；变更后抑制落在实体上，所以这里锁的是"外发必然没有凭据"这条不变量，而不是某次调用顺序。
 * 入站方向同样锁死：本仓所有请求体都是 {@code Map<String, String>}，没有任何路径需要由 JSON 写入
 * 密码，放开入站只会给未来的 {@code @RequestBody User} 留下面口令哈希的口子。
 *
 * @author leyon
 */
class UserCredentialExposureTest {

    /** 形状与真实 BCrypt 输出一致，便于断言"泄露的是哈希"而非"泄露了这个字面量" */
    private static final String PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static User loadedUser() {
        User user = new User();
        user.setId("u-1");
        user.setUsername("leyon");
        user.setNickname("云谕");
        user.setPassword(PASSWORD_HASH);
        user.setTokenVersion(3);
        user.setRole(User.ROLE_USER);
        return user;
    }

    @Nested
    class OutboundSuppression {

        /** 真实出口形状：{@code GET /api/auth/me} 与 {@code GET /api/admin/users} 都把 User 直接放进响应体 */
        @Test
        void responseEnvelope_carriesProfileButNeverPassword() throws Exception {
            String json = objectMapper.writeValueAsString(ApiResponse.success(loadedUser()));

            assertThat(json).doesNotContain("password").doesNotContain(PASSWORD_HASH);
            assertThat(json).contains("leyon").contains("u-1");
        }

        @Test
        void bareEntity_alsoSuppressesPassword() throws Exception {
            String json = objectMapper.writeValueAsString(loadedUser());

            assertThat(json).doesNotContain("password").doesNotContain(PASSWORD_HASH);
        }

        /** 凭据版本戳同为服务端内部状态（v2.42），外发只会告诉攻击者"改密后版本号是几" */
        @Test
        void tokenVersion_notSerialized() throws Exception {
            String json = objectMapper.writeValueAsString(loadedUser());

            assertThat(json).doesNotContain("tokenVersion");
        }

        /** 日志里出现哈希同样是泄露：toString 常被直接打进 log */
        @Test
        void toString_doesNotPrintHash() {
            String text = loadedUser().toString();

            assertThat(text).doesNotContain(PASSWORD_HASH).doesNotContain("password=");
            assertThat(text).contains("leyon");
        }
    }

    @Nested
    class InboundStaysClosedAndInternalsReadable {

        /**
         * Jackson 会把字段与 accessor 上的注解合并成同一个逻辑属性，所以"只加在 getter 上"
         * 并不能保住入站绑定——登记 ㉖ 时按这个假设写的口径是错的，这里把它钉死。
         */
        @Test
        void inboundJson_doesNotBindCredentialFields() throws Exception {
            User user = objectMapper.readValue(
                    "{\"username\":\"leyon\",\"password\":\"" + PASSWORD_HASH + "\",\"tokenVersion\":99}",
                    User.class);

            assertThat(user.getUsername()).isEqualTo("leyon");
            assertThat(user.getPassword()).isNull();
            assertThat(user.getTokenVersion()).isNull();
        }

        /** 抑制只作用于 JSON：登录校验要读哈希、注册要写哈希，这两条链路不能被注解顺手切断 */
        @Test
        void accessors_stillWorkInProcess() {
            User user = new User();
            user.setPassword(PASSWORD_HASH);

            assertThat(user.getPassword()).isEqualTo(PASSWORD_HASH);
        }
    }
}
