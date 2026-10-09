package com.leyon.backend.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 登录/注册标识判据（唯一判据入口，v2.89）
 * <p>
 * 一个输入框要同时接受用户名、邮箱、手机号，就必须把"这串字符该往哪一列查"收敛到一处：
 * 登录路由、注册校验、迁移 0013 的唯一索引三处共用同一形状判据，任何一处在别处再算一遍都会漂移。
 *
 * <p>读写两侧的宽容度刻意不同：
 * <ul>
 *   <li><b>读侧 {@link #classify(String)} 只看形状、不校验格式</b>。历史用户名可以是任意形状（纯数字、
 *       带 @），判错形状只是让登录走一次兜底查询；在此处报"格式不正确"等于把存量账号锁在门外。</li>
 *   <li><b>写侧 {@code validate*} 严格</b>。注册时拒绝"邮箱/手机号形状的用户名"，是为了让
 *       {@link #classify} 的三态在库里始终只对应一列——否则同一串标识符可能同时命中用户名与手机号，
 *       而登录只能选一行，另一行的账号永远登不进来（判不出来的竞态比报错更难查）。</li>
 * </ul>
 *
 * <p>归一化只作用于邮箱与手机号，<b>用户名刻意不归一</b>：存量行是按用户原始输入落库的，读侧改成
 * 小写会让"注册时叫 Alice、现在输入 alice"这种历史不匹配变成"两边都不认"。这条不对称是有意保留的，
 * 见手册 2.1。
 *
 * @author leyon
 */
@Component
public class IdentifierPolicy {

    /** 标识符三态 */
    public enum Kind {
        /** 用户名 */
        USERNAME,
        /** 邮箱 */
        EMAIL,
        /** 手机号 */
        PHONE
    }

    /**
     * 手机号形状：可选 + 前缀，7~15 位纯数字。
     * <p>
     * 上限 15 是 E.164 的位数上限，不是风格：列宽 {@code users.phone VARCHAR(20)} 只能再容一个 '+'，
     * 放宽到 20 位就会写出 21 字符，后果是 MySQL 1406 报错（对外是 500）而不是"手机号格式不正确"。
     */
    private static final Pattern PHONE_SHAPE = Pattern.compile("^\\+?[0-9]{7,15}$");

    /** 邮箱形状（归一化后判定）：本地段非空、域名段至少一个点、不含空白 */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** 与 {@code users.email VARCHAR(100)} 同值：超长的邮箱必须在写侧拒掉，理由见 {@link #PHONE_SHAPE} */
    private static final int MAX_EMAIL_LENGTH = 100;

    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[-()\\.\\s]");

    /**
     * 按形状判出标识符归属。全函数：任何输入都得到一个态，不抛异常也不返回 null。
     *
     * @param identifier 原始输入
     * @return EMAIL（含 @）/ PHONE（剥离分隔符后匹配手机号形状）/ USERNAME（其余）
     */
    public Kind classify(String identifier) {
        if (identifier == null) {
            return Kind.USERNAME;
        }
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            return Kind.EMAIL;
        }
        if (PHONE_SHAPE.matcher(PHONE_SEPARATORS.matcher(trimmed).replaceAll("")).matches()) {
            return Kind.PHONE;
        }
        return Kind.USERNAME;
    }

    /**
     * 邮箱归一化：去首尾空白 + 转小写；空白输入归成 {@code null}。
     * <p>
     * 归成 null 而不是空串是关键——迁移 0013 的唯一索引建在生成列上，NULL 不参与唯一约束，
     * 空串会占住唯一槽位，导致第二个"没填邮箱"的用户注册失败。
     */
    public String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * 手机号归一化：剥离空格、连字符、括号与点；空白输入归成 {@code null}。
     * <p>
     * <b>不做国家码推断</b>：{@code 13800000000} 与 {@code +8613800000000} 是两个不同的值，
     * 谁也不认谁。推断要依赖部署地或用户 locale，猜错的代价是"号占了但本人登不进"，
     * 比要求用户输入注册时那一种写法更贵。
     */
    public String normalizePhone(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = PHONE_SEPARATORS.matcher(raw.trim()).replaceAll("");
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * 校验邮箱格式（写侧）。
     *
     * @param email 原始输入，内部先归一化
     * @throws IllegalArgumentException 归一化后仍不匹配邮箱形状
     */
    public void validateEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null || normalized.length() > MAX_EMAIL_LENGTH
                || normalized.chars().filter(ch -> ch == '@').count() != 1
                || !EMAIL_SHAPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
    }

    /**
     * 校验手机号格式（写侧）。
     *
     * @param phone 原始输入，内部先归一化
     * @throws IllegalArgumentException 归一化后不匹配手机号形状
     */
    public void validatePhone(String phone) {
        String normalized = normalizePhone(phone);
        if (normalized == null || !PHONE_SHAPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("手机号格式不正确");
        }
    }

    /**
     * 校验用户名形状（写侧不变量）：不得占用邮箱或手机号形状。
     * <p>
     * 只判形状、不判长度与字符集——那些仍由 {@code AuthController} 现有校验负责，本方法不重复。
     *
     * @param username 用户名
     * @throws IllegalArgumentException 形状命中邮箱或手机号
     */
    public void validateUsernameShape(String username) {
        Kind kind = classify(username);
        if (kind != Kind.USERNAME) {
            throw new IllegalArgumentException(
                    "用户名不能是" + (kind == Kind.EMAIL ? "邮箱" : "手机号") + "形状，请在对应字段填写");
        }
    }
}
