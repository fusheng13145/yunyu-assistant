package com.leyon.backend.service;

import com.leyon.backend.entity.User;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 内存账号表：建模 v2.42 凭据版本相关的三条行语义
 * <ul>
 *   <li>{@code selectTokenVersion} 与 {@code @Select} 同口径——行缺失返回 null，列值为 NULL 读成 0；</li>
 *   <li>{@code updatePasswordAndBumpTokenVersion} 是单条语句，密码与版本一起落，行数即命中与否；</li>
 *   <li>{@code selectById} 只填 id/username/password，够 {@code UserService.changePassword} 用。</li>
 * </ul>
 * 其余方法继承 {@link UserMapperStub} 的"未建模即抛"，误用会在测试里立刻暴露。
 * SQL 的字面语义（IFNULL、is_deleted 过滤、并发下的行锁）另见真机一轮，手册 7.4 v2.42。
 *
 * @author leyon
 */
class FakeUserMapper extends UserMapperStub {

    private final Map<String, Integer> versions = new HashMap<>();
    private final Map<String, String> passwords = new HashMap<>();

    /** 放一个账号：version 为 null 表示列值 NULL（迁移前的旧行） */
    FakeUserMapper with(String id, Integer version) {
        versions.put(id, version);
        return this;
    }

    FakeUserMapper withPassword(String id, String encodedPassword, Integer version) {
        passwords.put(id, encodedPassword);
        return with(id, version);
    }

    Integer versionOf(String id) {
        return versions.get(id);
    }

    @Override
    public User selectById(Serializable id) {
        if (!versions.containsKey(id)) {
            return null;
        }
        User user = new User();
        user.setId(String.valueOf(id));
        user.setUsername("tester");
        user.setPassword(passwords.get(String.valueOf(id)));
        user.setTokenVersion(versions.get(String.valueOf(id)));
        return user;
    }

    @Override
    public Integer selectTokenVersion(String userId) {
        if (!versions.containsKey(userId)) {
            return null;
        }
        Integer version = versions.get(userId);
        return version == null ? 0 : version;
    }

    @Override
    public int updatePasswordAndBumpTokenVersion(String userId, String password) {
        if (!versions.containsKey(userId)) {
            return 0;
        }
        Integer current = versions.get(userId);
        versions.put(userId, (current == null ? 0 : current) + 1);
        passwords.put(userId, password);
        return 1;
    }
}
