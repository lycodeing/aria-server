package com.aria.conversation.infrastructure.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.aria.conversation.domain.model.SlaBreachActions;

/** SlaBreachActions → PostgreSQL jsonb 的 TypeHandler */
public class SlaBreachActionsTypeHandler extends PgJsonbTypeHandler<SlaBreachActions> {
    public SlaBreachActionsTypeHandler() {
        super(new TypeReference<SlaBreachActions>() {});
    }
}
