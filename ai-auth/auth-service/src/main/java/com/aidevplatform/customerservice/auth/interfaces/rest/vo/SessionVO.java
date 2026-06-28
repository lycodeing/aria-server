package com.aidevplatform.customerservice.auth.interfaces.rest.vo;

import java.util.List;

/**
 * 会话列表 VO。
 */
public record SessionVO(Long userId, String current, List<String> tokens, int total) {}
