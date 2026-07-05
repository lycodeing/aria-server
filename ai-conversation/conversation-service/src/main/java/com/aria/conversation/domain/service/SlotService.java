package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;

import java.util.List;
import java.util.Map;

/**
 * Slot 提取领域服务接口。
 */
public interface SlotService {
    Map<String, Object> extract(String userMessage,
                                List<ChatMessage> recentHistory,
                                List<SlotConfig> slots);
}
