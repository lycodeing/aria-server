package com.aria.conversation.infrastructure.dit.pipeline;

import java.util.List;
import java.util.Map;

/**
 * 槽位解析结果。
 *
 * @param status        解析状态
 * @param resolvedSlots 已解析完成的槽位值 Map（status=RESOLVED 时有值）
 * @param pendingSlot   当前挂起的槽位名（status=DISCOVERED/MISSING 时有值）
 * @param pendingType   挂起类型：DISCOVERED / MISSING
 * @param candidates    DISCOVERED 时的候选项列表
 * @param promptMessage 需要返回给用户的消息（DISCOVERED/MISSING 时有值）
 */
public record SlotResolveResult(
        Status status,
        Map<String, Object> resolvedSlots,
        String pendingSlot,
        String pendingType,
        List<Map<String, String>> candidates,
        String promptMessage
) {
    public static SlotResolveResult resolved(Map<String, Object> slots) {
        return new SlotResolveResult(Status.RESOLVED, slots, null, null, null, null);
    }

    public static SlotResolveResult discovered(String slotName,
                                               List<Map<String, String>> candidates,
                                               String prompt,
                                               Map<String, Object> alreadyResolved) {
        return new SlotResolveResult(Status.DISCOVERED, alreadyResolved,
                slotName, "DISCOVERED", candidates, prompt);
    }

    public static SlotResolveResult missing(String slotName, String prompt,
                                            Map<String, Object> alreadyResolved) {
        return new SlotResolveResult(Status.MISSING, alreadyResolved,
                slotName, "MISSING", null, prompt);
    }

    public static SlotResolveResult giveUp(String prompt) {
        return new SlotResolveResult(Status.GIVE_UP, Map.of(), null, null, null, prompt);
    }

    /**
     * 等待用户处理
     */
    public boolean isPending() {
        return status == Status.DISCOVERED || status == Status.MISSING;
    }

    /**
     * 重试超过阈值，触发兜底转人工
     */
    public boolean isGiveUp() {
        return status == Status.GIVE_UP;
    }

    public boolean isResolved() {
        return status == Status.RESOLVED;
    }

    public enum Status {
        /**
         * 所有必填槽位已解析完成，可继续执行 Pipeline
         */
        RESOLVED,
        /**
         * 发现候选项，等待用户选择
         */
        DISCOVERED,
        /**
         * 槽位缺失，等待用户输入
         */
        MISSING,
        /**
         * 重试超过阈值，触发兜底转人工
         */
        GIVE_UP
    }
}
