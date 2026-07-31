package com.aria.conversation.infrastructure.canned;

import com.aria.common.core.mybatis.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.aria.conversation.domain.CannedResponseScope;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(schema = "cs_conversation", value = "cs_canned_response")
public class CannedResponseDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private String title;
    private String content;
    /** PUBLIC / PRIVATE */
    private CannedResponseScope scope;
    /** PRIVATE 时的所属坐席 ID */
    private Long ownerId;
    private Integer useCount;
    private Integer sortOrder;
    private Long createdBy;
    /**
     * 软删除标记。PG 列类型为 boolean，且本模块所有查询/更新均显式处理 deleted 条件，
     * 不使用 @TableLogic（其默认 0/1 整型值会拼出 deleted=0，PG 报 boolean=integer 类型错误）。
     */
    private Boolean deleted;
}
