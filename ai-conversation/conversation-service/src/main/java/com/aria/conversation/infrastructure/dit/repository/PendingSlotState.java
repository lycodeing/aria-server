package com.aria.conversation.infrastructure.dit.repository;

import java.util.List;
import java.util.Map;

/**
 * 槽位解析挂起状态，存储于 Redis。
 *
 * <p>当槽位处于 DISCOVERED（展示候选项等待选择）或 MISSING（等待用户输入）时，
 * pipeline 进入等待，下一轮对话从此恢复上下文继续执行。
 *
 * @param sessionId     会话 ID
 * @param domainCode    领域标识
 * @param intentCode    当前意图标识
 * @param pendingSlot   当前等待解析的槽位名
 * @param pendingType   DISCOVERED / MISSING
 * @param candidates    DISCOVERED 时的候选项列表，每项包含 id 和 label
 * @param resolvedSlots 已解析完成的槽位值 Map
 * @param retryCount    已重试次数，达到 MAX_RETRY 触发兜底转人工
 */
public record PendingSlotState(
        String sessionId,
        String domainCode,
        String intentCode,
        String pendingSlot,
        String pendingType,
        List<Map<String, String>> candidates,
        Map<String, Object> resolvedSlots,
        int retryCount
) {
    /** 最大重试次数，超过后触发兜底转人工 */
    public static final int MAX_RETRY = 2;

    public boolean isDiscovered() { return "DISCOVERED".equals(pendingType); }
    public boolean isMissing()    { return "MISSING".equals(pendingType); }
    public boolean shouldGiveUp() { return retryCount >= MAX_RETRY; }

    /** 返回 retryCount +1 的新实例 */
    public PendingSlotState withIncrementedRetry() {
        return new PendingSlotState(sessionId, domainCode, intentCode,
                pendingSlot, pendingType, candidates, resolvedSlots, retryCount + 1);
    }
}
