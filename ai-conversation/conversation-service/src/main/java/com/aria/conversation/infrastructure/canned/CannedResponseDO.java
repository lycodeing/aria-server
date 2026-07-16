package com.aria.conversation.infrastructure.canned;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName(schema = "cs_conversation", value = "cs_canned_response")
public class CannedResponseDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private String title;
    private String content;
    /** PUBLIC / PRIVATE */
    private String scope;
    /** PRIVATE 时的所属坐席 ID */
    private Long ownerId;
    private Integer useCount;
    private Integer sortOrder;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Boolean deleted;
}
