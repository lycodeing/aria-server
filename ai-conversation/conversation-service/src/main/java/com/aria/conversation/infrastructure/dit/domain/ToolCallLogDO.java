package com.aria.conversation.infrastructure.dit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.conversation.infrastructure.persistence.typehandler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName(value = "cs_tool_call_log", autoResultMap = true)
public class ToolCallLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String toolCode;
    private String intentCode;
    private String domainCode;

    /** 实际发送的参数（脱敏，不含 token/password） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String params;

    /** HTTP 原始响应摘要（截断至 2000 字符） */
    private String response;

    /** SUCCESS / ERROR / TIMEOUT / SKIPPED */
    private String status;

    private Integer httpStatus;
    private Integer durationMs;
    private String errorMsg;
    private LocalDateTime createdAt;
}
