-- ========================================
-- Module: auth-service (cs_auth schema)
--          conversation-service (cs_conversation schema)
-- ========================================

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

-- ========================================
-- Module: auth-service (cs_auth data)
--          conversation-service (cs_conversation data)
-- ========================================

--
-- PostgreSQL database dump
--

\restrict LrTZ4YulIj3mfmuBYGXZLSfwgWCrZpLrHSfeBRQpl6T7eVly0yQuIp92RwsNLhL

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
-- Data for Name: ai_model_config; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (9, 'tongyi-intent-detect-v3', 'CUSTOM', 'OPENAI_COMPATIBLE', NULL, 'https://llm-abgxi9yilg0zfoev.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'tongyi-intent-detect-v3', 0.00, 32, 5, false, true, 1001, '2026-07-10 00:40:08.402486', '2026-07-10 00:49:46.093174', NULL, 'ROUTER');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (10, 'tongyi-xiaomi-analysis-flash', 'CUSTOM', 'OPENAI_COMPATIBLE', NULL, 'https://llm-abgxi9yilg0zfoev.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'tongyi-xiaomi-analysis-flash', 0.00, 32, 5, true, true, 1001, '2026-07-10 00:49:37.09858', '2026-07-10 00:49:46.093174', NULL, 'ROUTER');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (2, '本地 BGE-M3', 'Custom', 'OPENAI_COMPATIBLE', NULL, 'http://localhost:11434/v1', 'PLAINTEXT:', 'bge-m3', 0.00, 0, 60, true, true, NULL, '2026-07-02 17:21:17.73629', '2026-07-09 21:30:07.480823', NULL, 'EMBEDDING');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (1, '天翼云 DeepSeek-V4-Pro', 'CTYUN', 'OPENAI_COMPATIBLE', '天翼云 AI 平台 DeepSeek-V4-Flash 模型，默认对话模型', 'https://wishub-x6.ctyun.cn/v1', 'PLAINTEXT:c4747b2f3e3e49308b5fb0ba256cb70e', 'DeepSeek-V4-Pro', 0.70, 4096, 60, true, true, 1001, '2026-07-02 15:33:05.115131', '2026-07-10 00:26:59.38003', NULL, 'CHAT');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (8, 'qwen3.6-flash', 'CUSTOM', 'OPENAI_COMPATIBLE', NULL, 'https://llm-abgxi9yilg0zfoev.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'qwen3.6-flash-2026-04-16', 0.00, 32, 5, false, true, 1001, '2026-07-10 00:39:28.402335', '2026-07-10 00:39:28.404229', NULL, 'ROUTER');
INSERT INTO cs_auth.ai_model_config (id, name, provider, api_protocol, remark, base_url, api_key_enc, model_name, temperature, max_tokens, timeout_sec, is_default, is_enabled, created_by, created_at, updated_at, deleted_at, model_type) VALUES (7, '意图识别和槽位填充模型', 'OPENAI', 'OPENAI_COMPATIBLE', NULL, 'http://192.168.1.8:11434/v1', 'PLAINTEXT:sk-53e959ca40084e06953073c5c529e3ef', 'qwen:4b', 0.00, 32, 5, false, true, 1001, '2026-07-06 23:04:45.988999', '2026-07-10 00:40:12.55508', NULL, 'ROUTER');


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'postgres', '2026-06-29 15:07:40.499792', 0, true);
INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '1', 'init cs auth schema', 'SQL', 'V1__init_cs_auth_schema.sql', 0, 'postgres', '2026-06-29 07:09:05.614612', 100, true);
INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '2', 'seed menu data', 'SQL', 'V2__seed_menu_data.sql', -1038647149, 'postgres', '2026-06-29 15:09:16.154434', 17, true);
INSERT INTO cs_auth.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '3', 'seed test users', 'SQL', 'V3__seed_test_users.sql', -1914365077, 'postgres', '2026-06-29 15:09:16.211396', 24, true);


--
-- Data for Name: sys_dept; Type: TABLE DATA; Schema: cs_auth; Owner: -
--



