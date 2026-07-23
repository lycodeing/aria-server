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
 * 标签实体（对应 cs_conversation.cs_tag 表）。
 *
 * <p>source 枚举值：PRESET（预置标签）| CUSTOM（自定义标签）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(schema = "cs_conversation", value = "cs_tag")
public class TagEntity {

    /** 主键（BIGSERIAL 自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签名称（唯一） */
    private String name;

    /** 标签颜色（十六进制色值，如 #FF5733） */
    private String color;

    /** 来源：PRESET | CUSTOM */
    private String source;

    /** 使用次数（原子更新，禁止直接 set） */
    private Integer usageCount;

    /** 创建人（座席 ID 或 "system"） */
    private String createdBy;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
