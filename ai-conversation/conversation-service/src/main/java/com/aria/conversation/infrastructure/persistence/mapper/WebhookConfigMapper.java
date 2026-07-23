package com.aria.conversation.infrastructure.persistence.mapper;

import com.aria.conversation.infrastructure.persistence.entity.WebhookConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * Webhook 通知配置 Mapper。
 *
 * <p>单表查询通过 LambdaWrapper 完成；{@link #selectEnabledByIds} 过滤禁用配置，
 * 避免向已下线的 Webhook 地址发送通知。
 */
@Mapper
public interface WebhookConfigMapper extends BaseMapper<WebhookConfigEntity> {

    /**
     * 按 ID 列表批量查询，只返回已启用的配置（is_enabled = 1）。
     *
     * @param ids Webhook 配置 ID 列表
     * @return 启用状态的配置列表；ids 为空时直接返回空列表
     */
    default List<WebhookConfigEntity> selectEnabledByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return selectList(Wrappers.<WebhookConfigEntity>lambdaQuery()
                .in(WebhookConfigEntity::getId, ids)
                .eq(WebhookConfigEntity::getIsEnabled, 1));
    }
}
