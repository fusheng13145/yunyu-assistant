package com.leyon.backend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.leyon.backend.entity.User;
import com.leyon.backend.mapper.UserMapper;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.ResultHandler;

/**
 * UserMapper 的测试替身基类：只为本包测试提供"实现整个接口但一律拒绝调用"的底子，
 * 具体桩覆写自己真正建模的方法（含 v2.42 的凭据版本两条）。
 * <p>
 * 签名对齐 mybatis-plus 3.5.7 的 {@code BaseMapper} 抽象方法；升级 mybatis-plus 后若新增抽象方法，
 * 本类会在编译期立刻报错，而不是让测试静默失去覆盖。
 *
 * @author leyon
 */
abstract class UserMapperStub implements UserMapper {

    private UnsupportedOperationException unused(String name) {
        return new UnsupportedOperationException("测试替身未建模该方法: " + name);
    }

    @Override
    public int insert(User entity) {
        throw unused("insert");
    }

    @Override
    public int deleteById(User entity) {
        throw unused("deleteById(entity)");
    }

    @Override
    public int deleteById(Serializable id) {
        throw unused("deleteById(id)");
    }

    @Override
    public int deleteById(Object id, boolean useLogicDelete) {
        throw unused("deleteById(id, boolean)");
    }

    @Override
    public int deleteByMap(Map<String, Object> columnMap) {
        throw unused("deleteByMap");
    }

    @Override
    public int delete(Wrapper<User> queryWrapper) {
        throw unused("delete");
    }

    @Override
    public int deleteBatchIds(Collection<?> idList) {
        throw unused("deleteBatchIds");
    }

    @Override
    public int updateById(User entity) {
        throw unused("updateById");
    }

    @Override
    public int update(User entity, Wrapper<User> updateWrapper) {
        throw unused("update(entity, wrapper)");
    }

    @Override
    public int update(Wrapper<User> updateWrapper) {
        throw unused("update(wrapper)");
    }

    @Override
    public User selectById(Serializable id) {
        throw unused("selectById");
    }

    @Override
    public List<User> selectBatchIds(Collection<? extends Serializable> idList) {
        throw unused("selectBatchIds");
    }

    @Override
    public void selectBatchIds(Collection<? extends Serializable> idList, ResultHandler<User> handler) {
        throw unused("selectBatchIds(handler)");
    }

    @Override
    public List<User> selectByMap(Map<String, Object> columnMap) {
        throw unused("selectByMap");
    }

    @Override
    public Long selectCount(Wrapper<User> queryWrapper) {
        throw unused("selectCount");
    }

    @Override
    public List<User> selectList(Wrapper<User> queryWrapper) {
        throw unused("selectList");
    }

    @Override
    public void selectList(Wrapper<User> queryWrapper, ResultHandler<User> handler) {
        throw unused("selectList(handler)");
    }

    @Override
    public List<User> selectList(IPage<User> page, Wrapper<User> queryWrapper) {
        throw unused("selectList(page)");
    }

    @Override
    public void selectList(IPage<User> page, Wrapper<User> queryWrapper,
                           ResultHandler<User> handler) {
        throw unused("selectList(page, handler)");
    }

    @Override
    public List<Map<String, Object>> selectMaps(Wrapper<User> queryWrapper) {
        throw unused("selectMaps");
    }

    @Override
    public void selectMaps(Wrapper<User> queryWrapper,
                           ResultHandler<Map<String, Object>> handler) {
        throw unused("selectMaps(handler)");
    }

    @Override
    public List<Map<String, Object>> selectMaps(IPage<? extends Map<String, Object>> page,
                                                Wrapper<User> queryWrapper) {
        throw unused("selectMaps(page)");
    }

    @Override
    public void selectMaps(IPage<? extends Map<String, Object>> page, Wrapper<User> queryWrapper,
                           ResultHandler<Map<String, Object>> handler) {
        throw unused("selectMaps(page, handler)");
    }

    @Override
    public <E> List<E> selectObjs(Wrapper<User> queryWrapper) {
        throw unused("selectObjs");
    }

    @Override
    public <E> void selectObjs(Wrapper<User> queryWrapper, ResultHandler<E> handler) {
        throw unused("selectObjs(handler)");
    }

    @Override
    public <P extends IPage<User>> P selectPage(P page, Wrapper<User> queryWrapper) {
        throw unused("selectPage");
    }

    @Override
    public <P extends IPage<Map<String, Object>>> P selectMapsPage(P page, Wrapper<User> queryWrapper) {
        throw unused("selectMapsPage");
    }

    @Override
    public boolean insertOrUpdate(User entity) {
        throw unused("insertOrUpdate(entity)");
    }

    @Override
    public List<BatchResult> insert(Collection<User> entityList) {
        throw unused("insert(collection)");
    }

    @Override
    public List<BatchResult> insert(Collection<User> entityList, int batchSize) {
        throw unused("insert(collection, batchSize)");
    }

    @Override
    public List<BatchResult> updateById(Collection<User> entityList) {
        throw unused("updateById(collection)");
    }

    @Override
    public List<BatchResult> updateById(Collection<User> entityList, int batchSize) {
        throw unused("updateById(collection, batchSize)");
    }

    @Override
    public List<BatchResult> insertOrUpdate(Collection<User> entityList) {
        throw unused("insertOrUpdate(collection)");
    }

    @Override
    public List<BatchResult> insertOrUpdate(Collection<User> entityList, int batchSize) {
        throw unused("insertOrUpdate(collection, batchSize)");
    }
}
