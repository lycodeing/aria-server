package com.aria.conversation.infrastructure.csat;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName(schema = "cs_conversation", value = "cs_csat_rating")
public class CsatRatingDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String visitorId;
    private Long agentId;
    private Short score;
    private String comment;
    /** AI / HUMAN */
    private String channel;
    /** PENDING / RATED / EXPIRED / SKIPPED */
    private String status;
    private OffsetDateTime requestedAt;
    private OffsetDateTime ratedAt;
    private OffsetDateTime expiredAt;
}
