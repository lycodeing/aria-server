package com.aria.conversation.interfaces.rest.vo;

import lombok.Data;

@Data
public class CsatTrendItemVO {
    private String date;       // yyyy-MM-dd
    private double avgScore;
    private long ratedCount;
}
