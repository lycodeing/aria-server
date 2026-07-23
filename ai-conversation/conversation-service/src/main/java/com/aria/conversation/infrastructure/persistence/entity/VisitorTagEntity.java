package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 访客-标签关联实体（对应 cs_conversation.cs_visitor_tag 表）。
 *
 * <p>复合主键表（visitor_id, tag_id），MyBatis-Plus 不原生支持复合 PK，
 * 两列均作普通 @TableField，insert() 正常写入两列。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_visitor_tag")
public class VisitorTagEntity {

    /** 访客唯一标识 */
    private String visitorId;

    /** 标签 ID（FK → cs_tag.id） */
    private Long tagId;

    /** 打标人（座席 ID 或 "system"） */
    private String taggedBy;

    private LocalDateTime createTime;
}
