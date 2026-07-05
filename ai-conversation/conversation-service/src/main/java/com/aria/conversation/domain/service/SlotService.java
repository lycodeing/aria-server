package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;

import java.util.List;
import java.util.Map;

// TODO: SlotService 接口目前引用 infrastructure 类型（ChatMessage、SlotConfig）
// 需在后续迭代将这两个类移至 domain/model/ 以完全消除 infrastructure 依赖
/**
 * Slot 提取领域服务接口。
 */
public interface SlotService {
    Map<String, Object> extract(String userMessage,
                                List<ChatMessage> recentHistory,
                                List<SlotConfig> slots);
}
