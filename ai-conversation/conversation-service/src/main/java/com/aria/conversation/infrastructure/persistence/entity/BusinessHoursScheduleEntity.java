package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(schema = "cs_conversation", value = "cs_business_hours_schedule",
           autoResultMap = true)
public class BusinessHoursScheduleEntity {

    @TableId
    private Integer dayOfWeek;   // 1=周一 … 7=周日

    private Boolean isOpen;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<TimeRange> timeRanges;

    private String timezone;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Data
    public static class TimeRange {
        private String start;  // "09:00"
        private String end;    // "18:00"
    }
}
