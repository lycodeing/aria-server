package com.aria.conversation.domain.service;

import com.aria.conversation.domain.model.MultiIntentResult;

/**
 * 多意图识别领域服务接口。
 *
 * <p>实现在 infrastructure 层（{@link com.aria.conversation.infrastructure.ai.MultiHybridIntentService}），
 * 保持 DDD 分层。任何失败均返回 {@link MultiIntentResult#UNKNOWN}，不抛异常。
 */
public interface MultiIntentService {

    /**
     * 仅基于 {@code __system__} 域意图做分类（通用 FAQ 路径使用）。
     */
    MultiIntentResult classifyMulti(String userMessage);

    /**
     * 基于 {@code __system__} 域 + 指定域意图合并做分类（域路径使用）。
     *
     * <p>域级业务意图（如 query_logistics）定义在 {@code domainCode} 域，
     * 路由级意图（COMPLAINT/TRANSFER_REQUEST 等）定义在 {@code __system__} 域，
     * 两者合并后统一分类，确保业务意图被正确识别。
     *
     * @param domainCode 当前活跃域 code（null 时等同于 {@link #classifyMulti(String)}）
     */
    MultiIntentResult classifyMulti(String userMessage, String domainCode);
}