--
-- Data for Name: sys_menu; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (1, 0, 'DIRECTORY', '概览', 'Dashboard', '/dashboard', NULL, 'lucide:layout-dashboard', 1, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (3, 1, 'MENU', '工作台', 'DashboardWorkspace', '/dashboard/workspace', 'dashboard/workspace/index', 'carbon:workspace', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (102, 100, 'MENU', '知识库', 'CustomerServiceKnowledge', '/customerservice/knowledge', 'customerservice/knowledge/index', 'lucide:book-open', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (103, 100, 'MENU', '座席工作台', 'CustomerServiceAgent', '/customerservice/agent', 'customerservice/agent/index', 'lucide:headphones', 3, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (110, 102, 'BUTTON', '上传文档', 'knowledge:doc:upload', NULL, NULL, NULL, 1, false, false, false, NULL, 'knowledge:doc:upload', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (111, 102, 'BUTTON', '审核文档', 'knowledge:doc:review', NULL, NULL, NULL, 2, false, false, false, NULL, 'knowledge:doc:review', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (112, 102, 'BUTTON', '下线文档', 'knowledge:doc:offline', NULL, NULL, NULL, 3, false, false, false, NULL, 'knowledge:doc:offline', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (113, 102, 'BUTTON', '删除文档', 'knowledge:doc:delete', NULL, NULL, NULL, 4, false, false, false, NULL, 'knowledge:doc:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (120, 103, 'BUTTON', '接入会话', 'agent:session:accept', NULL, NULL, NULL, 1, false, false, false, NULL, 'agent:session:accept', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (121, 103, 'BUTTON', '结束会话', 'agent:session:close', NULL, NULL, NULL, 2, false, false, false, NULL, 'agent:session:close', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (122, 103, 'BUTTON', '转交会话', 'agent:session:transfer', NULL, NULL, NULL, 3, false, false, false, NULL, 'agent:session:transfer', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (200, 0, 'DIRECTORY', '系统管理', 'System', '/system', NULL, 'lucide:settings', 90, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (201, 200, 'MENU', '用户管理', 'SystemUser', '/system/user', 'system/user/index', 'lucide:users', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (202, 200, 'MENU', '角色管理', 'SystemRole', '/system/role', 'system/role/index', 'lucide:shield', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (203, 200, 'MENU', '菜单管理', 'SystemMenu', '/system/menu', 'system/menu/index', 'lucide:layout-list', 3, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (210, 201, 'BUTTON', '新增用户', 'system:user:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:user:create', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (211, 201, 'BUTTON', '编辑用户', 'system:user:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:user:update', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (212, 201, 'BUTTON', '删除用户', 'system:user:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:user:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (213, 201, 'BUTTON', '重置密码', 'system:user:reset-pwd', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:user:reset-pwd', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (214, 201, 'BUTTON', '分配角色', 'system:user:assign-role', NULL, NULL, NULL, 5, false, false, false, NULL, 'system:user:assign-role', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (220, 202, 'BUTTON', '新增角色', 'system:role:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:role:create', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (221, 202, 'BUTTON', '编辑角色', 'system:role:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:role:update', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (222, 202, 'BUTTON', '删除角色', 'system:role:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:role:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (223, 202, 'BUTTON', '分配菜单', 'system:role:assign-menu', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:role:assign-menu', 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (900, 0, 'MENU', '关于', 'About', '/about', '_core/about/index', 'lucide:copyright', 99, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-06-29 07:04:09.968097');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (204, 200, 'MENU', '部门管理', 'SystemDept', '/system/dept', '_core/fallback/coming-soon', 'lucide:building-2', 4, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-02 15:29:51.400768');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (2, 1, 'MENU', '分析页', 'DashboardAnalysis', '/dashboard/analysis', 'dashboard/analytics/index', 'lucide:area-chart', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-02 15:30:07.084421');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (205, 200, 'MENU', 'AI 模型配置', 'SystemAiModel', '/system/ai-model', 'system/ai-model/index', 'lucide:cpu', 5, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-02 17:08:00.32996', '2026-07-02 17:08:00.32996');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (230, 205, 'BUTTON', '新增配置', 'system:ai-model:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:ai-model:create', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (231, 205, 'BUTTON', '编辑配置', 'system:ai-model:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:ai-model:update', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (232, 205, 'BUTTON', '删除配置', 'system:ai-model:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:ai-model:delete', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (233, 205, 'BUTTON', '设为默认', 'system:ai-model:set-default', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:ai-model:set-default', 'active', NULL, NULL, '2026-07-02 17:08:16.290288', '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (104, 100, 'DIRECTORY', 'DIT配置', 'CustomerServiceDIT', '/customerservice/dit', NULL, 'lucide:settings-2', 40, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (105, 104, 'MENU', '领域与意图', 'CustomerServiceDITDomains', '/customerservice/dit/domains', 'customerservice/dit/domains/index', 'lucide:layers', 10, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (106, 104, 'MENU', '工具注册中心', 'CustomerServiceDITTools', '/customerservice/dit/tools', 'customerservice/dit/tools/index', 'lucide:wrench', 20, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (130, 105, 'BUTTON', '新建领域', 'dit:domain:create', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (131, 105, 'BUTTON', '编辑领域', 'dit:domain:update', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (132, 105, 'BUTTON', '删除领域', 'dit:domain:delete', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (133, 105, 'BUTTON', '管理意图', 'dit:intent:manage', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (134, 105, 'BUTTON', '管理槽位', 'dit:slot:manage', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (135, 106, 'BUTTON', '注册工具', 'dit:tool:create', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (136, 106, 'BUTTON', '编辑工具', 'dit:tool:update', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (137, 106, 'BUTTON', '删除工具', 'dit:tool:delete', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (138, 106, 'BUTTON', '测试工具', 'dit:tool:test', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749', '2026-07-04 15:48:09.284749');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (901, 200, 'MENU', '系统参数配置', 'system:config', '/system/config', 'system/config/index', 'lucide:sliders', 90, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-12 04:31:30.295086', '2026-07-12 06:47:43.024942');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (902, 100, 'MENU', '客服参数配置', 'customerservice:config', '/customerservice/config', 'system/config/index', 'lucide:sliders-horizontal', 90, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-12 04:31:30.295086', '2026-07-12 06:47:43.024942');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (101, 100, 'MENU', '对话', 'CustomerServiceChat', '/customerservice/chat', 'customerservice/chat/index', 'lucide:message-circle', 1, false, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-12 14:52:17.780293');
INSERT INTO cs_auth.sys_menu (id, parent_id, menu_type, menu_name, menu_key, path, component, icon, sort_order, is_visible, is_cache, is_external, redirect, permission_key, status, remark, created_by, created_at, updated_at) VALUES (100, 0, 'DIRECTORY', '智能客服', 'CustomerService', '/customerservice', NULL, 'lucide:bot', 10, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097', '2026-07-12 14:52:12.963409');


--
-- Data for Name: sys_permission; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (1, 'knowledge:doc:upload', '上传文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (2, 'knowledge:doc:review', '审核文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (3, 'knowledge:doc:offline', '下线文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (4, 'knowledge:doc:delete', '删除文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (5, 'agent:session:accept', '接入会话', 'agent', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (6, 'agent:session:close', '结束会话', 'agent', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (7, 'agent:session:transfer', '转交会话', 'agent', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (8, 'system:user:create', '新增用户', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (9, 'system:user:update', '编辑用户', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (10, 'system:user:delete', '删除用户', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (11, 'system:user:reset-pwd', '重置密码', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (12, 'system:user:assign-role', '分配角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (13, 'system:role:create', '新增角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (14, 'system:role:update', '编辑角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (15, 'system:role:delete', '删除角色', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (16, 'system:role:assign-menu', '分配菜单', 'system', NULL, '2026-06-29 07:04:09.971301');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (50, 'system:ai-model:create', '新增AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (51, 'system:ai-model:update', '编辑AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (52, 'system:ai-model:delete', '删除AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (53, 'system:ai-model:set-default', '设为默认AI模型', 'system', NULL, '2026-07-02 17:08:16.290288');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (54, 'system:config:list', '系统配置-查询', 'system_config', '查看系统配置列表及详情', '2026-07-12 04:31:30.288745');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (55, 'system:config:create', '系统配置-新增', 'system_config', '新增系统配置项', '2026-07-12 04:31:30.288745');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (56, 'system:config:update', '系统配置-编辑', 'system_config', '修改配置值及启用状态', '2026-07-12 04:31:30.288745');
INSERT INTO cs_auth.sys_permission (id, permission_key, permission_name, module, description, created_at) VALUES (57, 'system:config:delete', '系统配置-删除', 'system_config', '软删除系统配置项', '2026-07-12 04:31:30.288745');


--
-- Data for Name: sys_role; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role (id, role_key, role_name, description, is_system, status, created_at, updated_at) VALUES (10, 'super_admin', '超级管理员', NULL, true, 'active', '2026-06-29 07:04:09.972562', '2026-06-29 07:04:09.972562');
INSERT INTO cs_auth.sys_role (id, role_key, role_name, description, is_system, status, created_at, updated_at) VALUES (11, 'kf_manager', '客服管理员', NULL, false, 'active', '2026-06-29 07:04:09.972562', '2026-06-29 07:04:09.972562');
INSERT INTO cs_auth.sys_role (id, role_key, role_name, description, is_system, status, created_at, updated_at) VALUES (12, 'kf_staff', '普通客服', NULL, false, 'active', '2026-06-29 07:04:09.972562', '2026-06-29 07:04:09.972562');


--
-- Data for Name: sys_role_data_scope; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role_data_scope (id, role_id, scope_type, custom_dept_ids, created_at, updated_at) VALUES (1, 10, 'ALL', '[]', '2026-06-29 07:04:09.974783', '2026-06-29 15:09:16.215976');
INSERT INTO cs_auth.sys_role_data_scope (id, role_id, scope_type, custom_dept_ids, created_at, updated_at) VALUES (2, 11, 'DEPT_TREE', '[]', '2026-06-29 07:04:09.974783', '2026-06-29 15:09:16.215976');
INSERT INTO cs_auth.sys_role_data_scope (id, role_id, scope_type, custom_dept_ids, created_at, updated_at) VALUES (3, 12, 'SELF', '[]', '2026-06-29 07:04:09.974783', '2026-06-29 15:09:16.215976');


--
-- Data for Name: sys_role_menu; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 100, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 101, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 102, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 103, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 110, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 111, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 112, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 113, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 120, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 121, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 122, '2026-06-29 07:04:09.977047');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (12, 100, '2026-06-29 07:04:09.977935');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (12, 101, '2026-06-29 07:04:09.977935');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 104, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 105, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 106, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 133, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 134, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 138, '2026-07-04 15:48:09.318584');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 1, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 2, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 3, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 100, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 101, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 102, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 103, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 110, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 111, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 112, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 113, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 120, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 121, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 122, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 200, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 201, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 202, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 203, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 204, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 210, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 211, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 212, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 213, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 214, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 220, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 221, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 222, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 223, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 900, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 205, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 230, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 231, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 232, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 233, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 104, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 105, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 106, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 130, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 131, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 132, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 133, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 134, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 135, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 136, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 137, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 138, '2026-07-09 21:26:27.809012');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 901, '2026-07-12 04:31:30.31534');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (10, 902, '2026-07-12 04:31:30.31534');
INSERT INTO cs_auth.sys_role_menu (role_id, menu_id, created_at) VALUES (11, 902, '2026-07-12 04:31:30.31534');


--
-- Data for Name: sys_role_permission; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 1);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 2);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 3);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 4);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 5);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 6);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 7);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 8);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 9);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 10);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 11);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 12);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 13);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 14);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 15);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 16);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 50);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 51);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 52);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 53);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 54);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 55);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 56);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (10, 57);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 54);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 55);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 56);
INSERT INTO cs_auth.sys_role_permission (role_id, permission_id) VALUES (11, 57);


