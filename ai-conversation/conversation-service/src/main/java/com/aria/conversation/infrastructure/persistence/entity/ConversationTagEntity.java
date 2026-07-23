package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话-标签关联实体（对应 cs_conversation.cs_conversation_tag 表）。
 *
 * <p>复合主键表（session_id, tag_id），两列均作普通字段，MyBatis-Plus
 * insert() 正常写入两列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_conversation_tag")
public class ConversationTagEntity {

    /** 会话唯一标识（FK → cs_conversation.session_id） */
    private String sessionId;

    /** 标签 ID（FK → cs_tag.id） */
    private Long tagId;

    /** 打标人（座席 ID 或 "system"） */
    private String taggedBy;

    private LocalDateTime createTime;
}
