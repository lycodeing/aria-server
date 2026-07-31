package com.aria.common.core.mybatis;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 数据库对象统一基类：抽取所有表共有的审计时间字段。
 *
 * <p>约定：全库 {@code created_at} / {@code updated_at} 统一为 PostgreSQL 的
 * {@code timestamptz}，Java 侧统一映射为 {@link OffsetDateTime}，避免各服务
 * 混用 LocalDateTime / Instant 造成时区语义不一致。
 *
 * <p>两个字段均交由全局 MetaObjectHandler 自动填充，业务代码无需手动
 * {@code setCreatedAt} / {@code setUpdatedAt}：
 * <ul>
 *   <li>{@code createdAt}：插入时填充（仅当值为空，保留业务显式设置的时间）；</li>
 *   <li>{@code updatedAt}：插入与每次更新时强制刷新为当前时间。</li>
 * </ul>
 *
 * <p>仅同时拥有 created_at + updated_at 两列的表继承本类；只有 created_at 的表
 * 保持独立（避免映射出不存在的 updated_at 列）。
 */
@Getter
@Setter
public abstract class BaseDO {

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
