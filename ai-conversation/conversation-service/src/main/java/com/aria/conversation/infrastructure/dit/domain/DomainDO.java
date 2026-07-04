package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("cs_conversation.cs_domain")
public class DomainDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 前端传入的领域标识，如 "ecommerce" */
    private String code;
    private String name;
    private String description;

    /** 追加到 system prompt 的领域专属说明 */
    private String systemPromptAddon;

    /** 领域专属知识库 ID，null 时使用全局知识库 */
    private Long knowledgeBaseId;

    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
