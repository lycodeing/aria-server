package com.aria.conversation.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Map;

/** Map&lt;String, String&gt; → PostgreSQL jsonb 的 TypeHandler */
public class StringMapTypeHandler extends PgJsonbTypeHandler<Map<String, String>> {
    public StringMapTypeHandler() {
        super(new TypeReference<Map<String, String>>() {});
    }
}
