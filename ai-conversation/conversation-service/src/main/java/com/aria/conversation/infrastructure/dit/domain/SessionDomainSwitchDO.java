package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("cs_session_domain_switch")
public class SessionDomainSwitchDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    /** 切换前的域 code，初始进入时为 null */
    private String fromDomain;
    /** 切换后的域 code */
    private String toDomain;
    /** 切换类型，见 {@link SwitchType} */
    private String switchType;
    /** 触发切换的用户消息原文 */
    private String triggerMessage;
    /** 切换原因 */
    private String reason;
    /** 关联 cs_conversation_message.seq */
    private Long msgSeq;
    private OffsetDateTime createdAt;
}
