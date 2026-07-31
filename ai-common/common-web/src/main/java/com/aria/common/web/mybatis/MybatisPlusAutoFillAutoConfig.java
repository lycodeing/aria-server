package com.aria.common.web.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局 MyBatis-Plus 自动填充自动配置。
 *
 * <p>向所有依赖 common-web 的服务注册统一的 {@link AutoFillMetaObjectHandler}，
 * 由 {@code AutoConfiguration.imports} 加载。
 *
 * <p>{@code @ConditionalOnClass} 保证仅在 classpath 存在 MyBatis-Plus 时生效；
 * {@code @ConditionalOnMissingBean} 允许各服务按需自定义覆盖。
 */
@Configuration
@ConditionalOnClass(MetaObjectHandler.class)
public class MybatisPlusAutoFillAutoConfig {

    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    public MetaObjectHandler autoFillMetaObjectHandler() {
        return new AutoFillMetaObjectHandler();
    }
}
