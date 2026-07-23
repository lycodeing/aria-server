package com.aria.conversation.interfaces.rest.vo;

import java.time.OffsetDateTime;

/**
 * 会话备注视图对象。
 *
 * <p>时间字段使用 {@link OffsetDateTime}，与 PostgreSQL TIMESTAMPTZ 列直接映射，
 * 避免时区信息丢失。
 *
 * @param id         备注 ID
 * @param content    备注内容
 * @param createdBy  创建人（座席 ID）
 * @param createTime 创建时间
 * @param updateTime 最后更新时间
 */
public record NoteVO(
        Long id,
        String content,
        String createdBy,
        OffsetDateTime createTime,
        OffsetDateTime updateTime
) {}
