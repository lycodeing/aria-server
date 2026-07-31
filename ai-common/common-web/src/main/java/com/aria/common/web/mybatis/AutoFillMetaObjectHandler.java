package com.aria.common.web.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;

import java.time.OffsetDateTime;

/**
 * 全局 MyBatis-Plus 自动填充处理器。
 *
 * <p>统一处理全库继承 {@code BaseDO}（或自行声明 createdAt/updatedAt 并标注
 * {@code @TableField(fill=...)}）的实体审计时间，业务代码无需再手动
 * {@code setCreatedAt} / {@code setUpdatedAt}。
 *
 * <p>时间类型全库统一为 {@link OffsetDateTime}，对应 PostgreSQL 的 {@code timestamptz}。
 */
@Slf4j
public class AutoFillMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        OffsetDateTime now = OffsetDateTime.now();
        // strict 填充：仅当字段声明了 INSERT 填充策略且当前值为 null 时才写入，
        // 从而保留业务显式设置的时间（如消息真实发生时间 msgTime）。
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // updatedAt 需在每次 updateById 时强制刷新：strictUpdateFill 对已加载实体的
        // 非空 updatedAt 不会覆盖，故直接 setValue 保证始终写入当前时间。
        // 无 updatedAt setter 的实体（仅 created_at）自动跳过。
        if (metaObject.hasSetter("updatedAt")) {
            metaObject.setValue("updatedAt", OffsetDateTime.now());
        }
    }
}
