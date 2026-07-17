package com.aria.conversation.application.service.support;

import com.aria.conversation.infrastructure.csat.CsatRatingDO;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CSAT 邀请事件载荷构建器（前后端契约的唯一来源）。
 *
 * <p>SSE {@code csat_request} 事件、WS {@code csat_request} 帧、以及
 * {@code GET /chat/csat/pending} REST 接口共用同一份 payload：{@code
 * {csatId, sessionId, message, expiresAt}}。任何字段（文案、时间格式）
 * 一旦分散，就会出现"刷新前后弹窗文案不一致"的用户可感知 bug。
 *
 * <p>本类是**唯一**允许构造该 payload 的地方，禁止业务代码内嵌
 * "请对本次服务进行评价" 或 {@link OffsetDateTime#toString()}。
 */
public final class CsatInvites {

    /**
     * 邀请弹窗展示文案。SSE 流末尾、人工关闭会话、REST pending 恢复
     * 三个路径的用户可见文案必须完全一致。
     */
    public static final String INVITE_MESSAGE = "请对本次服务进行评价";

    private CsatInvites() { /* util */ }

    /**
     * 从 CSAT 记录构造有序的 payload map（字段顺序稳定，便于 JSON 二进制比对）。
     *
     * @param csat 已入库的 CSAT 记录（id/sessionId/expiredAt 必须非空）
     * @return 顺序为 {@code csatId, sessionId, message, expiresAt} 的不可变风格 LinkedHashMap
     */
    public static Map<String, Object> payload(CsatRatingDO csat) {
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("csatId",    csat.getId());
        body.put("sessionId", csat.getSessionId());
        body.put("message",   INVITE_MESSAGE);
        body.put("expiresAt", formatExpiresAt(csat.getExpiredAt()));
        return body;
    }

    /**
     * expiresAt 序列化策略：直接使用 ISO-8601 offset 表示（{@code OffsetDateTime.toString()}），
     * 与前端 {@code CsatRequestPayload.expiresAt: string} 字段兼容。
     *
     * <p>抽出独立方法便于未来切换到 Instant/UTC 归一时统一改动。
     */
    public static String formatExpiresAt(OffsetDateTime expiredAt) {
        return expiredAt != null ? expiredAt.toString() : null;
    }
}
