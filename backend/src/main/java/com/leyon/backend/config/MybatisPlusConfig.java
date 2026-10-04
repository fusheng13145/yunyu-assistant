package com.leyon.backend.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置（v2.79 · C-151，收口候选 ㊿）
 *
 * 乐观锁拦截器是 @Version 生效的前提：没有它，实体上的 @Version 注解只是摆设，
 * UPDATE 不会带 WHERE version = ? ——多标签页后保存方仍会拿旧值覆盖他列，
 * 且全部单测照样绿（静默失效正是 ㊿ 一直没被顺手修的原因），所以连接器取值被专项判据钉住。
 * 刻意只注册乐观锁一个拦截器：分页在本仓是手写 LIMIT/OFFSET（pageByUser），引入分页插件属无收益的行为面扩张。
 *
 * @author leyon
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
