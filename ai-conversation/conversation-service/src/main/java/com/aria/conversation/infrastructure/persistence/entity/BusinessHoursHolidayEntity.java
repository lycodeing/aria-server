package com.aria.conversation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@TableName(schema = "cs_conversation", value = "cs_business_hours_holiday",
           autoResultMap = true)
public class BusinessHoursHolidayEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate date;

    /** CLOSED | CUSTOM | WORKDAY */
    private String type;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<BusinessHoursScheduleEntity.TimeRange> timeRanges;

    private String remark;

    /** AUTO | MANUAL */
    private String source;

    private LocalDateTime createTime;
}