--
-- Data for Name: sys_user; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

-- 默认密码：Test@123456（BCrypt cost=10）
INSERT INTO cs_auth.sys_user (id, username, display_name, email, phone, password_hash, status, provider, login_fail_count, locked_until, must_change_password, password_changed_at, password_history, last_login_at, last_login_ip, dept_id, dept_name, deleted_at, created_at, updated_at) VALUES (1003, 'kfstaff', '普通客服', 'kfstaff@example.com', NULL, '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-19 00:00:00.000000', '[]', '2026-07-10 10:52:03.648093', '0:0:0:0:0:0:0:1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982', '2026-07-10 18:52:03.648857');
INSERT INTO cs_auth.sys_user (id, username, display_name, email, phone, password_hash, status, provider, login_fail_count, locked_until, must_change_password, password_changed_at, password_history, last_login_at, last_login_ip, dept_id, dept_name, deleted_at, created_at, updated_at) VALUES (1001, 'superadmin', '超级管理员', 'superadmin@example.com', NULL, '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-19 00:00:00.000000', '[]', '2026-07-12 10:52:37.539064', '0:0:0:0:0:0:0:1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982', '2026-07-12 18:52:37.668473');
INSERT INTO cs_auth.sys_user (id, username, display_name, email, phone, password_hash, status, provider, login_fail_count, locked_until, must_change_password, password_changed_at, password_history, last_login_at, last_login_ip, dept_id, dept_name, deleted_at, created_at, updated_at) VALUES (1002, 'kfmanager', '客服管理员', 'kfmanager@example.com', NULL, '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-19 00:00:00.000000', '[]', '2026-06-29 16:40:57.865042', '127.0.0.1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982', '2026-06-30 00:40:57.781134');


--
-- Data for Name: sys_user_dept; Type: TABLE DATA; Schema: cs_auth; Owner: -
--



--
-- Data for Name: sys_user_role; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.sys_user_role (user_id, role_id, granted_at, granted_by) VALUES (1001, 10, '2026-06-29 07:04:09.982149', NULL);
INSERT INTO cs_auth.sys_user_role (user_id, role_id, granted_at, granted_by) VALUES (1002, 11, '2026-06-29 07:04:09.982149', NULL);
INSERT INTO cs_auth.sys_user_role (user_id, role_id, granted_at, granted_by) VALUES (1003, 12, '2026-06-29 07:04:09.982149', NULL);


--
-- Data for Name: system_config; Type: TABLE DATA; Schema: cs_auth; Owner: -
--

INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (1, 'agent.maxConcurrent', '5', 'CUSTOMER_SERVICE', '单个座席同时接待的最大会话数。超出时系统拒绝新分配。取值范围：1–50', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (2, 'agent.welcomeMessage', '您好，感谢联系我们，请问有什么可以帮助您？', 'CUSTOMER_SERVICE', '会话建立时后端自动插入的欢迎消息内容', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (3, 'knowledge.searchTopK', '5', 'CUSTOMER_SERVICE', 'RAG 检索时返回的最大相关片段数（TopK）。取值范围：1–20', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (4, 'knowledge.uploadMaxFileSizeMb', '20', 'CUSTOMER_SERVICE', '知识库文件上传的单文件大小上限（单位：MB）。取值范围：1–200', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (5, 'prompt.agent.suggestion', '你是一名专业客服，请根据以下对话历史和知识库内容，为座席生成 3 条简洁的回复建议。

对话历史：
{history}

知识库参考：
{context}', 'CUSTOMER_SERVICE', '座席建议回复的 prompt 模板。占位符：{history}、{context}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (6, 'prompt.kb.qa', '你是一名专业客服助手，请根据以下知识库内容回答用户问题。如果知识库中没有相关信息，请如实告知。

知识库内容：
{context}

用户问题：{question}', 'CUSTOMER_SERVICE', '知识库问答的 prompt 模板。占位符：{context}、{question}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (7, 'prompt.visitor.autoReply', '你是一名智能客服，请根据以下对话历史和知识库内容，自动回复访客的最新消息。回复要简洁、友好、专业。

知识库内容：
{context}

对话历史：
{history}', 'CUSTOMER_SERVICE', '访客自动回复的 prompt 模板。占位符：{context}、{history}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (8, 'prompt.session.summary', '请根据以下客服对话记录，生成一份简洁的会话摘要，包含：用户主要问题、解决方案、是否已解决。

对话记录：
{history}', 'CUSTOMER_SERVICE', '会话结束后生成摘要的 prompt 模板。占位符：{history}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (9, 'prompt.intent.classify', '请分析用户消息的意图，从以下类别中选择最匹配的一个：{intents}。

用户消息：{message}

只需返回类别名称，不需要解释。', 'CUSTOMER_SERVICE', '意图识别分类的 prompt 模板。占位符：{intents}、{message}', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (10, 'dashboard.recentLimit', '10', 'SYSTEM', '仪表盘"最近记录"查询的 SQL LIMIT 值。取值范围：5–100', true, '2026-07-12 04:31:30.280237', '2026-07-12 04:31:30.280237', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (11, 'complexity.simpleMaxMessages', '5', 'CUSTOMER_SERVICE', '会话复杂度分桶：消息数 ≤ 此值为「简单」，取值范围 1–20', true, '2026-07-12 05:12:53.167476', '2026-07-12 05:12:53.167476', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (12, 'complexity.mediumMaxMessages', '15', 'CUSTOMER_SERVICE', '会话复杂度分桶：消息数 ≤ 此值为「中等」，超出则为「复杂」，取值范围 6–100', true, '2026-07-12 05:12:53.167476', '2026-07-12 05:12:53.167476', NULL);
INSERT INTO cs_auth.system_config (id, config_key, config_value, config_type, description, is_enabled, created_at, updated_at, deleted_at) VALUES (13, 'routing.config', '{
    "intent": {
      "embeddingEnabled": false,
      "embeddingThreshold": 0.75,
      "minLlmConfidence": 0.0,
      "maxExamplesToInject": 5
    },
    "domain": {
      "ruleEnabled": true
    }
  }', 'CUSTOMER_SERVICE', '意图路由级联配置（JSON）', true, now(), now(), NULL);


--
-- Data for Name: cs_domain; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (3, 'ecommerce', '电商客服', '电商平台客服，处理订单、退款、商品咨询等', '你是一名专业的电商平台客服助手，熟悉订单、退款、物流等业务流程。回答要简洁准确。', NULL, true, '2026-07-05 00:13:54.520015', '2026-07-05 00:13:54.520015');
INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (4, 'finance', '金融客服', '银行及金融产品客服，处理账户查询、理财咨询等', '你是一名专业的金融客服助手。注意：涉及转账、取款等敏感操作必须转接人工，保护用户资金安全。', NULL, true, '2026-07-05 00:13:54.658105', '2026-07-05 00:13:54.658105');
INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (5, 'travel', '酒旅客服', '酒店预订和旅游服务客服', '你是一名专业的酒旅客服助手，擅长酒店推荐、预订流程和旅游攻略。', NULL, true, '2026-07-05 00:13:54.721371', '2026-07-05 00:13:54.721371');
INSERT INTO cs_conversation.cs_domain (id, code, name, description, system_prompt_addon, knowledge_base_id, enabled, created_at, updated_at) VALUES (6, 'weather', '天气助手', '天气查询智能客服，支持实时天气、多日预报、空气质量查询，基于开源免费 API', '你是一个专业的天气查询助手。当用户询问天气时，请调用相应工具获取真实数据后回复，不要编造天气信息。回复时请使用简洁友好的语言，可以适当加上天气相关的贴心提示（如出行建议、穿衣提醒）。', NULL, true, '2026-07-04 16:32:05.98089', '2026-07-04 16:32:05.98089');


--
-- Data for Name: cs_intent; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (1, 3, 'query_order', '查询订单', '用户想查询订单状态、物流信息或订单详情', '["帮我查订单", "我的包裹到哪了", "查一下单号ORD001", "订单什么时候发货"]', false, true, NULL, true, 10);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (2, 3, 'apply_refund', '申请退款', '用户想申请退款或退货', '["我要退款", "申请退货", "这个商品质量太差要退", "退款流程是什么"]', false, true, NULL, true, 20);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (3, 3, 'product_inquiry', '商品咨询', '用户咨询商品详情、规格、库存、适用场景等', '["这款商品有什么颜色", "尺码怎么选", "适合多大年龄", "材质是什么"]', false, false, NULL, true, 30);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (4, 3, 'complaint', '投诉', '用户对服务或商品表达强烈不满，需要投诉', '["我要投诉", "服务太差了", "要求赔偿", "找你们负责人"]', true, false, '非常抱歉给您带来不好的体验，已为您转接专属客服处理投诉。', true, 40);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (5, 3, 'chitchat', '闲聊', '用户进行日常闲聊、问候，与业务无关', '["你好", "今天天气怎么样", "你是谁", "在吗"]', false, true, NULL, true, 50);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (6, 4, 'query_balance', '查询账户余额', '用户想查询银行卡或账户的当前余额', '["我的余额是多少", "查一下账户", "卡里还有多少钱", "账户余额查询"]', false, true, NULL, true, 10);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (7, 4, 'transfer_money', '转账汇款', '用户想进行转账或汇款操作', '["我要转账", "帮我汇款", "转钱给别人", "网银转账"]', true, false, '转账操作涉及资金安全，已为您转接专属人工客服核实身份后处理。', true, 20);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (8, 4, 'investment_inquiry', '理财咨询', '用户咨询理财产品、基金、利率等投资相关问题', '["有什么理财产品", "基金怎么买", "存款利率是多少", "推荐一些低风险产品"]', false, false, NULL, true, 30);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (9, 4, 'report_loss', '挂失补办', '用户需要挂失银行卡或补办卡片', '["银行卡丢了", "卡被盗了要挂失", "怎么补办银行卡", "申请挂失"]', true, false, '挂失业务需要身份核实，已为您转接人工客服处理。', true, 40);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (10, 5, 'search_hotel', '搜索酒店', '用户想查找某城市的可用酒店', '["帮我找北京的酒店", "上海有什么好酒店", "三亚五星级酒店推荐", "明天去杭州住哪好"]', false, true, NULL, true, 10);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (11, 5, 'make_booking', '预订房间', '用户想预订特定酒店的房间', '["我要预订", "帮我订一间", "确认预订", "怎么下单"]', true, false, '预订操作需要确认详细信息，已为您转接人工客服协助完成预订。', true, 20);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (12, 5, 'travel_guide', '旅游攻略', '用户想了解景点推荐、旅游路线、当地特色', '["三亚有什么好玩的", "推荐一下北京景点", "云南旅游攻略", "西藏几月份去好"]', false, false, NULL, true, 30);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (13, 6, 'query_current_weather', '查询当前天气', '用户询问某城市当前天气状况，包含温度、湿度、风速、天气描述等实时信息', '["今天北京天气怎么样", "上海现在多少度", "广州天气", "深圳今天热不热", "现在武汉天气如何", "帮我查一下成都的天气"]', false, true, '抱歉，暂时无法获取天气信息，请稍后重试或访问天气应用查询。', true, 1);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (14, 6, 'query_weather_forecast', '查询天气预报', '用户询问某城市未来几天的天气预报，包含每日天气、温度区间、降水概率等', '["明天上海天气", "北京未来三天天气", "这周广州会下雨吗", "杭州周末天气怎么样", "成都明后天天气预报", "深圳本周天气"]', false, true, '抱歉，暂时无法获取天气预报，请稍后重试。', true, 2);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (15, 6, 'query_air_quality', '查询空气质量', '用户询问某城市的空气质量、AQI指数、PM2.5浓度、是否适合户外活动等', '["北京今天空气质量怎么样", "上海PM2.5多少", "今天适合出门跑步吗", "广州空气质量好吗", "深圳AQI是多少", "今天口罩要戴吗"]', false, true, '抱歉，暂时无法获取空气质量数据，请稍后重试。', true, 3);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (16, 6, 'travel_weather_advice', '出行天气建议', '用户询问某城市是否适合出行、旅游，或者询问某段时间内某地的天气是否适合特定活动', '["下周去北京旅游天气好吗", "去三亚度假天气怎么样", "明天开车去上海路上天气咋样", "这周末适合去爬山吗", "去杭州西湖游玩天气合适吗"]', false, false, '抱歉，暂时无法获取出行天气建议，请查看天气应用或联系客服。', true, 4);
INSERT INTO cs_conversation.cs_intent (id, domain_id, code, name, description, example_queries, auto_transfer, skip_rag, fallback_reply, enabled, sort_order) VALUES (17, 6, 'out_of_scope', '超出范围', '用户提问与天气无关，或需要人工处理的情况，自动转人工服务', '["帮我订机票", "我要投诉", "怎么退款", "人工客服", "找真人"]', true, true, '您的问题超出了天气助手的服务范围，正在为您转接人工客服...', true, 5);


--
-- Data for Name: cs_intent_slot; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (1, 1, 'order_id', 'string', '订单号，格式为ORD开头的字符串', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', NULL, 'list_orders', '{}', '请提供您要查询的订单号，可在购买确认短信中找到', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (2, 2, 'order_id', 'string', '需要退款的订单号', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', NULL, 'list_orders', '{}', '请提供需要退款的订单号', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (3, 6, 'account_id', 'string', '账户ID或银行卡号', true, '["SESSION"]', 'account_id', NULL, '{}', '请提供您的账户ID', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (4, 10, 'city', 'string', '目标城市名称', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问您要去哪个城市？', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (5, 10, 'check_in', 'date', '入住日期，格式 YYYY-MM-DD', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问您计划哪天入住？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (6, 10, 'check_out', 'date', '退房日期，格式 YYYY-MM-DD', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问哪天退房？', NULL, 2);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (7, 13, 'city', 'string', '需要查询天气的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的天气？例如：北京、上海、广州', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (8, 14, 'city', 'string', '需要查询天气预报的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的天气预报？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (9, 14, 'days', 'integer', '预报天数，1-3天', false, '["EXTRACT"]', NULL, NULL, '{}', NULL, '[1, 2, 3]', 2);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (10, 15, 'city', 'string', '需要查询空气质量的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的空气质量？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot (id, intent_id, slot_name, slot_type, description, required, resolve_strategy, session_key, discover_tool_code, discover_fixed_params, ask_user_prompt, enum_values, sort_order) VALUES (11, 16, 'city', 'string', '目的地城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您打算去哪个城市？我来帮您查询出行天气。', NULL, 1);


--
-- Data for Name: cs_tool; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (3, 'list_orders', '查询订单列表', '查询用户的订单列表，支持按状态过滤。isDiscoverTool=true，可作为槽位DISCOVER级发现工具', 'HTTP', 'GET', 'https://api.example.com/orders', '{}', NULL, '{"status": {"type": "string", "description": "订单状态（unpaid/shipped/completed）"}, "user_id": {"type": "string", "description": "用户ID"}}', '$.data.orders', 'NONE', '{}', 5000, true, true, '2026-07-05 00:13:54.474449', '2026-07-05 00:13:54.474449');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (4, 'get_order', '查询订单详情', '根据订单号获取订单详情，包含商品信息、金额、物流状态', 'HTTP', 'GET', 'https://api.example.com/orders/{order_id}', '{}', NULL, '{"order_id": {"type": "string", "required": true, "description": "订单号"}}', '$.data', 'NONE', '{}', 5000, false, true, '2026-07-05 00:13:54.487366', '2026-07-05 00:13:54.487366');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (5, 'create_refund', '创建退款申请', '为指定订单创建退款申请，需要订单号，退款原因可选。LLM决定是否调用', 'HTTP', 'POST', 'https://api.example.com/refunds', '{}', '{"reason": "{reason}", "order_id": "{order_id}"}', '{"reason": {"type": "string", "description": "退款原因"}, "order_id": {"type": "string", "required": true, "description": "订单号"}}', '$.data', 'NONE', '{}', 8000, false, true, '2026-07-05 00:13:54.495835', '2026-07-05 00:13:54.495835');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (6, 'get_balance', '查询账户余额', '查询指定账户的当前余额和可用额度', 'HTTP', 'GET', 'https://api.example.com/accounts/{account_id}/balance', '{}', NULL, '{"account_id": {"type": "string", "required": true, "description": "账户ID"}}', '$.data', 'NONE', '{}', 5000, false, true, '2026-07-05 00:13:54.504385', '2026-07-05 00:13:54.504385');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (7, 'search_hotel', '搜索酒店', '根据城市和入离店日期搜索可用酒店列表，返回房型和价格', 'HTTP', 'POST', 'https://api.example.com/hotels/search', '{}', '{"city": "{city}", "check_in": "{check_in}", "check_out": "{check_out}"}', '{"city": {"type": "string", "required": true, "description": "城市名称"}, "check_in": {"type": "string", "required": true, "description": "入住日期 YYYY-MM-DD"}, "check_out": {"type": "string", "required": true, "description": "退房日期 YYYY-MM-DD"}}', '$.data.hotels', 'NONE', '{}', 8000, false, true, '2026-07-05 00:13:54.511747', '2026-07-05 00:13:54.511747');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (8, 'geocoding_search', '城市名搜索', '根据城市名关键词搜索匹配的城市列表，返回城市名称、经纬度、国家等信息，用于槽位 DISCOVER 级候选发现', 'HTTP', 'GET', 'https://geocoding-api.open-meteo.com/v1/search?name={city_name}&count=5&language=zh&format=json', '{}', NULL, '{"city_name": {"type": "string", "required": true, "description": "城市名称关键词，如北京、上海、纽约"}}', '$.results[*].name', 'NONE', '{}', 5000, true, true, '2026-07-04 16:32:05.961021', '2026-07-04 16:32:05.961021');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (9, 'get_current_weather', '查询当前天气', '查询指定城市的实时天气，包含温度、体感温度、湿度、风速、风向、天气状况等信息。使用 wttr.in 开源免费 API，支持中英文城市名。', 'HTTP', 'GET', 'https://wttr.in/{city}?format=j1', '{"Accept": "application/json"}', NULL, '{"city": {"type": "string", "required": true, "description": "城市名称，支持中文（如：北京）或英文（如：Beijing）"}}', '$.current_condition[0]', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.973958', '2026-07-04 16:32:05.973958');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (10, 'get_weather_forecast', '查询天气预报', '查询指定城市未来3天的天气预报，包含每日最高/最低温度、降水概率、UV指数、日出日落时间等。使用 wttr.in 开源免费 API。', 'HTTP', 'GET', 'https://wttr.in/{city}?format=j1', '{"Accept": "application/json"}', NULL, '{"city": {"type": "string", "required": true, "description": "城市名称，支持中文或英文"}, "days": {"type": "integer", "default": 3, "required": false, "description": "预报天数，1-3天，默认3天"}}', '$.weather', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.977465', '2026-07-04 16:32:05.977465');
INSERT INTO cs_conversation.cs_tool (id, code, name, description, tool_type, http_method, url_template, headers_template, body_template, param_schema, response_jsonpath, auth_type, auth_config, timeout_ms, is_discover_tool, enabled, created_at, updated_at) VALUES (11, 'get_air_quality', '查询空气质量', '查询指定城市的实时空气质量指数（AQI），包含PM2.5、PM10、臭氧、一氧化碳等污染物浓度。使用 Open-Meteo Air Quality API，开源免费。需要先用 geocoding_search 获取城市经纬度。', 'HTTP', 'GET', 'https://air-quality-api.open-meteo.com/v1/air-quality?latitude={latitude}&longitude={longitude}&current=pm2_5,pm10,european_aqi,us_aqi,carbon_monoxide,ozone', '{}', NULL, '{"latitude": {"type": "number", "required": true, "description": "城市纬度（WGS84），如北京为 39.9042"}, "longitude": {"type": "number", "required": true, "description": "城市经度（WGS84），如北京为 116.4074"}}', '$.current', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.979324', '2026-07-04 16:32:05.979324');


--
-- Data for Name: cs_intent_tool; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (1, 1, 3, 'REQUIRED', 0, '{"user_id": {"key": "user_id", "source": "session"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (2, 1, 4, 'REQUIRED', 1, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (3, 2, 4, 'REQUIRED', 0, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (4, 2, 5, 'OPTIONAL', 1, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (5, 6, 6, 'REQUIRED', 0, '{"account_id": {"key": "account_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (6, 10, 7, 'REQUIRED', 0, '{"city": {"key": "city", "source": "slot"}, "check_in": {"key": "check_in", "source": "slot"}, "check_out": {"key": "check_out", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (7, 13, 9, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (8, 14, 10, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}, "days": {"key": "days", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (9, 15, 11, 'OPTIONAL', 1, '{"latitude": {"key": "geocoding.latitude", "source": "tool_result"}, "longitude": {"key": "geocoding.longitude", "source": "tool_result"}}');
INSERT INTO cs_conversation.cs_intent_tool (id, intent_id, tool_id, execution_mode, execution_order, param_mappings) VALUES (10, 16, 10, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}}');


--
-- Data for Name: cs_session_domain_switch; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--



--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: cs_conversation; Owner: -
--

INSERT INTO cs_conversation.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (0, NULL, '<< Flyway Schema Creation >>', 'SCHEMA', '"cs_conversation"', NULL, 'postgres', '2026-06-29 21:54:32.760068', 0, true);
INSERT INTO cs_conversation.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create conversation tables', 'SQL', 'V1__create_conversation_tables.sql', 1610156439, 'postgres', '2026-06-29 21:54:32.778165', 46, true);


--
-- Name: ai_model_config_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.ai_model_config_id_seq', 10, true);


--
-- Name: sys_dept_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_dept_id_seq', 1, false);


--
-- Name: sys_menu_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq', 902, true);


--
-- Name: sys_permission_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_permission_id_seq', 57, true);


--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_role_data_scope_id_seq', 36, true);


--
-- Name: sys_role_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_role_id_seq', 13, true);


--
-- Name: sys_user_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.sys_user_id_seq', 1003, true);


--
-- Name: system_config_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: -
--

SELECT pg_catalog.setval('cs_auth.system_config_id_seq', 13, true);


--
-- Name: cs_conversation_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_id_seq', 196, true);


--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_message_id_seq', 783, true);


--
-- Name: cs_domain_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_domain_id_seq', 6, true);


--
-- Name: cs_intent_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_id_seq', 17, true);


--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_slot_id_seq', 11, true);


--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_tool_id_seq', 10, true);


--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_session_domain_switch_id_seq', 33, true);


--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_tool_call_log_id_seq', 12, true);


--
-- Name: cs_tool_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: -
--

SELECT pg_catalog.setval('cs_conversation.cs_tool_id_seq', 11, true);


--
-- PostgreSQL database dump complete
--

\unrestrict LrTZ4YulIj3mfmuBYGXZLSfwgWCrZpLrHSfeBRQpl6T7eVly0yQuIp92RwsNLhL

-- ========================================
-- Module: knowledge-service (knowledge schema)
-- ========================================

--
-- PostgreSQL database dump
--

\restrict 28irL5ajR9fdsOwlv076IPwUd3l8i3APgt07l6QyWjwjzQFGJzMjiNIhghM2Lvl

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
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
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
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
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
-- Name: knowledge_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_chunk (
    id character varying(36) NOT NULL,
    doc_id character varying(36) NOT NULL,
    kb_id character varying(36) NOT NULL,
    doc_status character varying(20) DEFAULT 'PUBLISHED'::character varying NOT NULL,
    parent_chunk_id character varying(36),
    breadcrumb text,
    content text NOT NULL,
    content_vector public.vector(1024) NOT NULL,
    token_count integer NOT NULL,
    retrieval_weight numeric(3,2) DEFAULT 1.0 NOT NULL,
    feedback_downvotes integer DEFAULT 0 NOT NULL,
    hypothetical_questions jsonb,
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    page_num integer,
    section_title text,
    chunk_type character varying(20) DEFAULT 'TEXT'::character varying NOT NULL
);


--
-- Name: TABLE knowledge_chunk; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.knowledge_chunk IS 'Chunk 向量表，核心检索单元，使用 pgvector 存储 1024 维 embedding';


--
-- Name: COLUMN knowledge_chunk.kb_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.kb_id IS '冗余字段，避免检索时 JOIN knowledge_doc';


--
-- Name: COLUMN knowledge_chunk.doc_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.doc_status IS '冗余字段，随 knowledge_doc.status 同步';


--
-- Name: COLUMN knowledge_chunk.parent_chunk_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.parent_chunk_id IS 'Parent-Child 架构：检索用小 chunk，生成用父 chunk';


--
-- Name: COLUMN knowledge_chunk.content_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.content_vector IS 'BGE-M3 生成的 1024 维 embedding，pgvector 格式';


--
-- Name: COLUMN knowledge_chunk.retrieval_weight; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.retrieval_weight IS '检索权重 0~1.0，被踩多次时下调至 0 停止检索';


--
-- Name: COLUMN knowledge_chunk.page_num; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.page_num IS '来源页码（1-based），PDF 逐页提取时填充，非 PDF 文档为 NULL';


--
-- Name: COLUMN knowledge_chunk.section_title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.section_title IS '所属章节标题，从文档结构或标题行提取，无法检测时为 NULL';


--
-- Name: COLUMN knowledge_chunk.chunk_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_chunk.chunk_type IS 'Chunk 内容类型：TEXT / TABLE / IMAGE_CAPTION';


--
-- Name: knowledge_doc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_doc (
    id character varying(36) NOT NULL,
    kb_id character varying(36) NOT NULL,
    file_name character varying(255) NOT NULL,
    file_type character varying(20) NOT NULL,
    storage_path character varying(500) NOT NULL,
    content_hash character varying(64) DEFAULT 'pending'::character varying NOT NULL,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    version character varying(50),
    effective_from date,
    expires_at date,
    uploader_id character varying(36) NOT NULL,
    reviewer_id character varying(36),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE knowledge_doc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.knowledge_doc IS '知识库文档表，支持多格式文件';


--
-- Name: COLUMN knowledge_doc.content_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_doc.content_hash IS 'SHA-256(文件内容)，相同内容跳过重摄取';


--
-- Name: COLUMN knowledge_doc.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_doc.status IS 'DRAFT=草稿 / REVIEW=审核中 / PUBLISHED=已发布 / DEPRECATED=已下线 / FAILED=摄取失败';


--
-- Name: COLUMN knowledge_doc.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.knowledge_doc.expires_at IS '文档过期日期，NULL=永久有效；过期后定时任务自动下线';


--
-- Name: knowledge_kb; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.knowledge_kb (
    id character varying(36) NOT NULL,
    name character varying(100) NOT NULL,
    description text,
    owner_id character varying(36) NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: TABLE knowledge_kb; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.knowledge_kb IS '知识库表，一个知识库对应一类业务文档集合';


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: knowledge_chunk knowledge_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_pkey PRIMARY KEY (id);


--
-- Name: knowledge_doc knowledge_doc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_doc
    ADD CONSTRAINT knowledge_doc_pkey PRIMARY KEY (id);


--
-- Name: knowledge_kb knowledge_kb_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_kb
    ADD CONSTRAINT knowledge_kb_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_chunk_doc; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunk_doc ON public.knowledge_chunk USING btree (doc_id);


--
-- Name: idx_chunk_kb_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunk_kb_status ON public.knowledge_chunk USING btree (kb_id, doc_status, retrieval_weight) WHERE (((doc_status)::text = 'PUBLISHED'::text) AND (retrieval_weight > (0)::numeric));


--
-- Name: idx_chunk_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunk_parent ON public.knowledge_chunk USING btree (parent_chunk_id) WHERE (parent_chunk_id IS NOT NULL);


--
-- Name: idx_chunk_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunk_type ON public.knowledge_chunk USING btree (chunk_type) WHERE ((doc_status)::text = 'PUBLISHED'::text);


--
-- Name: idx_chunk_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chunk_vector ON public.knowledge_chunk USING hnsw (content_vector public.vector_cosine_ops) WITH (m='16', ef_construction='64');


--
-- Name: idx_doc_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_expires ON public.knowledge_doc USING btree (expires_at) WHERE ((expires_at IS NOT NULL) AND ((status)::text <> 'DEPRECATED'::text));


--
-- Name: idx_doc_hash; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_hash ON public.knowledge_doc USING btree (content_hash);


--
-- Name: idx_doc_kb_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_kb_status ON public.knowledge_doc USING btree (kb_id, status);


--
-- Name: idx_kb_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_owner ON public.knowledge_kb USING btree (owner_id);


--
-- Name: idx_kb_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_kb_status ON public.knowledge_kb USING btree (status);


--
-- Name: knowledge_doc trg_doc_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_doc_updated BEFORE UPDATE ON public.knowledge_doc FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: knowledge_kb trg_kb_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_kb_updated BEFORE UPDATE ON public.knowledge_kb FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: knowledge_chunk knowledge_chunk_doc_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_doc_id_fkey FOREIGN KEY (doc_id) REFERENCES public.knowledge_doc(id);


--
-- Name: knowledge_doc knowledge_doc_kb_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.knowledge_doc
    ADD CONSTRAINT knowledge_doc_kb_id_fkey FOREIGN KEY (kb_id) REFERENCES public.knowledge_kb(id);


--
-- PostgreSQL database dump complete
--

\unrestrict 28irL5ajR9fdsOwlv076IPwUd3l8i3APgt07l6QyWjwjzQFGJzMjiNIhghM2Lvl

-- ========================================
-- Module: knowledge-service (knowledge data)
-- ========================================

--
-- PostgreSQL database dump
--

\restrict xoP6aph7iSG4G4cCE6LqpHsItza5StrUHvoj7IFM82VHbhsZ7ZFHZBde9TE44u9

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
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'postgres', '2026-06-29 15:07:39.101062', 0, true);


--
-- Data for Name: knowledge_kb; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.knowledge_kb (id, name, description, owner_id, status, created_at, updated_at) VALUES ('default', '默认知识库', '通用产品知识库，包含 FAQ、产品手册、政策文档', 'system', 'active', '2026-06-29 07:04:09.98579+00', '2026-06-29 07:04:09.98579+00');
INSERT INTO public.knowledge_kb (id, name, description, owner_id, status, created_at, updated_at) VALUES ('faq', 'FAQ 知识库', '常见问题解答专用知识库', 'system', 'active', '2026-06-29 07:04:09.98579+00', '2026-06-29 07:04:09.98579+00');
INSERT INTO public.knowledge_kb (id, name, description, owner_id, status, created_at, updated_at) VALUES ('ticket', '历史工单库', '历史客服工单数据，用于提升召回率', 'system', 'active', '2026-06-29 07:04:09.98579+00', '2026-06-29 07:04:09.98579+00');


--
-- Data for Name: knowledge_doc; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: knowledge_chunk; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- PostgreSQL database dump complete
--

\unrestrict xoP6aph7iSG4G4cCE6LqpHsItza5StrUHvoj7IFM82VHbhsZ7ZFHZBde9TE44u9

-- =====================================================================
-- 存量数据修复：补全 cs_conversation.cs_conversation 空字段
--
-- 背景：accepted_at / first_reply_at / closed_by 为事后新增列，
--       历史会话记录这三列为 NULL；closed_by 在 closeBySessionId
--       之前也未写入。本脚本对存量数据做最优近似补全。
--
-- 执行前提：已完成 schema 变更（accepted_at / first_reply_at / closed_by 列存在）
-- 执行方式：psql -U postgres -d aria_cs -f backfill-conversation-fields.sql
-- 幂等性：所有 UPDATE 均加 IS NULL 条件，重复执行安全
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------
-- Step 1  first_reply_at
--         从消息表取首条 role='agent' 消息的时间，最精确。
--         未收到过 agent 消息的会话（纯 AI 对话）不更新，保持 NULL。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation c
SET    first_reply_at = sub.first_agent_at
FROM (
    SELECT session_id,
           MIN(created_at) AS first_agent_at
    FROM   cs_conversation.cs_conversation_message
    WHERE  role = 'agent'
    GROUP  BY session_id
) sub
WHERE  c.session_id    = sub.session_id
  AND  c.first_reply_at IS NULL;

-- ---------------------------------------------------------------
-- Step 2  accepted_at
--         WAITING/AI_CHAT 状态语义上不应有 accepted_at，跳过。
--         ACTIVE/CLOSED 会话：优先使用 first_reply_at（座席首回复
--         前必已接入，误差通常在秒级）；若该会话无 agent 消息则
--         退化为 started_at（等待时长统计会偏大，但优于 NULL）。
--
--         注意：Step 1 须先执行，保证本步能读到刚写入的 first_reply_at。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation
SET    accepted_at = COALESCE(first_reply_at, started_at)
WHERE  status      IN ('ACTIVE', 'CLOSED')
  AND  accepted_at IS NULL;

-- ---------------------------------------------------------------
-- Step 3  ended_at
--         closeBySessionId 明确将 updated_at 设为 ended_at，
--         因此对存量 CLOSED 会话用 updated_at 反向恢复语义等价。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation
SET    ended_at = updated_at
WHERE  status   = 'CLOSED'
  AND  ended_at IS NULL;

-- ---------------------------------------------------------------
-- Step 4  closed_by
--         历史数据无法还原关闭发起方，统一标记为 system。
-- ---------------------------------------------------------------
UPDATE cs_conversation.cs_conversation
SET    closed_by = 'system'
WHERE  status    = 'CLOSED'
  AND  closed_by IS NULL;

COMMIT;

-- ---------------------------------------------------------------
-- 验证（执行后应全部返回 0）
-- ---------------------------------------------------------------
SELECT 'accepted_at NULL (ACTIVE/CLOSED)' AS check_item,
       COUNT(*)                            AS remaining_nulls
FROM   cs_conversation.cs_conversation
WHERE  status IN ('ACTIVE', 'CLOSED')
  AND  accepted_at IS NULL
UNION ALL
SELECT 'first_reply_at NULL (has agent msg)',
       COUNT(*)
FROM   cs_conversation.cs_conversation c
WHERE  first_reply_at IS NULL
  AND  EXISTS (
      SELECT 1 FROM cs_conversation.cs_conversation_message m
      WHERE  m.session_id = c.session_id
        AND  m.role = 'agent'
  )
UNION ALL
SELECT 'ended_at NULL (CLOSED)',
       COUNT(*)
FROM   cs_conversation.cs_conversation
WHERE  status   = 'CLOSED'
  AND  ended_at IS NULL
UNION ALL
SELECT 'closed_by NULL (CLOSED)',
       COUNT(*)
FROM   cs_conversation.cs_conversation
WHERE  status   = 'CLOSED'
  AND  closed_by IS NULL;
