package com.aria.customerservice.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 客服认证服务启动类。
 * 端口：8083，独立于 ai-dev-platform，服务于 ai-customerservice 项目。
 */
@SpringBootApplication
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
