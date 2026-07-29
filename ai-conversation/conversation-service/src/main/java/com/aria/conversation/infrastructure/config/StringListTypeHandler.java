package com.aria.conversation.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.aria.conversation.infrastructure.persistence.entity.BusinessHoursScheduleEntity;

import java.util.List;

/** List&lt;String&gt; → PostgreSQL jsonb 的 TypeHandler */
public class StringListTypeHandler extends PgJsonbTypeHandler<List<String>> {
    public StringListTypeHandler() {
        super(new TypeReference<List<String>>() {});
    }
}
