package com.aria.conversation.infrastructure.feedback;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 坐席反馈持久化。
 *
 * <p>直接持有 {@link SessionFeedbackMapper}（不继承 ServiceImpl），
 * 保持普通组件 + 构造注入，便于测试与依赖清晰。
 */
@Repository
@RequiredArgsConstructor
public class SessionFeedbackRepository {

    private final SessionFeedbackMapper mapper;

    /** 保存反馈记录，回填自增主键到入参实体。 */
    public void save(SessionFeedbackEntity entity) {
        mapper.insert(entity);
    }

    /** 标记反馈记录已成功积累到 example_vectors。 */
    public void markAccumulated(Long id) {
        mapper.update(null, Wrappers.<SessionFeedbackEntity>lambdaUpdate()
                .set(SessionFeedbackEntity::getAccumulated, true)
                .eq(SessionFeedbackEntity::getId, id));
    }
}
