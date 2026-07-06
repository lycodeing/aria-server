package com.aria.conversation.infrastructure.dit.repository;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 槽位解析挂起状态，存储于 Redis。
 *
 * <p>当槽位处于 DISCOVERED（展示候选项等待选择）或 MISSING（等待用户输入）时，
 * pipeline 进入等待，下一轮对话从此恢复上下文继续执行。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 防止 Jackson 将
 * is* 方法当作 Boolean getter 序列化到 JSON，导致反序列化时出现 UnrecognizedPropertyException。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PendingSlotState {

    /** 最大重试次数，超过后触发兜底转人工 */
    public static final int MAX_RETRY = 2;

    private String sessionId;
    private String domainCode;
    private String intentCode;

    /** 当前等待解析的槽位名 */
    private String pendingSlot;

    /** DISCOVERED=展示候选项等待选择, MISSING=等待用户输入 */
    private String pendingType;

    /** DISCOVERED 时的候选项列表，每项包含 id 和 label */
    private List<Map<String, String>> candidates;

    /** 已解析完成的槽位值 Map */
    private Map<String, Object> resolvedSlots;

    /** 已重试次数，达到 MAX_RETRY 触发兜底转人工 */
    private int retryCount;

    /** @JsonIgnore 阻止 Jackson 将此方法序列化为 "discovered" 字段 */
    @JsonIgnore
    public boolean isDiscovered() { return "DISCOVERED".equals(pendingType); }

    /** @JsonIgnore 阻止 Jackson 将此方法序列化为 "missing" 字段 */
    @JsonIgnore
    public boolean isMissing() { return "MISSING".equals(pendingType); }

    public boolean shouldGiveUp() { return retryCount >= MAX_RETRY; }

    /** 返回 retryCount +1 的新实例（不可变语义） */
    public PendingSlotState withIncrementedRetry() {
        return new PendingSlotState(sessionId, domainCode, intentCode,
                pendingSlot, pendingType, candidates, resolvedSlots, retryCount + 1);
    }
}
