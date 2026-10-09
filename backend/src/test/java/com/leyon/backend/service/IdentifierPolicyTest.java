package com.leyon.backend.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录/注册标识判据单元测试（v2.89）
 * <p>
 * 锁的是三件事：
 * <ul>
 *   <li><b>形状分类必须与归一化同序</b>——"含 @ 才算邮箱"看着简单，但 "+86 138-0000-0000" 这种带分隔符的
 *       手机号若先做形状匹配会退化成用户名，登录直接查不到人；</li>
 *   <li><b>读侧宽容、写侧严格</b>——{@code classify} 只看形状、绝不校验格式（历史用户名可能是任意形状，
 *       判错形状只是走兜底路径，报"格式不正确"则是把人锁在门外）；{@code validate*} 只在注册时生效；</li>
 *   <li><b>用户名不得占用邮箱/手机号形状</b>——这是"路由确定性"的写侧不变量：一旦允许注册
 *       "a@b.co" 这样的用户名，同一串标识符在登录时只能命中一列，另一列的账号就永远登不进来。</li>
 * </ul>
 * 每条判据都配了反向锚点：只写"合法形状被认出来"，则"一律当用户名"这种改法同样全绿。
 *
 * @author leyon
 */
class IdentifierPolicyTest {

    private final IdentifierPolicy policy = new IdentifierPolicy();

    // ===================== 形状分类 =====================

    @Test
    void classify_emailShape() {
        assertThat(policy.classify("a@b.co")).isEqualTo(IdentifierPolicy.Kind.EMAIL);
        // 反向锚点：不是"含数字就当手机号"，@ 的优先级必须高于数字
        assertThat(policy.classify("user123@example.com")).isEqualTo(IdentifierPolicy.Kind.EMAIL);
    }

    @Test
    void classify_phoneShape() {
        assertThat(policy.classify("13800000000")).isEqualTo(IdentifierPolicy.Kind.PHONE);
        assertThat(policy.classify("+8613800000000")).isEqualTo(IdentifierPolicy.Kind.PHONE);
    }

    @Test
    void classify_usernameShape() {
        assertThat(policy.classify("alice")).isEqualTo(IdentifierPolicy.Kind.USERNAME);
        // 反向锚点：短数字不是手机号形状，必须回落用户名，否则 5 位数字账号永远查不到
        assertThat(policy.classify("12345")).isEqualTo(IdentifierPolicy.Kind.USERNAME);
        // 反向锚点：带字母的数字串不是手机号
        assertThat(policy.classify("abc13800000000")).isEqualTo(IdentifierPolicy.Kind.USERNAME);
    }

    @Test
    void classify_toleratesSeparatorsInPhone() {
        // 用户从通讯录复制号码就会带分隔符；这里不宽容，登录就只能要求用户手工删空格
        assertThat(policy.classify("+86 138-0000-0000")).isEqualTo(IdentifierPolicy.Kind.PHONE);
        assertThat(policy.classify("138 0000 0000")).isEqualTo(IdentifierPolicy.Kind.PHONE);
    }

    // ===================== 归一化 =====================

    @Test
    void normalizeEmail_trimsAndLowercases() {
        assertThat(policy.normalizeEmail("  Alice@Example.COM ")).isEqualTo("alice@example.com");
    }

    @Test
    void normalizeEmail_blankBecomesNull() {
        // 空串与空白必须归成 null：库里 NULL 不参与唯一索引，空串会占用唯一槽位
        assertThat(policy.normalizeEmail("   ")).isNull();
        assertThat(policy.normalizeEmail(null)).isNull();
    }

    @Test
    void normalizePhone_stripsSeparatorsKeepsPlus() {
        assertThat(policy.normalizePhone("+86 (138) 0000-0000")).isEqualTo("+8613800000000");
        assertThat(policy.normalizePhone("13800000000")).isEqualTo("13800000000");
        assertThat(policy.normalizePhone("")).isNull();
    }

    // ===================== 格式校验（仅写侧） =====================

    @Test
    void validateEmail_acceptsPlainAddress() {
        assertThatCode(() -> policy.validateEmail("alice@example.com")).doesNotThrowAnyException();
    }

    @Test
    void validateEmail_rejectsMissingDomainDot() {
        assertThatThrownBy(() -> policy.validateEmail("alice@example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱");
    }

    @Test
    void validateEmail_rejectsEmptyLocalPart() {
        assertThatThrownBy(() -> policy.validateEmail("@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱");
    }

    @Test
    void validateEmail_rejectsMultipleAtSigns() {
        assertThatThrownBy(() -> policy.validateEmail("a@b@c.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱");
    }

    @Test
    void validatePhone_acceptsNationalAndInternational() {
        assertThatCode(() -> policy.validatePhone("13800000000")).doesNotThrowAnyException();
        assertThatCode(() -> policy.validatePhone("+8613800000000")).doesNotThrowAnyException();
    }

    @Test
    void validatePhone_rejectsTooShort() {
        assertThatThrownBy(() -> policy.validatePhone("12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("手机号");
    }

    @Test
    void validatePhone_rejectsNonDigitsAfterPlus() {
        assertThatThrownBy(() -> policy.validatePhone("+86-138-0000-000a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("手机号");
    }

    @Test
    void validatePhone_rejectsBeyondE164Length() {
        // 16 位起被拒有两个理由叠在一起：E.164 的位数上限是 15，而 users.phone 是 VARCHAR(20)——
        // 放到 20 位数字（带 + 是 21 字符）就写出列宽之外，后果是 MySQL 1406（对外 500），
        // 不是"手机号格式不正确"
        assertThatThrownBy(() -> policy.validatePhone("1234567890123456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("手机号");
    }

    @Test
    void validateEmail_rejectsBeyondColumnWidth() {
        // 同上：users.email 是 VARCHAR(100)，格式合格但超长的邮箱必须在写侧就拒掉
        assertThatThrownBy(() -> policy.validateEmail("a".repeat(95) + "@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("邮箱");
    }

    // ===================== 用户名形状拒绝（写侧不变量） =====================

    @Test
    void validateUsername_acceptsOrdinaryNames() {
        assertThatCode(() -> policy.validateUsernameShape("alice")).doesNotThrowAnyException();
        // 反向锚点：历史里已有的纯短数字用户名不得被这条判据判死，否则改口令/建号会受影响
        assertThatCode(() -> policy.validateUsernameShape("12345")).doesNotThrowAnyException();
    }

    @Test
    void validateUsername_rejectsEmailShape() {
        assertThatThrownBy(() -> policy.validateUsernameShape("a@b.co"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名");
    }

    @Test
    void validateUsername_rejectsPhoneShape() {
        assertThatThrownBy(() -> policy.validateUsernameShape("13800000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户名");
    }

    // ===================== 三态互斥（分类必须是全函数） =====================

    @Test
    void everyIdentifierMapsToExactlyOneKind() {
        // classify 不得返回 null / 抛异常：登录链路对每个输入都要能选出一条查询路径
        for (String raw : new String[] {"alice", "a@b.co", "13800000000", "+8613800000000",
                "12345", "+", "@", "  alice ", "a1@2", "0000000", "user.name+tag@example.co.uk"}) {
            assertThat(policy.classify(raw)).as("标识符 " + raw).isNotNull();
        }
    }
}
