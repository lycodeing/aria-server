package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

/**
 * HTTP 工具认证头策略。
 * 新增认证方式只需新增 @Component 实现，无需修改 HttpToolRunner（开闭原则）。
 */
public interface HttpAuthStrategy {
    /** 返回此策略处理的认证类型，如 "BEARER"、"API_KEY"、"NONE" */
    String authType();
    /** 将认证信息写入请求头 */
    void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper);
}
