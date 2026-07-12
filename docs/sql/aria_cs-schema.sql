--
-- PostgreSQL database dump
--

\restrict ssj1gNQprlQEnz7daH8ob9g2E5pQJM3E1MOFlJJ0eJFKa2bsCMaxE56sgXDhF7g

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
-- Name: cs_auth; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA cs_auth;


--
-- Name: cs_conversation; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA cs_conversation;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: cs_auth; Owner: -
--

CREATE FUNCTION cs_auth.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


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
-- Name: ai_model_config; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.ai_model_config (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    provider character varying(50) NOT NULL,
    api_protocol character varying(30) DEFAULT 'OPENAI_COMPATIBLE'::character varying NOT NULL,
    remark text,
    base_url character varying(500) NOT NULL,
    api_key_enc character varying(500) NOT NULL,
    model_name character varying(100) NOT NULL,
    temperature numeric(4,2) DEFAULT 0.7,
    max_tokens integer DEFAULT 4096,
    timeout_sec integer DEFAULT 60,
    is_default boolean DEFAULT false NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    created_by bigint,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone,
    model_type character varying(20) DEFAULT 'CHAT'::character varying NOT NULL,
    CONSTRAINT ai_model_config_model_type_check CHECK (((model_type)::text = ANY ((ARRAY['CHAT'::character varying, 'EMBEDDING'::character varying, 'ROUTER'::character varying])::text[])))
);


--
-- Name: TABLE ai_model_config; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.ai_model_config IS 'AI 模型配置表，支持多模型切换';


--
-- Name: COLUMN ai_model_config.api_key_enc; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.ai_model_config.api_key_enc IS 'API Key，格式：PLAINTEXT:{raw}（开发）或 AES:{base64}（生产）';


--
-- Name: COLUMN ai_model_config.is_default; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.ai_model_config.is_default IS '是否为默认模型，系统同时只有一个 is_default=true';


--
-- Name: COLUMN ai_model_config.model_type; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.ai_model_config.model_type IS 'CHAT=对话大模型, EMBEDDING=向量模型, ROUTER=域路由小模型';


