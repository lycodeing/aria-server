package com.aria.knowledge.domain.model;

import com.aria.common.core.exception.BusinessException;

import java.util.Set;

/**
 * 文档状态枚举（状态模式）。
 *
 * <p>每个枚举值内建合法流转目标，通过 {@link #transitionTo} 统一校验，
 * 取代各业务类中散落的 if-else 状态判断，集中维护状态机规则。
 *
 * <p>合法流转关系：
 * <pre>
 *   DRAFT ──► REVIEW ──► PUBLISHED ──► DEPRECATED
 *     ▲            │
 *     └────────────┘ (审核退回)
 *     └──► PUBLISHED  (无审核，Pipeline 直接发布)
 *     └──► FAILED ──► DRAFT (失败后可重新摄取)
 * </pre>
 */
public enum DocStatus {

    /** 草稿：文档已上传，等待摄取或审核 */
    DRAFT {
        @Override
        public Set<DocStatus> allowedTransitions() {
            return Set.of(REVIEW, PUBLISHED, FAILED);
        }
    },

    /** 审核中：提交人工审核后的中间状态 */
    REVIEW {
        @Override
        public Set<DocStatus> allowedTransitions() {
            return Set.of(PUBLISHED, DRAFT);
        }
    },

    /** 已发布：chunk 已进入向量库，可被检索 */
    PUBLISHED {
        @Override
        public Set<DocStatus> allowedTransitions() {
            return Set.of(DEPRECATED);
        }
    },

    /** 已下线：chunk 已物理删除，终态 */
    DEPRECATED {
        @Override
        public Set<DocStatus> allowedTransitions() {
            return Set.of();
        }
    },

    /** 摄取失败：可重新上传触发再次摄取 */
    FAILED {
        @Override
        public Set<DocStatus> allowedTransitions() {
            return Set.of(DRAFT);
        }
    };

    /**
     * 返回当前状态允许流转的目标状态集合。
     */
    public abstract Set<DocStatus> allowedTransitions();

    /**
     * 执行状态流转，非法流转时抛出业务异常。
     *
     * @param next 目标状态
     * @return 目标状态（便于链式赋值）
     * @throws BusinessException 错误码 5010，当前状态不允许流转到目标状态
     */
    public DocStatus transitionTo(DocStatus next) {
        if (!allowedTransitions().contains(next)) {
            throw new BusinessException(5010,
                "非法状态流转：" + this.name() + " → " + next.name()
                + "，允许流转至：" + allowedTransitions());
        }
        return next;
    }
}
