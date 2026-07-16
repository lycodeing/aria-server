package com.aria.conversation.interfaces.rest.vo;

import lombok.Data;

@Data
public class CsatDistributionItemVO {
    private short score;       // 1~5
    private long count;
    private double percentage;
}
