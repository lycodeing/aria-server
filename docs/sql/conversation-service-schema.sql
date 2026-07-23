-- conversation-service (cs_conversation schema)
--
-- PostgreSQL database dump
--

-- Dumped from database version 16.14 (Debian 16.14-1.pgdg12+1)
-- Dumped by pg_dump version 16.14 (Debian 16.14-1.pgdg12+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;



--
-- Name: cs_conversation; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA cs_conversation;

--
-- Name: set_updated_at(); Type: FUNCTION; Schema: cs_conversation; Owner: -
--

CREATE FUNCTION cs_conversation.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: cs_conversation; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_conversation (
    id bigint NOT NULL,
    session_id character varying(100) NOT NULL,
    visitor_name character varying(100) DEFAULT '访客'::character varying NOT NULL,
    transfer_reason text,
    tag character varying(50),
    status character varying(20) DEFAULT 'WAITING'::character varying NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    ended_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    agent_id character varying(100) DEFAULT NULL::character varying,
    accepted_at timestamp with time zone,
    first_reply_at timestamp with time zone,
    closed_by character varying(20),
    visitor_id      character varying(64)  DEFAULT NULL,
    visitor_ip      character varying(45)  DEFAULT NULL,
    visitor_device  character varying(500) DEFAULT NULL
);

--
-- Name: TABLE cs_conversation; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_conversation IS '客服会话生命周期记录表';

--
-- Name: COLUMN cs_conversation.session_id; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation.session_id IS '前端唯一会话 ID，与 Redis chat:session:{id} 对应';

--
-- Name: COLUMN cs_conversation.transfer_reason; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation.transfer_reason IS '转接人工的原因描述';

--
-- Name: COLUMN cs_conversation.status; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation.status IS '会话状态：WAITING=等待接入, ACTIVE=接待中, CLOSED=已结束';

--
-- Name: COLUMN cs_conversation.agent_id; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation.agent_id IS '接入座席 ID，WAITING 时为 NULL，ACTIVE 后填入';

--
-- Name: cs_conversation_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_conversation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_conversation_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_conversation_id_seq OWNED BY cs_conversation.cs_conversation.id;

--
-- Name: cs_conversation_message; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_conversation_message (
    id bigint NOT NULL,
    session_id character varying(100) NOT NULL,
    role character varying(20) NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    seq bigint,
    tool_calls_json text,
    tool_request_id character varying(128),
    tool_name character varying(128)
);

--
-- Name: TABLE cs_conversation_message; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_conversation_message IS '对话消息明细表，由 Redis Stream 异步写入';

--
-- Name: COLUMN cs_conversation_message.session_id; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.session_id IS '冗余字段，避免查询历史时 JOIN cs_conversation';

--
-- Name: COLUMN cs_conversation_message.role; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.role IS '消息角色：user=访客, assistant=AI, agent=人工座席';

--
-- Name: COLUMN cs_conversation_message.seq; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.seq IS 'session 内单调递增序号，支持断线重连增量拉取；历史消息为 NULL';

--
-- Name: COLUMN cs_conversation_message.tool_calls_json; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_calls_json IS 'assistant 触发的 tool_calls JSON 数组：[{"id":"...","name":"...","arguments":"..."}]。仅 role=assistant 且模型返回 tool_calls 时非空';

--
-- Name: COLUMN cs_conversation_message.tool_request_id; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_request_id IS 'LangChain4j ToolExecutionRequest ID，role=tool 时填充，用于关联工具调用上下文';

--
-- Name: COLUMN cs_conversation_message.tool_name; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_name IS '工具名称，role=tool 时填充';

--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_conversation_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_conversation_message_id_seq OWNED BY cs_conversation.cs_conversation_message.id;

--
-- Name: cs_domain; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_domain (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    description text,
    system_prompt_addon text,
    knowledge_base_id bigint,
    keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    patterns jsonb DEFAULT '[]'::jsonb NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE cs_domain; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_domain IS '领域/场景配置表';

--
-- Name: COLUMN cs_domain.code; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_domain.code IS '前端传入的领域标识，如 ecommerce';

--
-- Name: COLUMN cs_domain.system_prompt_addon; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_domain.system_prompt_addon IS '追加到 system prompt 的领域专属说明';

--
-- Name: cs_domain_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_domain_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_domain_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_domain_id_seq OWNED BY cs_conversation.cs_domain.id;

--
-- Name: cs_intent; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_intent (
    id bigint NOT NULL,
    domain_id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    description text NOT NULL,
    example_queries jsonb DEFAULT '[]'::jsonb NOT NULL,
    auto_transfer boolean DEFAULT false NOT NULL,
    skip_rag boolean DEFAULT false NOT NULL,
    fallback_reply text,
    enabled boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    patterns jsonb DEFAULT '[]'::jsonb NOT NULL
);

--
-- Name: TABLE cs_intent; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_intent IS '意图定义表';

--
-- Name: COLUMN cs_intent.example_queries; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent.example_queries IS '少样本示例，JSON 数组，如 ["查订单","我的包裹到哪了"]';

--
-- Name: COLUMN cs_intent.auto_transfer; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent.auto_transfer IS 'true=命中后自动转人工（投诉/敏感操作）';

--
-- Name: COLUMN cs_intent.skip_rag; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent.skip_rag IS 'true=跳过 RAG 检索';

--
-- Name: cs_intent_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_intent_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_intent_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_intent_id_seq OWNED BY cs_conversation.cs_intent.id;

--
-- Name: cs_intent_slot; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_intent_slot (
    id bigint NOT NULL,
    intent_id bigint NOT NULL,
    slot_name character varying(64) NOT NULL,
    slot_type character varying(32) DEFAULT 'string'::character varying NOT NULL,
    description character varying(256) NOT NULL,
    required boolean DEFAULT false NOT NULL,
    resolve_strategy jsonb DEFAULT '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]'::jsonb NOT NULL,
    session_key character varying(64),
    discover_tool_code character varying(64),
    discover_fixed_params jsonb DEFAULT '{}'::jsonb,
    ask_user_prompt character varying(256),
    enum_values jsonb,
    sort_order integer DEFAULT 0 NOT NULL
);

--
-- Name: COLUMN cs_intent_slot.resolve_strategy; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent_slot.resolve_strategy IS '解析策略优先级，JSON 数组，按顺序尝试';

--
-- Name: COLUMN cs_intent_slot.discover_tool_code; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent_slot.discover_tool_code IS 'DISCOVER 级使用的发现工具 code';

--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_intent_slot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_intent_slot_id_seq OWNED BY cs_conversation.cs_intent_slot.id;

--
-- Name: cs_intent_tool; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_intent_tool (
    id bigint NOT NULL,
    intent_id bigint NOT NULL,
    tool_id bigint NOT NULL,
    execution_mode character varying(16) DEFAULT 'OPTIONAL'::character varying NOT NULL,
    execution_order integer DEFAULT 0 NOT NULL,
    param_mappings jsonb DEFAULT '{}'::jsonb NOT NULL
);

--
-- Name: COLUMN cs_intent_tool.execution_mode; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent_tool.execution_mode IS 'REQUIRED=系统立即执行, OPTIONAL=交给 LLM 决策';

--
-- Name: COLUMN cs_intent_tool.param_mappings; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_intent_tool.param_mappings IS '参数来源映射，JSON，key=工具参数名，value={source,key}';

--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_intent_tool_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_intent_tool_id_seq OWNED BY cs_conversation.cs_intent_tool.id;

--
-- Name: cs_pending_slot; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_pending_slot (
    session_id character varying(64) NOT NULL,
    domain_code character varying(64) NOT NULL,
    intent_code character varying(64) NOT NULL,
    pending_slot character varying(64) NOT NULL,
    pending_type character varying(16) NOT NULL,
    candidates jsonb,
    resolved_slots jsonb DEFAULT '{}'::jsonb NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE cs_pending_slot; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_pending_slot IS '槽位解析挂起状态，用于多轮对话中间状态恢复';

--
-- Name: COLUMN cs_pending_slot.pending_type; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_pending_slot.pending_type IS 'DISCOVERED=候选项待选, MISSING=等待用户输入';

--
-- Name: cs_session_domain_switch; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_session_domain_switch (
    id bigint NOT NULL,
    session_id character varying(100) NOT NULL,
    from_domain character varying(64),
    to_domain character varying(64) NOT NULL,
    switch_type character varying(32) NOT NULL,
    trigger_message text,
    reason text,
    msg_seq bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE cs_session_domain_switch; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_session_domain_switch IS '会话域切换记录表';

--
-- Name: COLUMN cs_session_domain_switch.session_id; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.session_id IS '会话 ID';

--
-- Name: COLUMN cs_session_domain_switch.from_domain; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.from_domain IS '切换前的域 code，首次进入时为 NULL';

--
-- Name: COLUMN cs_session_domain_switch.to_domain; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.to_domain IS '切换后的域 code';

--
-- Name: COLUMN cs_session_domain_switch.switch_type; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.switch_type IS 'INITIAL / ROUTER_MODEL / LLM_TOOL / USER_SELECTED';

--
-- Name: COLUMN cs_session_domain_switch.trigger_message; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.trigger_message IS '触发切换的用户消息原文';

--
-- Name: COLUMN cs_session_domain_switch.reason; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.reason IS '切换原因描述';

--
-- Name: COLUMN cs_session_domain_switch.msg_seq; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.msg_seq IS '关联 cs_conversation_message.seq';

--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_session_domain_switch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_session_domain_switch_id_seq OWNED BY cs_conversation.cs_session_domain_switch.id;

--
-- Name: cs_tool; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_tool (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    description text NOT NULL,
    tool_type character varying(32) DEFAULT 'HTTP'::character varying NOT NULL,
    http_method character varying(16),
    url_template character varying(512),
    headers_template jsonb DEFAULT '{}'::jsonb,
    body_template jsonb,
    param_schema jsonb DEFAULT '{}'::jsonb NOT NULL,
    response_jsonpath character varying(256),
    auth_type character varying(32) DEFAULT 'NONE'::character varying NOT NULL,
    auth_config jsonb DEFAULT '{}'::jsonb,
    timeout_ms integer DEFAULT 5000 NOT NULL,
    is_discover_tool boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE cs_tool; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_tool IS '工具注册表（HTTP 调用或内置 Java 实现）';

--
-- Name: COLUMN cs_tool.tool_type; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_tool.tool_type IS 'HTTP=通用 HTTP 调用, BUILTIN=Java 内置实现';

--
-- Name: COLUMN cs_tool.url_template; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_tool.url_template IS 'URL 模板，支持 {slot_name} 路径参数';

--
-- Name: COLUMN cs_tool.param_schema; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_tool.param_schema IS '参数 JSON Schema，供 LLM Function Calling 使用';

--
-- Name: COLUMN cs_tool.is_discover_tool; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_tool.is_discover_tool IS 'true=可作为槽位 DISCOVER 级发现工具';

--
-- Name: cs_tool_call_log; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.cs_tool_call_log (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    tool_code character varying(64) NOT NULL,
    intent_code character varying(64),
    domain_code character varying(64),
    params jsonb,
    response text,
    status character varying(16) NOT NULL,
    http_status integer,
    duration_ms integer,
    error_msg text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);

--
-- Name: TABLE cs_tool_call_log; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON TABLE cs_conversation.cs_tool_call_log IS '工具调用日志（调试+监控）';

--
-- Name: COLUMN cs_tool_call_log.status; Type: COMMENT; Schema: cs_conversation; Owner: -
--

COMMENT ON COLUMN cs_conversation.cs_tool_call_log.status IS 'SUCCESS/ERROR/TIMEOUT/SKIPPED';

--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_tool_call_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_tool_call_log_id_seq OWNED BY cs_conversation.cs_tool_call_log.id;

--
-- Name: cs_tool_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: -
--

CREATE SEQUENCE cs_conversation.cs_tool_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: cs_tool_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: -
--

ALTER SEQUENCE cs_conversation.cs_tool_id_seq OWNED BY cs_conversation.cs_tool.id;

--
-- Name: flyway_schema_history; Type: TABLE; Schema: cs_conversation; Owner: -
--

CREATE TABLE cs_conversation.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);

--
-- Name: cs_conversation id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_conversation ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_conversation_id_seq'::regclass);

--
-- Name: cs_conversation_message id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_conversation_message ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_conversation_message_id_seq'::regclass);

--
-- Name: cs_domain id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_domain ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_domain_id_seq'::regclass);

--
-- Name: cs_intent id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_id_seq'::regclass);

--
-- Name: cs_intent_slot id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_slot_id_seq'::regclass);

--
-- Name: cs_intent_tool id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_tool_id_seq'::regclass);

--
-- Name: cs_session_domain_switch id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_session_domain_switch ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_session_domain_switch_id_seq'::regclass);

--
-- Name: cs_tool id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_tool ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_tool_id_seq'::regclass);

--
-- Name: cs_tool_call_log id; Type: DEFAULT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_tool_call_log ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_tool_call_log_id_seq'::regclass);

--
-- Name: cs_conversation_message cs_conversation_message_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_conversation_message
    ADD CONSTRAINT cs_conversation_message_pkey PRIMARY KEY (id);

--
-- Name: cs_conversation cs_conversation_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_conversation
    ADD CONSTRAINT cs_conversation_pkey PRIMARY KEY (id);

--
-- Name: cs_domain cs_domain_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_domain
    ADD CONSTRAINT cs_domain_pkey PRIMARY KEY (id);

--
-- Name: cs_intent cs_intent_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent
    ADD CONSTRAINT cs_intent_pkey PRIMARY KEY (id);

--
-- Name: cs_intent_slot cs_intent_slot_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot
    ADD CONSTRAINT cs_intent_slot_pkey PRIMARY KEY (id);

--
-- Name: cs_intent_tool cs_intent_tool_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT cs_intent_tool_pkey PRIMARY KEY (id);

--
-- Name: cs_pending_slot cs_pending_slot_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_pending_slot
    ADD CONSTRAINT cs_pending_slot_pkey PRIMARY KEY (session_id);

--
-- Name: cs_session_domain_switch cs_session_domain_switch_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_session_domain_switch
    ADD CONSTRAINT cs_session_domain_switch_pkey PRIMARY KEY (id);

--
-- Name: cs_tool_call_log cs_tool_call_log_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_tool_call_log
    ADD CONSTRAINT cs_tool_call_log_pkey PRIMARY KEY (id);

--
-- Name: cs_tool cs_tool_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_tool
    ADD CONSTRAINT cs_tool_pkey PRIMARY KEY (id);

--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);

--
-- Name: cs_domain uq_domain_code; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_domain
    ADD CONSTRAINT uq_domain_code UNIQUE (code);

--
-- Name: cs_intent uq_intent_domain_code; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent
    ADD CONSTRAINT uq_intent_domain_code UNIQUE (domain_id, code);

--
-- Name: cs_intent_tool uq_intent_tool; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT uq_intent_tool UNIQUE (intent_id, tool_id);

--
-- Name: cs_intent_slot uq_slot_intent_name; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot
    ADD CONSTRAINT uq_slot_intent_name UNIQUE (intent_id, slot_name);

--
-- Name: cs_tool uq_tool_code; Type: CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_tool
    ADD CONSTRAINT uq_tool_code UNIQUE (code);

--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON cs_conversation.flyway_schema_history USING btree (success);

--
-- Name: idx_cs_conv_status; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_cs_conv_status ON cs_conversation.cs_conversation USING btree (status) WHERE ((status)::text <> 'CLOSED'::text);

--
-- Name: idx_cs_msg_session_seq; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_cs_msg_session_seq ON cs_conversation.cs_conversation_message USING btree (session_id, seq) WHERE (seq IS NOT NULL);

--
-- Name: idx_cs_msg_session_time; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_cs_msg_session_time ON cs_conversation.cs_conversation_message USING btree (session_id, created_at);

--
-- Name: idx_session_domain_switch_created; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_session_domain_switch_created ON cs_conversation.cs_session_domain_switch USING btree (created_at);

--
-- Name: idx_session_domain_switch_session; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_session_domain_switch_session ON cs_conversation.cs_session_domain_switch USING btree (session_id);

--
-- Name: idx_tool_call_log_created; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_tool_call_log_created ON cs_conversation.cs_tool_call_log USING btree (created_at);

--
-- Name: idx_tool_call_log_session; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE INDEX idx_tool_call_log_session ON cs_conversation.cs_tool_call_log USING btree (session_id);

--
-- Name: uk_cs_conv_session; Type: INDEX; Schema: cs_conversation; Owner: -
--

CREATE UNIQUE INDEX uk_cs_conv_session ON cs_conversation.cs_conversation USING btree (session_id);

--
-- Name: cs_conversation trg_cs_conv_updated; Type: TRIGGER; Schema: cs_conversation; Owner: -
--

CREATE TRIGGER trg_cs_conv_updated BEFORE UPDATE ON cs_conversation.cs_conversation FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_updated_at();

--
-- Name: cs_intent cs_intent_domain_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent
    ADD CONSTRAINT cs_intent_domain_id_fkey FOREIGN KEY (domain_id) REFERENCES cs_conversation.cs_domain(id);

--
-- Name: cs_intent_slot cs_intent_slot_intent_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot
    ADD CONSTRAINT cs_intent_slot_intent_id_fkey FOREIGN KEY (intent_id) REFERENCES cs_conversation.cs_intent(id);

--
-- Name: cs_intent_tool cs_intent_tool_intent_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT cs_intent_tool_intent_id_fkey FOREIGN KEY (intent_id) REFERENCES cs_conversation.cs_intent(id);

--
-- Name: cs_intent_tool cs_intent_tool_tool_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: -
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT cs_intent_tool_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES cs_conversation.cs_tool(id);
--
-- PostgreSQL database dump complete
--
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

--
-- message feedback (cs_conversation schema)
-- 访客对 AI/座席消息的点赞/点踩，(session_id, seq) 唯一，null feedback 表示取消
--
CREATE TABLE cs_conversation.cs_message_feedback (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(64) NOT NULL,
    seq         BIGINT      NOT NULL,
    feedback    VARCHAR(8)  NOT NULL,
    visitor_id  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_msg_feedback UNIQUE (session_id, seq),
    CONSTRAINT ck_msg_feedback_value CHECK (feedback IN ('up','down'))
);
CREATE INDEX idx_msg_feedback_session ON cs_conversation.cs_message_feedback(session_id);
COMMENT ON TABLE  cs_conversation.cs_message_feedback           IS '访客对单条消息的反馈（up/down），(session_id, seq) 唯一';
COMMENT ON COLUMN cs_conversation.cs_message_feedback.seq       IS '对应 cs_conversation_message.seq，允许历史消息（seq 非空）被反馈';
COMMENT ON COLUMN cs_conversation.cs_message_feedback.feedback  IS '反馈类型：up=点赞, down=点踩；取消反馈则删除该行';

-- 2026-07-17: 访客会话创建统一化改造
-- 1. 新增访客标识列
ALTER TABLE cs_conversation.cs_conversation
    ADD COLUMN IF NOT EXISTS visitor_id     VARCHAR(64)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS visitor_ip     VARCHAR(45)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS visitor_device VARCHAR(500) DEFAULT NULL;

COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_id
    IS '访客唯一标识，前端 localStorage 生成的 anonymousId';
COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_ip
    IS '访客 IP，取 X-Forwarded-For 首个地址或直连 RemoteAddr，支持 IPv4/IPv6';
COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_device
    IS '访客设备信息，原始 User-Agent 字符串';

-- 2. 新增索引（生产执行时用 CONCURRENTLY）
CREATE INDEX IF NOT EXISTS idx_cs_conv_visitor_id
    ON cs_conversation.cs_conversation (visitor_id, status)
    WHERE visitor_id IS NOT NULL;

-- 每周排班配置（7条固定记录，只允许 UPDATE 不允许 DELETE）
CREATE TABLE IF NOT EXISTS cs_conversation.cs_business_hours_schedule (
    day_of_week  SMALLINT     NOT NULL,
    is_open      SMALLINT     NOT NULL DEFAULT 1,
    time_ranges  JSONB        NOT NULL,
    timezone     VARCHAR(50)  NOT NULL DEFAULT 'Asia/Shanghai',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (day_of_week)
);
COMMENT ON TABLE  cs_conversation.cs_business_hours_schedule             IS '每周排班配置';
COMMENT ON COLUMN cs_conversation.cs_business_hours_schedule.day_of_week IS '1=周一 … 7=周日';
COMMENT ON COLUMN cs_conversation.cs_business_hours_schedule.is_open     IS '当天是否营业';
COMMENT ON COLUMN cs_conversation.cs_business_hours_schedule.time_ranges IS '[{"start":"HH:mm","end":"HH:mm"}]';

CREATE OR REPLACE FUNCTION cs_conversation.set_update_time()
    RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.update_time = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_biz_hours_schedule_update_time
    BEFORE UPDATE ON cs_conversation.cs_business_hours_schedule
    FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();

-- 节假日例外配置
CREATE TABLE IF NOT EXISTS cs_conversation.cs_business_hours_holiday (
    id          BIGSERIAL    NOT NULL,
    date        DATE         NOT NULL,
    type        VARCHAR(10)  NOT NULL,
    time_ranges JSONB,
    remark      VARCHAR(100),
    source      VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id),
    CONSTRAINT uk_biz_holiday_date UNIQUE (date)
);
COMMENT ON TABLE  cs_conversation.cs_business_hours_holiday             IS '节假日例外配置';
COMMENT ON COLUMN cs_conversation.cs_business_hours_holiday.date        IS '具体日期';
COMMENT ON COLUMN cs_conversation.cs_business_hours_holiday.type        IS 'CLOSED | CUSTOM | WORKDAY';
COMMENT ON COLUMN cs_conversation.cs_business_hours_holiday.time_ranges IS 'CUSTOM/WORKDAY 必填，指定当天服务时段；CLOSED 时为 null';
COMMENT ON COLUMN cs_conversation.cs_business_hours_holiday.remark      IS '备注';
COMMENT ON COLUMN cs_conversation.cs_business_hours_holiday.source      IS 'AUTO | MANUAL';
