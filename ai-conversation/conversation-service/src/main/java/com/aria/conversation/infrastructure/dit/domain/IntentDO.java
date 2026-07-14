package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.conversation.infrastructure.persistence.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "cs_intent", autoResultMap = true)
public class IntentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long domainId;
    private String code;
    private String name;
    private String description;

    /** 少样本示例，JSON 数组字符串，如 ["查订单","我的包裹到哪了"] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String exampleQueries;

    /** true=命中后自动转人工 */
    private Boolean autoTransfer;

    /** true=跳过 RAG 检索 */
    private Boolean skipRag;

    /** 工具失败时的兜底回复 */
    private String fallbackReply;

    private Boolean enabled;
    private Integer sortOrder;

    /** 关键词列表，JSON 数组，大小写不敏感包含匹配 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String keywords;

    /** 正则表达式列表，JSON 数组，Java Pattern 语法 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String patterns;
}
