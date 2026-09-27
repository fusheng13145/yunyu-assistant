package com.leyon.backend.service;

import com.leyon.backend.entity.ApiApp;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存应用表：建模 v2.46 能力变更要用到的四条行语义
 * <ul>
 *   <li>{@code selectById} 与 MyBatis-Plus 的 {@code @TableLogic} 同口径——逻辑删除的行读不到；</li>
 *   <li>{@code updateById} 整行覆盖 {@code scopes} 并返回命中行数，"0 行"是可见结果而不是异常；</li>
 *   <li>{@code deleteById(id)} 是逻辑删除（置 {@code isDeleted}），不是物理删行；</li>
 *   <li>{@code insert} 生成 UUID 主键，让创建路径与真实一致地"先有 id 再落库"。</li>
 * </ul>
 * 条件查询（{@code selectOne(Wrapper)} 这类按哈希/属主过滤）刻意**未建模**：本文件的测试都不依赖它，
 * 误用会抛 {@link UnsupportedOperationException} 而不是静默返回错误的行集。
 * 真库语义（唯一索引、JSON 列、并发行锁）另见 scripts/smoke.sh §7.10。
 *
 * @author leyon
 */
class FakeApiAppMapper extends ApiAppMapperStub {

    private final Map<String, ApiApp> rows = new LinkedHashMap<>();
    private int sequence;

    /** 预置一行应用（属主与能力可控），返回其 id */
    String seed(String userId, String scopes) {
        ApiApp app = new ApiApp();
        app.setId("app-" + (++sequence));
        app.setUserId(userId);
        app.setAppName("seed-" + app.getId());
        app.setScopes(scopes);
        app.setAppKeyHash("hash-of-" + app.getId());
        app.setWebhookSecret("secret-of-" + app.getId());
        app.setEnabled(ApiApp.ENABLED);
        app.setIsDeleted(ApiApp.NOT_DELETED);
        rows.put(app.getId(), app);
        return app.getId();
    }

    /** 直接读表里的当前值：断言"库里到底写没写进去"，而不是只信返回值 */
    String scopesInDb(String id) {
        ApiApp app = rows.get(id);
        return app == null ? null : app.getScopes();
    }

    /** 读整行（含逻辑删除的行），用于证明"改能力不会顺手动到凭据列" */
    ApiApp appInDb(String id) {
        ApiApp app = rows.get(id);
        return app == null ? null : copyOf(app);
    }

    boolean isDeletedInDb(String id) {
        ApiApp app = rows.get(id);
        return app != null && ApiApp.DELETED == app.getIsDeleted();
    }

    List<String> idsInDb() {
        return new ArrayList<>(rows.keySet());
    }

    @Override
    public int insert(ApiApp entity) {
        if (entity.getId() == null) {
            entity.setId("app-" + (++sequence));
        }
        rows.put(entity.getId(), copyOf(entity));
        return 1;
    }

    @Override
    public ApiApp selectById(Serializable id) {
        ApiApp app = rows.get(String.valueOf(id));
        if (app == null || ApiApp.DELETED == app.getIsDeleted()) {
            return null;
        }
        return copyOf(app);
    }

    /**
     * 与 MyBatis-Plus 默认 {@code FieldStrategy.NOT_NULL} 同口径：null 字段不参与更新。
     * 这条建模是刻意的——"改能力"只该写 {@code scopes}，若实现传回整行且漏了凭据列，
     * 这里就会把它覆盖成 null 并让相应断言变红。
     */
    @Override
    public int updateById(ApiApp entity) {
        ApiApp stored = rows.get(entity.getId());
        if (stored == null || ApiApp.DELETED == stored.getIsDeleted()) {
            return 0;
        }
        if (entity.getScopes() != null) {
            stored.setScopes(entity.getScopes());
        }
        if (entity.getIsDeleted() != null) {
            stored.setIsDeleted(entity.getIsDeleted());
        }
        return 1;
    }

    @Override
    public int deleteById(Serializable id) {
        ApiApp stored = rows.get(String.valueOf(id));
        if (stored == null || ApiApp.DELETED == stored.getIsDeleted()) {
            return 0;
        }
        stored.setIsDeleted(ApiApp.DELETED);
        return 1;
    }

    /** 存副本：真实库里"读出来的实体"与"表里的行"不是同一个对象，改返回值不该影响表内容 */
    private ApiApp copyOf(ApiApp source) {
        ApiApp copy = new ApiApp();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setAppName(source.getAppName());
        copy.setScopes(source.getScopes());
        copy.setAppKeyHash(source.getAppKeyHash());
        copy.setAppKey(source.getAppKey());
        copy.setWebhookUrl(source.getWebhookUrl());
        copy.setWebhookSecret(source.getWebhookSecret());
        copy.setEnabled(source.getEnabled());
        copy.setIsDeleted(source.getIsDeleted());
        return copy;
    }
}
