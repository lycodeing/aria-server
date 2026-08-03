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

    /**
     * 查询订阅了指定事件范围且启用的 Webhook 配置（按 id 升序）。
     * 使用 jsonb 数组包含操作符 {@code @>}，通过 MyBatis-Plus apply 参数化注入。
     *
     * <p>使用 apply 而非 @Select 手写 SQL，原因：
     * <ol>
     *   <li>保持 MyBatis-Plus 自动 ResultMap，确保 jsonb 列（scopes/customHeaders）通过 StringListTypeHandler/StringMapTypeHandler 正确映射</li>
     *   <li>避免 @Select 全量 SELECT * 不走 LambdaQueryWrapper 的自动列映射</li>
     * </ol>
     *
     * @param scope WebhookScope 枚举名，如 "SLA_BREACH"
     * @return 匹配的启用配置列表，无则返回空列表
     */
    default List<WebhookConfigEntity> selectEnabledByScope(String scope) {
        return selectList(Wrappers.<WebhookConfigEntity>lambdaQuery()
                .eq(WebhookConfigEntity::getIsEnabled, 1)
                .apply("scopes @> ('[\"' || {0} || '\"]')::jsonb", scope)
                .orderByAsc(WebhookConfigEntity::getId));
    }
}
