package com.aria.conversation.infrastructure.csat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aria.conversation.domain.CsatChannel;
import com.aria.conversation.domain.CsatStatus;
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
    private CsatChannel channel;
    /** PENDING / RATED / EXPIRED / SKIPPED */
    private CsatStatus status;
    private OffsetDateTime requestedAt;
    private OffsetDateTime ratedAt;
    private OffsetDateTime expiredAt;
}
