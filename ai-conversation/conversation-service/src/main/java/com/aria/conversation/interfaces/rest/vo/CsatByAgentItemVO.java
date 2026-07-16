package com.aria.conversation.interfaces.rest.vo;

import lombok.Data;

@Data
public class CsatByAgentItemVO {
    private Long agentId;
    private String agentName;
    private double avgScore;
    private long ratedCount;
}
