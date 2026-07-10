package com.aria.conversation.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置。
 *
 * <p>替代 redisson-spring-boot-starter 的自动配置，解决空密码场景下
 * 自动配置会向无密码 Redis 发送 {@code AUTH ""} 命令导致连接失败的问题：
 * Lettuce 忽略空密码，但 Redisson 不会。
 *
 * <p>当 {@code spring.data.redis.password} 为 null 或空字符串时，不设置密码（跳过 AUTH）；
 * 否则正常设置密码。
 */
@Slf4j
@Configuration
public class RedissonClientConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    /**
     * 创建 RedissonClient Bean。
     * redisson-spring-boot-starter 检测到用户定义的 RedissonClient Bean 时，
     * 会跳过自动配置，避免重复创建。
     *
     * @return 配置好的 RedissonClient
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig ssc = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(2)
                .setConnectTimeout(10_000)
                .setTimeout(3_000)
                .setRetryAttempts(3)
                .setRetryInterval(1_500);

        if (password != null && !password.isEmpty()) {
            ssc.setPassword(password);
            log.info("[Redisson] 使用密码连接 Redis {}:{}", host, port);
        } else {
            log.info("[Redisson] 无密码连接 Redis {}:{}", host, port);
        }

        return Redisson.create(config);
    }
}
