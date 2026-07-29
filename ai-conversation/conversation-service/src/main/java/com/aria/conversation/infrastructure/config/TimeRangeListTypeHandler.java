package com.aria.conversation.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;

import java.util.List;

/** List&lt;TimeRange&gt; → PostgreSQL jsonb 的 TypeHandler */
public class TimeRangeListTypeHandler extends PgJsonbTypeHandler<List<BusinessHoursScheduleEntity.TimeRange>> {
    public TimeRangeListTypeHandler() {
        super(new TypeReference<List<BusinessHoursScheduleEntity.TimeRange>>() {});
    }
}
