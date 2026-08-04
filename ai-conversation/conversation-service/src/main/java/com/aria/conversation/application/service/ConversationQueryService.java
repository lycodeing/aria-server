package com.aria.conversation.application.service;

import com.aria.common.core.page.PageResult;
import com.aria.conversation.application.query.ConversationPageQuery;
import com.aria.conversation.infrastructure.persistence.ConversationPersistRepository;
import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.ConversationMessageEntity;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import com.aria.conversation.interfaces.assembler.SessionAssembler;
import com.aria.conversation.interfaces.rest.vo.SessionMessageVO;
import com.aria.conversation.interfaces.rest.vo.SessionRecordVO;
import com.aria.sdk.auth.AuthClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话查询应用服务（座席工作台「会话查询」页面）。
 *
 * <p>承载会话查询的用例编排：分页查询 → 批量补消息数 → Assembler 组装 VO。
 * 客服显示名（agentName）已在会话接入/转交的写路径快照进 {@code cs_conversation.agent_name}，
 * 查询侧直接读快照，无需在读热路径实时跨服务解析（历史真相不可变 + 免 N+1）。
 *
 * <p>读侧 CQRS：本服务直接消费读模型（{@link ConversationEntity}），
 * 不经过领域聚合根，符合查询侧简化原则；跨服务调用（客服下拉）也收敛在此，
 * 使 {@code ConversationQueryController} 回归「参数接收 + 响应组装」单一职责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationQueryService {

    /** 详情抽屉消息展示上限，防止超长会话（大量 tool 消息）一次性灌入内存与前端 */
    private static final int MAX_DETAIL_MESSAGES = 500;

    private final ConversationPersistRepository persistRepository;
    private final ConversationMessageMapper messageMapper;
    private final AuthClient authClient;

    /**
     * 分页查询会话记录。
     *
     * @param query 查询参数（非法枚举/日期由 repository 校验并抛 400）
     * @return 分页 VO 结果
     */
    public PageResult<SessionRecordVO> query(ConversationPageQuery query) {
        PageResult<ConversationEntity> result = persistRepository.search(query);

        List<String> sessionIds = result.items().stream()
                .map(ConversationEntity::getSessionId)
                .toList();
        Map<String, Long> msgCounts = sessionIds.isEmpty()
                ? Map.of()
                : messageMapper.countBySessionIds(sessionIds);

        List<SessionRecordVO> voList = result.items().stream()
                .map(e -> SessionAssembler.toRecordVO(
                        e, msgCounts.getOrDefault(e.getSessionId(), 0L).intValue()))
                .toList();

        return PageResult.of(result.total(), result.page(), result.size(), voList);
    }

    /**
     * 查询会话消息记录（从 DB 读取，不依赖 Redis）。
     *
     * <p>取最近 {@link #MAX_DETAIL_MESSAGES} 条后按时间升序返回，兼顾长会话性能与阅读顺序。
     *
     * @param sessionId 会话唯一标识
     */
    public List<SessionMessageVO> messages(String sessionId) {
        List<ConversationMessageEntity> entities = messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageEntity.class)
                        .eq(ConversationMessageEntity::getSessionId, sessionId)
                        .orderByDesc(ConversationMessageEntity::getId)
                        .last("LIMIT " + MAX_DETAIL_MESSAGES));
        // DB 取回为倒序（最近在前），反转为正序展示
        Collections.reverse(entities);
        return entities.stream()
                .map(SessionAssembler::toMessageVO)
                .toList();
    }

    /**
     * 搜索客服列表（供会话查询页面筛选下拉使用），通过 AuthClient 调用 auth-service 内部接口。
     *
     * @param keyword 搜索关键词（可选）
     * @param page    页码（0-based）
     * @param size    每页大小
     */
    public Map<String, Object> searchAgents(String keyword, int page, int size) {
        return authClient.searchUsers(keyword, page, size);
    }
}
