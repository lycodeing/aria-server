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
@TableName(value = "cs_conversation.cs_intent_tool", autoResultMap = true)
public class IntentToolDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long intentId;
    private Long toolId;

    /** REQUIRED=系统立即执行, OPTIONAL=交给 LLM 决策 */
    private String executionMode;

    /** REQUIRED 工具的串行执行顺序 */
    private Integer executionOrder;

    /**
     * 参数来源映射，JSON。
     * 格式：{"order_id":{"source":"slot","key":"order_id"}}
     * source 取值：slot / session / literal
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String paramMappings;
}