--
-- Name: ai_model_config_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.ai_model_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ai_model_config_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.ai_model_config_id_seq OWNED BY cs_auth.ai_model_config.id;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.flyway_schema_history (
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
-- Name: sys_dept; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_dept (
    id bigint NOT NULL,
    parent_id bigint DEFAULT 0 NOT NULL,
    dept_name character varying(100) NOT NULL,
    dept_code character varying(50) NOT NULL,
    ancestor_ids text DEFAULT ''::text NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    leader character varying(50),
    phone character varying(20),
    email character varying(100),
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    deleted_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE sys_dept; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.sys_dept IS '部门树，用于数据权限的组织维度过滤';


--
-- Name: COLUMN sys_dept.ancestor_ids; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.sys_dept.ancestor_ids IS '祖先ID路径，格式：0,parentId,...,deptId';


--
-- Name: sys_dept_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.sys_dept_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_dept_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.sys_dept_id_seq OWNED BY cs_auth.sys_dept.id;


--
-- Name: sys_menu; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_menu (
    id bigint NOT NULL,
    parent_id bigint DEFAULT 0 NOT NULL,
    menu_type character varying(20) NOT NULL,
    menu_name character varying(100) NOT NULL,
    menu_key character varying(100) NOT NULL,
    path character varying(200),
    component character varying(200),
    icon character varying(100),
    sort_order integer DEFAULT 0 NOT NULL,
    is_visible boolean DEFAULT true NOT NULL,
    is_cache boolean DEFAULT true NOT NULL,
    is_external boolean DEFAULT false NOT NULL,
    redirect character varying(200),
    permission_key character varying(100),
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    remark text,
    created_by bigint,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE sys_menu; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.sys_menu IS '菜单与按钮权限表，支持多级树状结构';


--
-- Name: COLUMN sys_menu.menu_type; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.sys_menu.menu_type IS '菜单类型：DIRECTORY=目录，MENU=菜单页面，BUTTON=按钮/接口';


--
-- Name: COLUMN sys_menu.permission_key; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.sys_menu.permission_key IS 'BUTTON类型对应的接口权限标识';


--
-- Name: sys_menu_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.sys_menu_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_menu_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.sys_menu_id_seq OWNED BY cs_auth.sys_menu.id;


--
-- Name: sys_permission; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_permission (
    id bigint NOT NULL,
    permission_key character varying(100) NOT NULL,
    permission_name character varying(200) NOT NULL,
    module character varying(50) NOT NULL,
    description text,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: sys_permission_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.sys_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_permission_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.sys_permission_id_seq OWNED BY cs_auth.sys_permission.id;


--
-- Name: sys_role; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_role (
    id bigint NOT NULL,
    role_key character varying(50) NOT NULL,
    role_name character varying(100) NOT NULL,
    description text,
    is_system boolean DEFAULT false NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE sys_role; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.sys_role IS '角色表';


--
-- Name: sys_role_data_scope; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_role_data_scope (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    scope_type character varying(30) DEFAULT 'SELF'::character varying NOT NULL,
    custom_dept_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE sys_role_data_scope; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.sys_role_data_scope IS '角色数据权限范围：ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF';


--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.sys_role_data_scope_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.sys_role_data_scope_id_seq OWNED BY cs_auth.sys_role_data_scope.id;


--
-- Name: sys_role_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.sys_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_role_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.sys_role_id_seq OWNED BY cs_auth.sys_role.id;


--
-- Name: sys_role_menu; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_role_menu (
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: sys_role_permission; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_role_permission (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


--
-- Name: sys_user; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_user (
    id bigint NOT NULL,
    username character varying(50) NOT NULL,
    display_name character varying(100) NOT NULL,
    email character varying(200) NOT NULL,
    phone character varying(20),
    password_hash character varying(200) NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    provider character varying(30) DEFAULT 'LOCAL'::character varying NOT NULL,
    login_fail_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp without time zone,
    must_change_password boolean DEFAULT true NOT NULL,
    password_changed_at timestamp without time zone,
    password_history jsonb DEFAULT '[]'::jsonb NOT NULL,
    last_login_at timestamp without time zone,
    last_login_ip character varying(50),
    dept_id bigint,
    dept_name character varying(100),
    deleted_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE sys_user; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.sys_user IS '用户表';


--
-- Name: COLUMN sys_user.password_hash; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.sys_user.password_hash IS 'BCrypt(cost=10) 密码哈希';


--
-- Name: COLUMN sys_user.locked_until; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.sys_user.locked_until IS '锁定截止时间，NULL=未锁定';


--
-- Name: COLUMN sys_user.password_history; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.sys_user.password_history IS '最近5次密码哈希，防重用';


--
-- Name: sys_user_dept; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_user_dept (
    user_id bigint NOT NULL,
    dept_id bigint NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: sys_user_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.sys_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: sys_user_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.sys_user_id_seq OWNED BY cs_auth.sys_user.id;


--
-- Name: sys_user_role; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.sys_user_role (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    granted_at timestamp without time zone DEFAULT now() NOT NULL,
    granted_by bigint
);


--
-- Name: system_config; Type: TABLE; Schema: cs_auth; Owner: -
--

CREATE TABLE cs_auth.system_config (
    id bigint NOT NULL,
    config_key character varying(100) NOT NULL,
    config_value text NOT NULL,
    config_type character varying(50) DEFAULT 'SYSTEM'::character varying NOT NULL,
    description character varying(255) DEFAULT ''::character varying NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone
);


--
-- Name: TABLE system_config; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON TABLE cs_auth.system_config IS '系统配置表';


--
-- Name: COLUMN system_config.config_key; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.system_config.config_key IS '配置键，全局唯一（未删除）';


--
-- Name: COLUMN system_config.config_value; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.system_config.config_value IS '配置值，统一字符串存储';


--
-- Name: COLUMN system_config.config_type; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.system_config.config_type IS '配置类型：SYSTEM | CUSTOMER_SERVICE';


--
-- Name: COLUMN system_config.is_enabled; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.system_config.is_enabled IS '是否启用，禁用时业务层回退硬编码默认值';


--
-- Name: COLUMN system_config.deleted_at; Type: COMMENT; Schema: cs_auth; Owner: -
--

COMMENT ON COLUMN cs_auth.system_config.deleted_at IS '软删除时间，NULL 表示未删除';


--
-- Name: system_config_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: -
--

CREATE SEQUENCE cs_auth.system_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: system_config_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: -
--

ALTER SEQUENCE cs_auth.system_config_id_seq OWNED BY cs_auth.system_config.id;


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
    closed_by character varying(20)
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
    sort_order integer DEFAULT 0 NOT NULL
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
-- Name: ai_model_config id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.ai_model_config ALTER COLUMN id SET DEFAULT nextval('cs_auth.ai_model_config_id_seq'::regclass);


--
-- Name: sys_dept id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_dept ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_dept_id_seq'::regclass);


--
-- Name: sys_menu id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_menu ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_menu_id_seq'::regclass);


--
-- Name: sys_permission id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_permission ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_permission_id_seq'::regclass);


--
-- Name: sys_role id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_role_id_seq'::regclass);


--
-- Name: sys_role_data_scope id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role_data_scope ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_role_data_scope_id_seq'::regclass);


--
-- Name: sys_user id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_user ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_user_id_seq'::regclass);


--
-- Name: system_config id; Type: DEFAULT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.system_config ALTER COLUMN id SET DEFAULT nextval('cs_auth.system_config_id_seq'::regclass);


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
-- Name: ai_model_config ai_model_config_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.ai_model_config
    ADD CONSTRAINT ai_model_config_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: sys_dept sys_dept_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_dept
    ADD CONSTRAINT sys_dept_pkey PRIMARY KEY (id);


--
-- Name: sys_menu sys_menu_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_menu
    ADD CONSTRAINT sys_menu_pkey PRIMARY KEY (id);


--
-- Name: sys_permission sys_permission_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_permission
    ADD CONSTRAINT sys_permission_pkey PRIMARY KEY (id);


--
-- Name: sys_role_data_scope sys_role_data_scope_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role_data_scope
    ADD CONSTRAINT sys_role_data_scope_pkey PRIMARY KEY (id);


--
-- Name: sys_role_data_scope sys_role_data_scope_role_id_key; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role_data_scope
    ADD CONSTRAINT sys_role_data_scope_role_id_key UNIQUE (role_id);


--
-- Name: sys_role_menu sys_role_menu_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role_menu
    ADD CONSTRAINT sys_role_menu_pkey PRIMARY KEY (role_id, menu_id);


--
-- Name: sys_role_permission sys_role_permission_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role_permission
    ADD CONSTRAINT sys_role_permission_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: sys_role sys_role_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_role
    ADD CONSTRAINT sys_role_pkey PRIMARY KEY (id);


--
-- Name: sys_user_dept sys_user_dept_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_user_dept
    ADD CONSTRAINT sys_user_dept_pkey PRIMARY KEY (user_id, dept_id);


--
-- Name: sys_user sys_user_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_user
    ADD CONSTRAINT sys_user_pkey PRIMARY KEY (id);


--
-- Name: sys_user_role sys_user_role_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.sys_user_role
    ADD CONSTRAINT sys_user_role_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: system_config system_config_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: -
--

ALTER TABLE ONLY cs_auth.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (id);


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
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON cs_auth.flyway_schema_history USING btree (success);


--
-- Name: idx_ai_model_default; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_ai_model_default ON cs_auth.ai_model_config USING btree (is_default) WHERE (deleted_at IS NULL);


--
-- Name: idx_ai_model_enabled; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_ai_model_enabled ON cs_auth.ai_model_config USING btree (is_enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_ai_model_type_default; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_ai_model_type_default ON cs_auth.ai_model_config USING btree (model_type, is_default) WHERE ((deleted_at IS NULL) AND (is_enabled = true));


--
-- Name: idx_cs_dept_parent; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_dept_parent ON cs_auth.sys_dept USING btree (parent_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_cs_menu_parent; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_menu_parent ON cs_auth.sys_menu USING btree (parent_id);


--
-- Name: idx_cs_menu_type_status; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_menu_type_status ON cs_auth.sys_menu USING btree (menu_type, status);


--
-- Name: idx_cs_permission_module; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_permission_module ON cs_auth.sys_permission USING btree (module);


--
-- Name: idx_cs_role_menu_menu; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_role_menu_menu ON cs_auth.sys_role_menu USING btree (menu_id);


--
-- Name: idx_cs_role_permission_perm; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_role_permission_perm ON cs_auth.sys_role_permission USING btree (permission_id);


--
-- Name: idx_cs_user_dept_dept; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_user_dept_dept ON cs_auth.sys_user_dept USING btree (dept_id);


--
-- Name: idx_cs_user_lastlogin; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_user_lastlogin ON cs_auth.sys_user USING btree (last_login_at);


--
-- Name: idx_cs_user_role_role; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_user_role_role ON cs_auth.sys_user_role USING btree (role_id);


--
-- Name: idx_cs_user_status; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_cs_user_status ON cs_auth.sys_user USING btree (status) WHERE (deleted_at IS NULL);


--
-- Name: idx_system_config_type; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE INDEX idx_system_config_type ON cs_auth.system_config USING btree (config_type) WHERE (deleted_at IS NULL);


--
-- Name: uk_cs_dept_code; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uk_cs_dept_code ON cs_auth.sys_dept USING btree (dept_code) WHERE (deleted_at IS NULL);


--
-- Name: uk_cs_menu_key; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uk_cs_menu_key ON cs_auth.sys_menu USING btree (menu_key);


--
-- Name: uk_cs_permission_key; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uk_cs_permission_key ON cs_auth.sys_permission USING btree (permission_key);


--
-- Name: uk_cs_role_key; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uk_cs_role_key ON cs_auth.sys_role USING btree (role_key);


--
-- Name: uk_cs_user_email; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uk_cs_user_email ON cs_auth.sys_user USING btree (email) WHERE (deleted_at IS NULL);


--
-- Name: uk_cs_user_username; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uk_cs_user_username ON cs_auth.sys_user USING btree (username) WHERE (deleted_at IS NULL);


--
-- Name: uq_system_config_key; Type: INDEX; Schema: cs_auth; Owner: -
--

CREATE UNIQUE INDEX uq_system_config_key ON cs_auth.system_config USING btree (config_key) WHERE (deleted_at IS NULL);


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
-- Name: ai_model_config trg_ai_model_config_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_ai_model_config_updated BEFORE UPDATE ON cs_auth.ai_model_config FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_dept trg_cs_dept_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_cs_dept_updated BEFORE UPDATE ON cs_auth.sys_dept FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_menu trg_cs_menu_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_cs_menu_updated BEFORE UPDATE ON cs_auth.sys_menu FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_role_data_scope trg_cs_role_scope_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_cs_role_scope_updated BEFORE UPDATE ON cs_auth.sys_role_data_scope FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_role trg_cs_role_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_cs_role_updated BEFORE UPDATE ON cs_auth.sys_role FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_user trg_cs_user_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_cs_user_updated BEFORE UPDATE ON cs_auth.sys_user FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: system_config trg_system_config_updated; Type: TRIGGER; Schema: cs_auth; Owner: -
--

CREATE TRIGGER trg_system_config_updated BEFORE UPDATE ON cs_auth.system_config FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


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

\unrestrict ssj1gNQprlQEnz7daH8ob9g2E5pQJM3E1MOFlJJ0eJFKa2bsCMaxE56sgXDhF7g

