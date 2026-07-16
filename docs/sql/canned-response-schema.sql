-- canned-response schema (cs_conversation schema)
-- Append to conversation-service-schema.sql after creation

CREATE TABLE cs_conversation.cs_canned_response_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    parent_id   BIGINT       REFERENCES cs_conversation.cs_canned_response_group(id) ON DELETE SET NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE cs_conversation.cs_canned_response_group IS '快捷回复分组';

CREATE TABLE cs_conversation.cs_canned_response (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT       REFERENCES cs_conversation.cs_canned_response_group(id) ON DELETE SET NULL,
    title       VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL,
    scope       VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC',
    owner_id    BIGINT,
    use_count   INT          NOT NULL DEFAULT 0,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
COMMENT ON TABLE  cs_conversation.cs_canned_response          IS '快捷回复模板';
COMMENT ON COLUMN cs_conversation.cs_canned_response.scope    IS 'PUBLIC=公共, PRIVATE=个人';
COMMENT ON COLUMN cs_conversation.cs_canned_response.use_count IS '使用次数，用于搜索排序';

-- GIN 全文检索索引（title + content）
CREATE INDEX idx_cr_fts ON cs_conversation.cs_canned_response
    USING GIN (to_tsvector('simple', title || ' ' || content))
    WHERE deleted = FALSE;

CREATE INDEX idx_cr_scope_owner ON cs_conversation.cs_canned_response(scope, owner_id)
    WHERE deleted = FALSE;
