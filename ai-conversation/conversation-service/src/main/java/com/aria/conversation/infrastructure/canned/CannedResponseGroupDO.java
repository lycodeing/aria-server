package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName(schema = "cs_conversation", value = "cs_canned_response_group")
public class CannedResponseGroupDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long parentId;
    private Integer sortOrder;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private Boolean deleted;
}
