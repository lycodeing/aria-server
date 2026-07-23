package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话备注实体（对应 cs_conversation.cs_conversation_note 表）。
 *
 * <p>备注由座席手动添加，每条会话可有多条备注，按 create_time 升序展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_conversation_note")
public class ConversationNoteEntity {

    /** 主键（BIGSERIAL 自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话唯一标识（FK → cs_conversation.session_id） */
    private String sessionId;

    /** 备注内容 */
    private String content;

    /** 创建人（座席 ID） */
    private String createdBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
