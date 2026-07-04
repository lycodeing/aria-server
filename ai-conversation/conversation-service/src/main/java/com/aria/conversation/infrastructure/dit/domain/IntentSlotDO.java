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
@TableName(value = "cs_conversation.cs_intent_slot", autoResultMap = true)
public class IntentSlotDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long intentId;
    private String slotName;

    /** string / number / date / enum */
    private String slotType;

    private String description;
    private Boolean required;

    /** 解析策略优先级，JSON 数组，如 ["EXTRACT","SESSION","DISCOVER","ASK_USER"] */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resolveStrategy;

    /** SESSION 级：从会话上下文取的 key */
    private String sessionKey;

    /** DISCOVER 级：调用的发现工具 code */
    private String discoverToolCode;

    /** DISCOVER 工具的额外固定参数，JSON */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String discoverFixedParams;

    /** ASK_USER 级：询问话术 */
    private String askUserPrompt;

    /** enum 类型时的可选值，JSON 数组 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String enumValues;

    private Integer sortOrder;
}
