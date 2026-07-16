CREATE TABLE cs_conversation.cs_csat_rating (
    id           BIGSERIAL PRIMARY KEY,
    session_id   VARCHAR(64)  NOT NULL,
    visitor_id   VARCHAR(64),
    agent_id     BIGINT,
    score        SMALLINT     CHECK (score BETWEEN 1 AND 5),
    comment      TEXT,
    channel      VARCHAR(20)  NOT NULL DEFAULT 'AI',
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    rated_at     TIMESTAMPTZ,
    expired_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_csat_session UNIQUE (session_id)
);
CREATE INDEX idx_csat_status_expired ON cs_conversation.cs_csat_rating (status, expired_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_csat_agent_rated ON cs_conversation.cs_csat_rating (agent_id, rated_at)
    WHERE agent_id IS NOT NULL;
COMMENT ON TABLE  cs_conversation.cs_csat_rating         IS '会话满意度评价';
COMMENT ON COLUMN cs_conversation.cs_csat_rating.channel IS 'AI=AI对话, HUMAN=人工接待';
COMMENT ON COLUMN cs_conversation.cs_csat_rating.status  IS 'PENDING/RATED/EXPIRED/SKIPPED';
