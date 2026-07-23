package com.aria.conversation.domain.service;

import com.aria.conversation.infrastructure.persistence.entity.ConversationEntity;
import com.aria.conversation.infrastructure.persistence.entity.SlaPolicyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SLA 策略匹配器（domain service）。
 *
 * <p>按优先级从高到低遍历所有已启用策略，返回第一个同时满足以下两个条件的策略：
 * <ol>
 *   <li>访客标签匹配：{@code matchVisitorTags} 为空，或访客至少有一个标签在列表中</li>
 *   <li>转接标签匹配：{@code matchTransferTags} 为空，或 {@code session.tag} 在列表中</li>
 * </ol>
 *
 * <p>两个条件同时满足（AND 逻辑）才命中。返回 {@code null} 表示该会话不受任何 SLA 策略监控。
 *
 * <p>通过 {@link SlaPolicyRepository} 接口获取策略列表（依赖倒置），
 * 不直接依赖 infrastructure 层的具体缓存实现。
 */
@Component
@RequiredArgsConstructor
public class SlaPolicyMatcher {

    private final SlaPolicyRepository slaPolicyRepository;

    /**
     * 按优先级匹配会话对应的 SLA 策略。
     *
     * @param session         当前会话实体
     * @param visitorTagNames 访客标签名称列表（从 cs_visitor_tag 实时查询）
     * @return 第一个命中的策略，{@code null} 表示不受 SLA 监控
     */
    public SlaPolicyEntity findPolicy(ConversationEntity session, List<String> visitorTagNames) {
        List<SlaPolicyEntity> policies = slaPolicyRepository.findAllEnabled();
        for (SlaPolicyEntity policy : policies) {
            if (matchesTags(policy, session, visitorTagNames)) {
                return policy;
            }
        }
        return null;
    }

    /**
     * 判断单个策略是否与当前会话匹配。
     *
     * @param policy          SLA 策略
     * @param session         当前会话
     * @param visitorTagNames 访客标签名称列表
     * @return true 表示命中
     */
    private boolean matchesTags(SlaPolicyEntity policy,
                                 ConversationEntity session,
                                 List<String> visitorTagNames) {
        List<String> mvt = policy.getMatchVisitorTags();
        List<String> mtt = policy.getMatchTransferTags();

        // 访客标签条件：策略无限制（空/null），或访客标签中至少有一个在策略列表中
        boolean visitorMatch = mvt == null || mvt.isEmpty()
                || (visitorTagNames != null && visitorTagNames.stream().anyMatch(mvt::contains));

        // 转接标签条件：策略无限制（空/null），或 session.tag 在策略列表中
        boolean transferMatch = mtt == null || mtt.isEmpty()
                || (session.getTag() != null && mtt.contains(session.getTag()));

        return visitorMatch && transferMatch;
    }
}

