package com.aria.conversation.infrastructure.config;

import com.aria.common.core.page.PageQuery;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>注册分页插件，使 {@code selectPage} 正确执行 count 查询并截取分页记录。
 * 未配置此前 {@code Page.getTotal()} 恒为 0。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pageInterceptor =
                new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pageInterceptor.setMaxLimit((long) PageQuery.MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pageInterceptor);
        return interceptor;
    }
}
