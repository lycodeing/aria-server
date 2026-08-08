-- ==========================================================================
-- aria-server 数据库初始化脚本（全量：结构 + 业务种子数据）
--
-- 覆盖两个库：
--   aria_cs        —— schema: cs_auth（认证/权限/系统配置）、cs_conversation（会话/意图/工具/SLA）
--   aria_knowledge —— schema: public（知识库/文档/切片，含 pg_jieba + vector 扩展）
--
-- 由 docker-entrypoint 首次初始化时对 POSTGRES_DB=aria_cs 执行；
-- 脚本内显式 CREATE DATABASE aria_knowledge 并 \connect 切换完成第二个库。
--
-- 种子数据说明：
--   * 仅含系统运行所需的基础配置（菜单/权限/角色/3个标准账号/域/意图/工具/SLA/预置标签/知识库）
--   * 已剔除全部测试(e2e_/autotest_/自动化)与运行时业务数据(会话/消息/评价/SLA违规等)
--   * 不含 ai_model_config（含真实厂商端点与密钥，须部署时经管理后台配置）
--
-- 标准账号（密码见部署文档，均为 bcrypt 存储）：
--   superadmin(1001) / kfmanager(1002) / kfstaff(1003)
-- ==========================================================================

-- ==========================================================================
-- 数据库 1：aria_cs （entrypoint 已创建并连接）
-- ==========================================================================

-- ---------- 结构（schema cs_auth / cs_conversation + 扩展 pgcrypto + 表/序列/约束/触发器） ----------
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
-- Name: cs_auth; Type: SCHEMA; Schema: -; Owner: postgres
--

CREATE SCHEMA cs_auth;


ALTER SCHEMA cs_auth OWNER TO postgres;

--
-- Name: cs_conversation; Type: SCHEMA; Schema: -; Owner: postgres
--

CREATE SCHEMA cs_conversation;


ALTER SCHEMA cs_conversation OWNER TO postgres;

--
-- Name: set_updated_at(); Type: FUNCTION; Schema: cs_auth; Owner: postgres
--

CREATE FUNCTION cs_auth.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


ALTER FUNCTION cs_auth.set_updated_at() OWNER TO postgres;

--
-- Name: set_update_time(); Type: FUNCTION; Schema: cs_conversation; Owner: postgres
--

CREATE FUNCTION cs_conversation.set_update_time() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.update_time = NOW();
    RETURN NEW;
END;
$$;


ALTER FUNCTION cs_conversation.set_update_time() OWNER TO postgres;

--
-- Name: set_updated_at(); Type: FUNCTION; Schema: cs_conversation; Owner: postgres
--

CREATE FUNCTION cs_conversation.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


ALTER FUNCTION cs_conversation.set_updated_at() OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ai_model_config; Type: TABLE; Schema: cs_auth; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone,
    model_type character varying(20) DEFAULT 'CHAT'::character varying NOT NULL,
    CONSTRAINT ai_model_config_model_type_check CHECK (((model_type)::text = ANY (ARRAY[('CHAT'::character varying)::text, ('EMBEDDING'::character varying)::text, ('ROUTER'::character varying)::text])))
);


ALTER TABLE cs_auth.ai_model_config OWNER TO postgres;

--
-- Name: TABLE ai_model_config; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.ai_model_config IS 'AI 模型配置表，支持多模型切换';


--
-- Name: COLUMN ai_model_config.api_key_enc; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.ai_model_config.api_key_enc IS 'API Key，格式：PLAINTEXT:{raw}（开发）或 AES:{base64}（生产）';


--
-- Name: COLUMN ai_model_config.is_default; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.ai_model_config.is_default IS '是否为默认模型，系统同时只有一个 is_default=true';


--
-- Name: COLUMN ai_model_config.model_type; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.ai_model_config.model_type IS 'CHAT=对话大模型, EMBEDDING=向量模型, ROUTER=域路由小模型';


--
-- Name: ai_model_config_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.ai_model_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.ai_model_config_id_seq OWNER TO postgres;

--
-- Name: ai_model_config_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.ai_model_config_id_seq OWNED BY cs_auth.ai_model_config.id;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: cs_auth; Owner: postgres
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


ALTER TABLE cs_auth.flyway_schema_history OWNER TO postgres;

--
-- Name: sys_dept; Type: TABLE; Schema: cs_auth; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_dept OWNER TO postgres;

--
-- Name: TABLE sys_dept; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.sys_dept IS '部门树，用于数据权限的组织维度过滤';


--
-- Name: COLUMN sys_dept.ancestor_ids; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.sys_dept.ancestor_ids IS '祖先ID路径，格式：0,parentId,...,deptId';


--
-- Name: sys_dept_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.sys_dept_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.sys_dept_id_seq OWNER TO postgres;

--
-- Name: sys_dept_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.sys_dept_id_seq OWNED BY cs_auth.sys_dept.id;


--
-- Name: sys_menu; Type: TABLE; Schema: cs_auth; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_menu OWNER TO postgres;

--
-- Name: TABLE sys_menu; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.sys_menu IS '菜单与按钮权限表，支持多级树状结构';


--
-- Name: COLUMN sys_menu.menu_type; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.sys_menu.menu_type IS '菜单类型：DIRECTORY=目录，MENU=菜单页面，BUTTON=按钮/接口';


--
-- Name: COLUMN sys_menu.permission_key; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.sys_menu.permission_key IS 'BUTTON类型对应的接口权限标识';


--
-- Name: sys_menu_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.sys_menu_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.sys_menu_id_seq OWNER TO postgres;

--
-- Name: sys_menu_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.sys_menu_id_seq OWNED BY cs_auth.sys_menu.id;


--
-- Name: sys_permission; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_permission (
    id bigint NOT NULL,
    permission_key character varying(100) NOT NULL,
    permission_name character varying(200) NOT NULL,
    module character varying(50) NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_permission OWNER TO postgres;

--
-- Name: sys_permission_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.sys_permission_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.sys_permission_id_seq OWNER TO postgres;

--
-- Name: sys_permission_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.sys_permission_id_seq OWNED BY cs_auth.sys_permission.id;


--
-- Name: sys_role; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_role (
    id bigint NOT NULL,
    role_key character varying(50) NOT NULL,
    role_name character varying(100) NOT NULL,
    description text,
    is_system boolean DEFAULT false NOT NULL,
    status character varying(20) DEFAULT 'active'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_role OWNER TO postgres;

--
-- Name: TABLE sys_role; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.sys_role IS '角色表';


--
-- Name: sys_role_data_scope; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_role_data_scope (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    scope_type character varying(30) DEFAULT 'SELF'::character varying NOT NULL,
    custom_dept_ids jsonb DEFAULT '[]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_role_data_scope OWNER TO postgres;

--
-- Name: TABLE sys_role_data_scope; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.sys_role_data_scope IS '角色数据权限范围：ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF';


--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.sys_role_data_scope_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.sys_role_data_scope_id_seq OWNER TO postgres;

--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.sys_role_data_scope_id_seq OWNED BY cs_auth.sys_role_data_scope.id;


--
-- Name: sys_role_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.sys_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.sys_role_id_seq OWNER TO postgres;

--
-- Name: sys_role_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.sys_role_id_seq OWNED BY cs_auth.sys_role.id;


--
-- Name: sys_role_menu; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_role_menu (
    role_id bigint NOT NULL,
    menu_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_role_menu OWNER TO postgres;

--
-- Name: sys_role_permission; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_role_permission (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


ALTER TABLE cs_auth.sys_role_permission OWNER TO postgres;

--
-- Name: sys_user; Type: TABLE; Schema: cs_auth; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_user OWNER TO postgres;

--
-- Name: TABLE sys_user; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.sys_user IS '用户表';


--
-- Name: COLUMN sys_user.password_hash; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.sys_user.password_hash IS 'BCrypt(cost=10) 密码哈希';


--
-- Name: COLUMN sys_user.locked_until; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.sys_user.locked_until IS '锁定截止时间，NULL=未锁定';


--
-- Name: COLUMN sys_user.password_history; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.sys_user.password_history IS '最近5次密码哈希，防重用';


--
-- Name: sys_user_dept; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_user_dept (
    user_id bigint NOT NULL,
    dept_id bigint NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_auth.sys_user_dept OWNER TO postgres;

--
-- Name: sys_user_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.sys_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.sys_user_id_seq OWNER TO postgres;

--
-- Name: sys_user_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.sys_user_id_seq OWNED BY cs_auth.sys_user.id;


--
-- Name: sys_user_role; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.sys_user_role (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL,
    granted_at timestamp without time zone DEFAULT now() NOT NULL,
    granted_by bigint
);


ALTER TABLE cs_auth.sys_user_role OWNER TO postgres;

--
-- Name: system_config; Type: TABLE; Schema: cs_auth; Owner: postgres
--

CREATE TABLE cs_auth.system_config (
    id bigint NOT NULL,
    config_key character varying(100) NOT NULL,
    config_value text NOT NULL,
    config_type character varying(50) DEFAULT 'SYSTEM'::character varying NOT NULL,
    description character varying(255) DEFAULT ''::character varying NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp without time zone
);


ALTER TABLE cs_auth.system_config OWNER TO postgres;

--
-- Name: TABLE system_config; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON TABLE cs_auth.system_config IS '系统配置表';


--
-- Name: COLUMN system_config.config_key; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.system_config.config_key IS '配置键，全局唯一（未删除）';


--
-- Name: COLUMN system_config.config_value; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.system_config.config_value IS '配置值，统一字符串存储';


--
-- Name: COLUMN system_config.config_type; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.system_config.config_type IS '配置类型：SYSTEM | CUSTOMER_SERVICE';


--
-- Name: COLUMN system_config.is_enabled; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.system_config.is_enabled IS '是否启用，禁用时业务层回退硬编码默认值';


--
-- Name: COLUMN system_config.deleted_at; Type: COMMENT; Schema: cs_auth; Owner: postgres
--

COMMENT ON COLUMN cs_auth.system_config.deleted_at IS '软删除时间，NULL 表示未删除';


--
-- Name: system_config_id_seq; Type: SEQUENCE; Schema: cs_auth; Owner: postgres
--

CREATE SEQUENCE cs_auth.system_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_auth.system_config_id_seq OWNER TO postgres;

--
-- Name: system_config_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_auth; Owner: postgres
--

ALTER SEQUENCE cs_auth.system_config_id_seq OWNED BY cs_auth.system_config.id;


--
-- Name: cs_business_hours_holiday; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_business_hours_holiday (
    id bigint NOT NULL,
    date date NOT NULL,
    type character varying(10) NOT NULL,
    time_ranges jsonb,
    remark character varying(100),
    source character varying(10) DEFAULT 'MANUAL'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_business_hours_holiday OWNER TO postgres;

--
-- Name: cs_business_hours_holiday_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_business_hours_holiday_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_business_hours_holiday_id_seq OWNER TO postgres;

--
-- Name: cs_business_hours_holiday_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_business_hours_holiday_id_seq OWNED BY cs_conversation.cs_business_hours_holiday.id;


--
-- Name: cs_business_hours_schedule; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_business_hours_schedule (
    day_of_week smallint NOT NULL,
    is_open boolean DEFAULT true NOT NULL,
    time_ranges jsonb NOT NULL,
    timezone character varying(50) DEFAULT 'Asia/Shanghai'::character varying NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_business_hours_schedule OWNER TO postgres;

--
-- Name: cs_canned_response; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_canned_response (
    id bigint NOT NULL,
    group_id bigint,
    title character varying(128) NOT NULL,
    content text NOT NULL,
    scope character varying(16) DEFAULT 'PUBLIC'::character varying NOT NULL,
    owner_id bigint,
    use_count integer DEFAULT 0 NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


ALTER TABLE cs_conversation.cs_canned_response OWNER TO postgres;

--
-- Name: TABLE cs_canned_response; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_canned_response IS '快捷回复模板';


--
-- Name: COLUMN cs_canned_response.scope; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_canned_response.scope IS 'PUBLIC=公共, PRIVATE=个人';


--
-- Name: COLUMN cs_canned_response.use_count; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_canned_response.use_count IS '使用次数，用于搜索排序';


--
-- Name: cs_canned_response_group; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_canned_response_group (
    id bigint NOT NULL,
    name character varying(64) NOT NULL,
    parent_id bigint,
    sort_order integer DEFAULT 0 NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted boolean DEFAULT false NOT NULL
);


ALTER TABLE cs_conversation.cs_canned_response_group OWNER TO postgres;

--
-- Name: TABLE cs_canned_response_group; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_canned_response_group IS '快捷回复分组';


--
-- Name: cs_canned_response_group_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_canned_response_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_canned_response_group_id_seq OWNER TO postgres;

--
-- Name: cs_canned_response_group_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_canned_response_group_id_seq OWNED BY cs_conversation.cs_canned_response_group.id;


--
-- Name: cs_canned_response_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_canned_response_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_canned_response_id_seq OWNER TO postgres;

--
-- Name: cs_canned_response_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_canned_response_id_seq OWNED BY cs_conversation.cs_canned_response.id;


--
-- Name: cs_conversation; Type: TABLE; Schema: cs_conversation; Owner: postgres
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
    visitor_id character varying(64) DEFAULT NULL::character varying,
    visitor_ip character varying(45) DEFAULT NULL::character varying,
    visitor_device character varying(500) DEFAULT NULL::character varying,
    agent_name character varying(100) DEFAULT NULL::character varying
);


ALTER TABLE cs_conversation.cs_conversation OWNER TO postgres;

--
-- Name: TABLE cs_conversation; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_conversation IS '客服会话生命周期记录表';


--
-- Name: COLUMN cs_conversation.session_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.session_id IS '前端唯一会话 ID，与 Redis chat:session:{id} 对应';


--
-- Name: COLUMN cs_conversation.transfer_reason; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.transfer_reason IS '转接人工的原因描述';


--
-- Name: COLUMN cs_conversation.status; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.status IS '会话状态：WAITING=等待接入, ACTIVE=接待中, CLOSED=已结束';


--
-- Name: COLUMN cs_conversation.agent_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.agent_id IS '接入座席 ID，WAITING 时为 NULL，ACTIVE 后填入';


--
-- Name: COLUMN cs_conversation.visitor_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_id IS '访客唯一标识，前端 localStorage 生成的 anonymousId';


--
-- Name: COLUMN cs_conversation.visitor_ip; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_ip IS '访客 IP，取 X-Forwarded-For 首个地址或直连 RemoteAddr，支持 IPv4/IPv6';


--
-- Name: COLUMN cs_conversation.visitor_device; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.visitor_device IS '访客设备信息，原始 User-Agent 字符串';


--
-- Name: COLUMN cs_conversation.agent_name; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation.agent_name IS '接入座席显示名称快照（接入/转交时冻结，反映当时接待人，不随客服改名/离职变化）';


--
-- Name: cs_conversation_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_conversation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_conversation_id_seq OWNER TO postgres;

--
-- Name: cs_conversation_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_conversation_id_seq OWNED BY cs_conversation.cs_conversation.id;


--
-- Name: cs_conversation_message; Type: TABLE; Schema: cs_conversation; Owner: postgres
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


ALTER TABLE cs_conversation.cs_conversation_message OWNER TO postgres;

--
-- Name: TABLE cs_conversation_message; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_conversation_message IS '对话消息明细表，由 Redis Stream 异步写入';


--
-- Name: COLUMN cs_conversation_message.session_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.session_id IS '冗余字段，避免查询历史时 JOIN cs_conversation';


--
-- Name: COLUMN cs_conversation_message.role; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.role IS '消息角色：user=访客, assistant=AI, agent=人工座席';


--
-- Name: COLUMN cs_conversation_message.seq; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.seq IS 'session 内单调递增序号，支持断线重连增量拉取；历史消息为 NULL';


--
-- Name: COLUMN cs_conversation_message.tool_calls_json; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_calls_json IS 'assistant 触发的 tool_calls JSON 数组：[{"id":"...","name":"...","arguments":"..."}]。仅 role=assistant 且模型返回 tool_calls 时非空';


--
-- Name: COLUMN cs_conversation_message.tool_request_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_request_id IS 'LangChain4j ToolExecutionRequest ID，role=tool 时填充，用于关联工具调用上下文';


--
-- Name: COLUMN cs_conversation_message.tool_name; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_conversation_message.tool_name IS '工具名称，role=tool 时填充';


--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_conversation_message_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_conversation_message_id_seq OWNER TO postgres;

--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_conversation_message_id_seq OWNED BY cs_conversation.cs_conversation_message.id;


--
-- Name: cs_conversation_note; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_conversation_note (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    content text NOT NULL,
    created_by character varying(64),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_conversation_note OWNER TO postgres;

--
-- Name: cs_conversation_note_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_conversation_note_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_conversation_note_id_seq OWNER TO postgres;

--
-- Name: cs_conversation_note_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_conversation_note_id_seq OWNED BY cs_conversation.cs_conversation_note.id;


--
-- Name: cs_conversation_tag; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_conversation_tag (
    session_id character varying(64) NOT NULL,
    tag_id bigint NOT NULL,
    tagged_by character varying(64),
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_conversation_tag OWNER TO postgres;

--
-- Name: cs_csat_rating; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_csat_rating (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    visitor_id character varying(64),
    agent_id bigint,
    score smallint,
    comment text,
    channel character varying(20) DEFAULT 'AI'::character varying NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    rated_at timestamp with time zone,
    expired_at timestamp with time zone NOT NULL,
    CONSTRAINT cs_csat_rating_score_check CHECK (((score >= 1) AND (score <= 5)))
);


ALTER TABLE cs_conversation.cs_csat_rating OWNER TO postgres;

--
-- Name: TABLE cs_csat_rating; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_csat_rating IS '会话满意度评价';


--
-- Name: COLUMN cs_csat_rating.channel; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_csat_rating.channel IS 'AI=AI对话, HUMAN=人工接待';


--
-- Name: COLUMN cs_csat_rating.status; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_csat_rating.status IS 'PENDING/RATED/EXPIRED/SKIPPED';


--
-- Name: cs_csat_rating_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_csat_rating_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_csat_rating_id_seq OWNER TO postgres;

--
-- Name: cs_csat_rating_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_csat_rating_id_seq OWNED BY cs_conversation.cs_csat_rating.id;


--
-- Name: cs_domain; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_domain (
    id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    description text,
    system_prompt_addon text,
    knowledge_base_id bigint,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    keywords jsonb DEFAULT '[]'::jsonb NOT NULL,
    patterns jsonb DEFAULT '[]'::jsonb NOT NULL
);


ALTER TABLE cs_conversation.cs_domain OWNER TO postgres;

--
-- Name: TABLE cs_domain; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_domain IS '领域/场景配置表';


--
-- Name: COLUMN cs_domain.code; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_domain.code IS '前端传入的领域标识，如 ecommerce';


--
-- Name: COLUMN cs_domain.system_prompt_addon; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_domain.system_prompt_addon IS '追加到 system prompt 的领域专属说明';


--
-- Name: COLUMN cs_domain.keywords; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_domain.keywords IS '域路由关键词列表，命中则直接路由到该域，跳过 LLM';


--
-- Name: COLUMN cs_domain.patterns; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_domain.patterns IS '域路由正则列表，命中则直接路由到该域，跳过 LLM';


--
-- Name: cs_domain_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_domain_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_domain_id_seq OWNER TO postgres;

--
-- Name: cs_domain_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_domain_id_seq OWNED BY cs_conversation.cs_domain.id;


--
-- Name: cs_intent; Type: TABLE; Schema: cs_conversation; Owner: postgres
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


ALTER TABLE cs_conversation.cs_intent OWNER TO postgres;

--
-- Name: TABLE cs_intent; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_intent IS '意图定义表';


--
-- Name: COLUMN cs_intent.example_queries; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent.example_queries IS '少样本示例，JSON 数组，如 ["查订单","我的包裹到哪了"]';


--
-- Name: COLUMN cs_intent.auto_transfer; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent.auto_transfer IS 'true=命中后自动转人工（投诉/敏感操作）';


--
-- Name: COLUMN cs_intent.skip_rag; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent.skip_rag IS 'true=跳过 RAG 检索';


--
-- Name: COLUMN cs_intent.keywords; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent.keywords IS '关键词列表，JSON 字符串数组，大小写不敏感全文包含匹配，如 ["转人工","找真人"]';


--
-- Name: COLUMN cs_intent.patterns; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent.patterns IS '正则表达式列表，Java Pattern 语法，DOTALL|CASE_INSENSITIVE，如 ["^我要.*转.*人工"]';


--
-- Name: cs_intent_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_intent_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_intent_id_seq OWNER TO postgres;

--
-- Name: cs_intent_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_intent_id_seq OWNED BY cs_conversation.cs_intent.id;


--
-- Name: cs_intent_slot; Type: TABLE; Schema: cs_conversation; Owner: postgres
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


ALTER TABLE cs_conversation.cs_intent_slot OWNER TO postgres;

--
-- Name: COLUMN cs_intent_slot.resolve_strategy; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent_slot.resolve_strategy IS '解析策略优先级，JSON 数组，按顺序尝试';


--
-- Name: COLUMN cs_intent_slot.discover_tool_code; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent_slot.discover_tool_code IS 'DISCOVER 级使用的发现工具 code';


--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_intent_slot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_intent_slot_id_seq OWNER TO postgres;

--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_intent_slot_id_seq OWNED BY cs_conversation.cs_intent_slot.id;


--
-- Name: cs_intent_tier_stat; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_intent_tier_stat (
    id bigint NOT NULL,
    domain_code character varying(64),
    reached_tier character varying(20) NOT NULL,
    tier1_hit boolean DEFAULT false NOT NULL,
    tier2_executed boolean DEFAULT false NOT NULL,
    tier2_hit boolean DEFAULT false NOT NULL,
    tier3_executed boolean DEFAULT false NOT NULL,
    tier3_hit boolean DEFAULT false NOT NULL,
    tier1_latency_ms integer,
    tier2_latency_ms integer,
    tier3_latency_ms integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_intent_tier_stat OWNER TO postgres;

--
-- Name: TABLE cs_intent_tier_stat; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_intent_tier_stat IS 'DIT 三层意图分类单次明细，供 Admin 命中率/延迟报表聚合';


--
-- Name: cs_intent_tier_stat_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_intent_tier_stat_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_intent_tier_stat_id_seq OWNER TO postgres;

--
-- Name: cs_intent_tier_stat_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_intent_tier_stat_id_seq OWNED BY cs_conversation.cs_intent_tier_stat.id;


--
-- Name: cs_intent_tool; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_intent_tool (
    id bigint NOT NULL,
    intent_id bigint NOT NULL,
    tool_id bigint NOT NULL,
    execution_mode character varying(16) DEFAULT 'OPTIONAL'::character varying NOT NULL,
    execution_order integer DEFAULT 0 NOT NULL,
    param_mappings jsonb DEFAULT '{}'::jsonb NOT NULL
);


ALTER TABLE cs_conversation.cs_intent_tool OWNER TO postgres;

--
-- Name: COLUMN cs_intent_tool.execution_mode; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent_tool.execution_mode IS 'REQUIRED=系统立即执行, OPTIONAL=交给 LLM 决策';


--
-- Name: COLUMN cs_intent_tool.param_mappings; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_intent_tool.param_mappings IS '参数来源映射，JSON，key=工具参数名，value={source,key}';


--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_intent_tool_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_intent_tool_id_seq OWNER TO postgres;

--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_intent_tool_id_seq OWNED BY cs_conversation.cs_intent_tool.id;


--
-- Name: cs_llm_cost_log; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_llm_cost_log (
    id bigint NOT NULL,
    session_id character varying(64),
    model_name character varying(128) NOT NULL,
    call_type character varying(32) NOT NULL,
    input_tokens integer DEFAULT 0 NOT NULL,
    output_tokens integer DEFAULT 0 NOT NULL,
    total_tokens integer DEFAULT 0 NOT NULL,
    latency_ms integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_llm_cost_log OWNER TO postgres;

--
-- Name: TABLE cs_llm_cost_log; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_llm_cost_log IS 'P0-D LLM Token 消耗日志，管理台成本报表数据源';


--
-- Name: COLUMN cs_llm_cost_log.session_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.session_id IS '会话 ID，可为 null（意图分类等系统级调用无会话上下文）';


--
-- Name: COLUMN cs_llm_cost_log.model_name; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.model_name IS '实际调用的模型名称（取自活跃 AiModelConfig.modelName）';


--
-- Name: COLUMN cs_llm_cost_log.call_type; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.call_type IS '调用类型：CHAT | INTENT_CLASSIFY';


--
-- Name: COLUMN cs_llm_cost_log.latency_ms; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_llm_cost_log.latency_ms IS '本次调用耗时（毫秒）';


--
-- Name: cs_llm_cost_log_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_llm_cost_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_llm_cost_log_id_seq OWNER TO postgres;

--
-- Name: cs_llm_cost_log_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_llm_cost_log_id_seq OWNED BY cs_conversation.cs_llm_cost_log.id;


--
-- Name: cs_message_feedback; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_message_feedback (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    seq bigint NOT NULL,
    feedback character varying(8) NOT NULL,
    visitor_id character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_msg_feedback_value CHECK (((feedback)::text = ANY ((ARRAY['up'::character varying, 'down'::character varying])::text[])))
);


ALTER TABLE cs_conversation.cs_message_feedback OWNER TO postgres;

--
-- Name: TABLE cs_message_feedback; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_message_feedback IS '访客对单条消息的反馈（up/down），(session_id, seq) 唯一';


--
-- Name: COLUMN cs_message_feedback.seq; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_message_feedback.seq IS '对应 cs_conversation_message.seq，允许历史消息（seq 非空）被反馈';


--
-- Name: COLUMN cs_message_feedback.feedback; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_message_feedback.feedback IS '反馈类型：up=点赞, down=点踩；取消反馈则删除该行';


--
-- Name: cs_message_feedback_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_message_feedback_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_message_feedback_id_seq OWNER TO postgres;

--
-- Name: cs_message_feedback_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_message_feedback_id_seq OWNED BY cs_conversation.cs_message_feedback.id;


--
-- Name: cs_pending_slot; Type: TABLE; Schema: cs_conversation; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_pending_slot OWNER TO postgres;

--
-- Name: TABLE cs_pending_slot; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_pending_slot IS '槽位解析挂起状态，用于多轮对话中间状态恢复';


--
-- Name: COLUMN cs_pending_slot.pending_type; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_pending_slot.pending_type IS 'DISCOVERED=候选项待选, MISSING=等待用户输入';


--
-- Name: cs_rag_miss_log; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_rag_miss_log (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    query_text text NOT NULL,
    top1_score double precision,
    hit_count integer DEFAULT 0 NOT NULL,
    is_miss boolean DEFAULT false NOT NULL,
    source character varying(20),
    domain_code character varying(64),
    intent_codes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_rag_miss_log OWNER TO postgres;

--
-- Name: TABLE cs_rag_miss_log; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_rag_miss_log IS 'RAG 检索质量快照：每次 FAQ 链路检索后异步写一行，驱动知识库覆盖度分析';


--
-- Name: cs_rag_miss_log_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_rag_miss_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_rag_miss_log_id_seq OWNER TO postgres;

--
-- Name: cs_rag_miss_log_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_rag_miss_log_id_seq OWNED BY cs_conversation.cs_rag_miss_log.id;


--
-- Name: cs_session_domain_switch; Type: TABLE; Schema: cs_conversation; Owner: postgres
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


ALTER TABLE cs_conversation.cs_session_domain_switch OWNER TO postgres;

--
-- Name: TABLE cs_session_domain_switch; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_session_domain_switch IS '会话域切换记录表';


--
-- Name: COLUMN cs_session_domain_switch.session_id; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.session_id IS '会话 ID';


--
-- Name: COLUMN cs_session_domain_switch.from_domain; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.from_domain IS '切换前的域 code，首次进入时为 NULL';


--
-- Name: COLUMN cs_session_domain_switch.to_domain; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.to_domain IS '切换后的域 code';


--
-- Name: COLUMN cs_session_domain_switch.switch_type; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.switch_type IS 'INITIAL / ROUTER_MODEL / LLM_TOOL / USER_SELECTED';


--
-- Name: COLUMN cs_session_domain_switch.trigger_message; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.trigger_message IS '触发切换的用户消息原文';


--
-- Name: COLUMN cs_session_domain_switch.reason; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.reason IS '切换原因描述';


--
-- Name: COLUMN cs_session_domain_switch.msg_seq; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_domain_switch.msg_seq IS '关联 cs_conversation_message.seq';


--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_session_domain_switch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_session_domain_switch_id_seq OWNER TO postgres;

--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_session_domain_switch_id_seq OWNED BY cs_conversation.cs_session_domain_switch.id;


--
-- Name: cs_session_feedback; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_session_feedback (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    message_id character varying(64),
    feedback_type character varying(20) NOT NULL,
    original_query text NOT NULL,
    correct_intent character varying(64),
    correct_answer text,
    agent_id bigint,
    accumulated boolean DEFAULT false NOT NULL,
    kb_queued boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_session_feedback OWNER TO postgres;

--
-- Name: TABLE cs_session_feedback; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_session_feedback IS '坐席对 AI 回答的纠错/点赞反馈';


--
-- Name: COLUMN cs_session_feedback.feedback_type; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_feedback.feedback_type IS 'WRONG_INTENT（意图错，回写样本库）| WRONG_ANSWER（回答错，待 KB 审核）| GOOD（点赞，仅计数）';


--
-- Name: COLUMN cs_session_feedback.accumulated; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_feedback.accumulated IS '是否已写入 intent_example_vectors';


--
-- Name: COLUMN cs_session_feedback.kb_queued; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_session_feedback.kb_queued IS '是否已推入 KB 审核队列（当前恒为 false，KB 审核队列 P1 迭代补全后启用）';


--
-- Name: cs_session_feedback_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_session_feedback_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_session_feedback_id_seq OWNER TO postgres;

--
-- Name: cs_session_feedback_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_session_feedback_id_seq OWNED BY cs_conversation.cs_session_feedback.id;


--
-- Name: cs_sla_breach; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_sla_breach (
    id bigint NOT NULL,
    session_id character varying(64) NOT NULL,
    policy_id bigint NOT NULL,
    breach_type character varying(10) NOT NULL,
    stage character varying(10) DEFAULT 'BREACH'::character varying NOT NULL,
    target_sec integer NOT NULL,
    warn_at_sec integer NOT NULL,
    actual_sec integer NOT NULL,
    breach_at timestamp without time zone NOT NULL,
    alerted_at timestamp without time zone,
    escalated_at timestamp without time zone,
    webhook_notified_at timestamp without time zone,
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_sla_breach OWNER TO postgres;

--
-- Name: cs_sla_breach_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_sla_breach_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_sla_breach_id_seq OWNER TO postgres;

--
-- Name: cs_sla_breach_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_sla_breach_id_seq OWNED BY cs_conversation.cs_sla_breach.id;


--
-- Name: cs_sla_policy; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_sla_policy (
    id bigint NOT NULL,
    name character varying(50) NOT NULL,
    is_enabled boolean DEFAULT true NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    match_visitor_tags jsonb,
    match_transfer_tags jsonb,
    time_mode character varying(15) DEFAULT 'CALENDAR'::character varying NOT NULL,
    wait_time_target_sec integer DEFAULT 120 NOT NULL,
    frt_target_sec integer DEFAULT 60 NOT NULL,
    handle_time_target_sec integer DEFAULT 1800 NOT NULL,
    warning_threshold_pct smallint DEFAULT 80 NOT NULL,
    actions jsonb NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_sla_policy OWNER TO postgres;

--
-- Name: cs_sla_policy_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_sla_policy_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_sla_policy_id_seq OWNER TO postgres;

--
-- Name: cs_sla_policy_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_sla_policy_id_seq OWNED BY cs_conversation.cs_sla_policy.id;


--
-- Name: cs_tag; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_tag (
    id bigint NOT NULL,
    name character varying(50) NOT NULL,
    color character varying(7) DEFAULT '#6B7280'::character varying NOT NULL,
    source character varying(10) DEFAULT 'PRESET'::character varying NOT NULL,
    usage_count integer DEFAULT 0 NOT NULL,
    created_by character varying(64),
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_tag OWNER TO postgres;

--
-- Name: cs_tag_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_tag_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_tag_id_seq OWNER TO postgres;

--
-- Name: cs_tag_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_tag_id_seq OWNED BY cs_conversation.cs_tag.id;


--
-- Name: cs_tool; Type: TABLE; Schema: cs_conversation; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_tool OWNER TO postgres;

--
-- Name: TABLE cs_tool; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_tool IS '工具注册表（HTTP 调用或内置 Java 实现）';


--
-- Name: COLUMN cs_tool.tool_type; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_tool.tool_type IS 'HTTP=通用 HTTP 调用, BUILTIN=Java 内置实现';


--
-- Name: COLUMN cs_tool.url_template; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_tool.url_template IS 'URL 模板，支持 {slot_name} 路径参数';


--
-- Name: COLUMN cs_tool.param_schema; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_tool.param_schema IS '参数 JSON Schema，供 LLM Function Calling 使用';


--
-- Name: COLUMN cs_tool.is_discover_tool; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_tool.is_discover_tool IS 'true=可作为槽位 DISCOVER 级发现工具';


--
-- Name: cs_tool_call_log; Type: TABLE; Schema: cs_conversation; Owner: postgres
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
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_tool_call_log OWNER TO postgres;

--
-- Name: TABLE cs_tool_call_log; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON TABLE cs_conversation.cs_tool_call_log IS '工具调用日志（调试+监控）';


--
-- Name: COLUMN cs_tool_call_log.status; Type: COMMENT; Schema: cs_conversation; Owner: postgres
--

COMMENT ON COLUMN cs_conversation.cs_tool_call_log.status IS 'SUCCESS/ERROR/TIMEOUT/SKIPPED';


--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_tool_call_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_tool_call_log_id_seq OWNER TO postgres;

--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_tool_call_log_id_seq OWNED BY cs_conversation.cs_tool_call_log.id;


--
-- Name: cs_tool_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_tool_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_tool_id_seq OWNER TO postgres;

--
-- Name: cs_tool_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_tool_id_seq OWNED BY cs_conversation.cs_tool.id;


--
-- Name: cs_visitor_tag; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_visitor_tag (
    visitor_id character varying(64) NOT NULL,
    tag_id bigint NOT NULL,
    tagged_by character varying(64),
    create_time timestamp without time zone DEFAULT now() NOT NULL
);


ALTER TABLE cs_conversation.cs_visitor_tag OWNER TO postgres;

--
-- Name: cs_webhook_config; Type: TABLE; Schema: cs_conversation; Owner: postgres
--

CREATE TABLE cs_conversation.cs_webhook_config (
    id bigint NOT NULL,
    name character varying(50) NOT NULL,
    type character varying(10) NOT NULL,
    url character varying(500) NOT NULL,
    secret character varying(200),
    custom_headers jsonb,
    message_template text,
    is_enabled smallint DEFAULT 1 NOT NULL,
    create_time timestamp without time zone DEFAULT now() NOT NULL,
    update_time timestamp without time zone DEFAULT now() NOT NULL,
    scopes jsonb DEFAULT '["SLA_BREACH"]'::jsonb NOT NULL
);


ALTER TABLE cs_conversation.cs_webhook_config OWNER TO postgres;

--
-- Name: cs_webhook_config_id_seq; Type: SEQUENCE; Schema: cs_conversation; Owner: postgres
--

CREATE SEQUENCE cs_conversation.cs_webhook_config_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE cs_conversation.cs_webhook_config_id_seq OWNER TO postgres;

--
-- Name: cs_webhook_config_id_seq; Type: SEQUENCE OWNED BY; Schema: cs_conversation; Owner: postgres
--

ALTER SEQUENCE cs_conversation.cs_webhook_config_id_seq OWNED BY cs_conversation.cs_webhook_config.id;


--
-- Name: flyway_schema_history; Type: TABLE; Schema: cs_conversation; Owner: postgres
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


ALTER TABLE cs_conversation.flyway_schema_history OWNER TO postgres;

--
-- Name: ai_model_config id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.ai_model_config ALTER COLUMN id SET DEFAULT nextval('cs_auth.ai_model_config_id_seq'::regclass);


--
-- Name: sys_dept id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_dept ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_dept_id_seq'::regclass);


--
-- Name: sys_menu id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_menu ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_menu_id_seq'::regclass);


--
-- Name: sys_permission id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_permission ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_permission_id_seq'::regclass);


--
-- Name: sys_role id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_role_id_seq'::regclass);


--
-- Name: sys_role_data_scope id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role_data_scope ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_role_data_scope_id_seq'::regclass);


--
-- Name: sys_user id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_user ALTER COLUMN id SET DEFAULT nextval('cs_auth.sys_user_id_seq'::regclass);


--
-- Name: system_config id; Type: DEFAULT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.system_config ALTER COLUMN id SET DEFAULT nextval('cs_auth.system_config_id_seq'::regclass);


--
-- Name: cs_business_hours_holiday id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_business_hours_holiday ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_business_hours_holiday_id_seq'::regclass);


--
-- Name: cs_canned_response id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_canned_response ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_canned_response_id_seq'::regclass);


--
-- Name: cs_canned_response_group id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_canned_response_group ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_canned_response_group_id_seq'::regclass);


--
-- Name: cs_conversation id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_conversation_id_seq'::regclass);


--
-- Name: cs_conversation_message id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation_message ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_conversation_message_id_seq'::regclass);


--
-- Name: cs_conversation_note id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation_note ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_conversation_note_id_seq'::regclass);


--
-- Name: cs_csat_rating id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_csat_rating ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_csat_rating_id_seq'::regclass);


--
-- Name: cs_domain id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_domain ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_domain_id_seq'::regclass);


--
-- Name: cs_intent id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_id_seq'::regclass);


--
-- Name: cs_intent_slot id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_slot_id_seq'::regclass);


--
-- Name: cs_intent_tier_stat id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tier_stat ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_tier_stat_id_seq'::regclass);


--
-- Name: cs_intent_tool id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_intent_tool_id_seq'::regclass);


--
-- Name: cs_llm_cost_log id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_llm_cost_log ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_llm_cost_log_id_seq'::regclass);


--
-- Name: cs_message_feedback id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_message_feedback ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_message_feedback_id_seq'::regclass);


--
-- Name: cs_rag_miss_log id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_rag_miss_log ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_rag_miss_log_id_seq'::regclass);


--
-- Name: cs_session_domain_switch id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_session_domain_switch ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_session_domain_switch_id_seq'::regclass);


--
-- Name: cs_session_feedback id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_session_feedback ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_session_feedback_id_seq'::regclass);


--
-- Name: cs_sla_breach id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_sla_breach ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_sla_breach_id_seq'::regclass);


--
-- Name: cs_sla_policy id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_sla_policy ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_sla_policy_id_seq'::regclass);


--
-- Name: cs_tag id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tag ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_tag_id_seq'::regclass);


--
-- Name: cs_tool id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tool ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_tool_id_seq'::regclass);


--
-- Name: cs_tool_call_log id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tool_call_log ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_tool_call_log_id_seq'::regclass);


--
-- Name: cs_webhook_config id; Type: DEFAULT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_webhook_config ALTER COLUMN id SET DEFAULT nextval('cs_conversation.cs_webhook_config_id_seq'::regclass);


--
-- Name: ai_model_config ai_model_config_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.ai_model_config
    ADD CONSTRAINT ai_model_config_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: sys_dept sys_dept_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_dept
    ADD CONSTRAINT sys_dept_pkey PRIMARY KEY (id);


--
-- Name: sys_menu sys_menu_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_menu
    ADD CONSTRAINT sys_menu_pkey PRIMARY KEY (id);


--
-- Name: sys_permission sys_permission_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_permission
    ADD CONSTRAINT sys_permission_pkey PRIMARY KEY (id);


--
-- Name: sys_role_data_scope sys_role_data_scope_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role_data_scope
    ADD CONSTRAINT sys_role_data_scope_pkey PRIMARY KEY (id);


--
-- Name: sys_role_data_scope sys_role_data_scope_role_id_key; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role_data_scope
    ADD CONSTRAINT sys_role_data_scope_role_id_key UNIQUE (role_id);


--
-- Name: sys_role_menu sys_role_menu_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role_menu
    ADD CONSTRAINT sys_role_menu_pkey PRIMARY KEY (role_id, menu_id);


--
-- Name: sys_role_permission sys_role_permission_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role_permission
    ADD CONSTRAINT sys_role_permission_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: sys_role sys_role_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_role
    ADD CONSTRAINT sys_role_pkey PRIMARY KEY (id);


--
-- Name: sys_user_dept sys_user_dept_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_user_dept
    ADD CONSTRAINT sys_user_dept_pkey PRIMARY KEY (user_id, dept_id);


--
-- Name: sys_user sys_user_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_user
    ADD CONSTRAINT sys_user_pkey PRIMARY KEY (id);


--
-- Name: sys_user_role sys_user_role_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.sys_user_role
    ADD CONSTRAINT sys_user_role_pkey PRIMARY KEY (user_id, role_id);


--
-- Name: system_config system_config_pkey; Type: CONSTRAINT; Schema: cs_auth; Owner: postgres
--

ALTER TABLE ONLY cs_auth.system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (id);


--
-- Name: cs_business_hours_holiday cs_business_hours_holiday_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_business_hours_holiday
    ADD CONSTRAINT cs_business_hours_holiday_pkey PRIMARY KEY (id);


--
-- Name: cs_business_hours_schedule cs_business_hours_schedule_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_business_hours_schedule
    ADD CONSTRAINT cs_business_hours_schedule_pkey PRIMARY KEY (day_of_week);


--
-- Name: cs_canned_response_group cs_canned_response_group_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_canned_response_group
    ADD CONSTRAINT cs_canned_response_group_pkey PRIMARY KEY (id);


--
-- Name: cs_canned_response cs_canned_response_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_canned_response
    ADD CONSTRAINT cs_canned_response_pkey PRIMARY KEY (id);


--
-- Name: cs_conversation_message cs_conversation_message_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation_message
    ADD CONSTRAINT cs_conversation_message_pkey PRIMARY KEY (id);


--
-- Name: cs_conversation_note cs_conversation_note_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation_note
    ADD CONSTRAINT cs_conversation_note_pkey PRIMARY KEY (id);


--
-- Name: cs_conversation cs_conversation_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation
    ADD CONSTRAINT cs_conversation_pkey PRIMARY KEY (id);


--
-- Name: cs_conversation_tag cs_conversation_tag_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_conversation_tag
    ADD CONSTRAINT cs_conversation_tag_pkey PRIMARY KEY (session_id, tag_id);


--
-- Name: cs_csat_rating cs_csat_rating_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_csat_rating
    ADD CONSTRAINT cs_csat_rating_pkey PRIMARY KEY (id);


--
-- Name: cs_domain cs_domain_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_domain
    ADD CONSTRAINT cs_domain_pkey PRIMARY KEY (id);


--
-- Name: cs_intent cs_intent_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent
    ADD CONSTRAINT cs_intent_pkey PRIMARY KEY (id);


--
-- Name: cs_intent_slot cs_intent_slot_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot
    ADD CONSTRAINT cs_intent_slot_pkey PRIMARY KEY (id);


--
-- Name: cs_intent_tier_stat cs_intent_tier_stat_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tier_stat
    ADD CONSTRAINT cs_intent_tier_stat_pkey PRIMARY KEY (id);


--
-- Name: cs_intent_tool cs_intent_tool_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT cs_intent_tool_pkey PRIMARY KEY (id);


--
-- Name: cs_llm_cost_log cs_llm_cost_log_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_llm_cost_log
    ADD CONSTRAINT cs_llm_cost_log_pkey PRIMARY KEY (id);


--
-- Name: cs_message_feedback cs_message_feedback_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_message_feedback
    ADD CONSTRAINT cs_message_feedback_pkey PRIMARY KEY (id);


--
-- Name: cs_pending_slot cs_pending_slot_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_pending_slot
    ADD CONSTRAINT cs_pending_slot_pkey PRIMARY KEY (session_id);


--
-- Name: cs_rag_miss_log cs_rag_miss_log_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_rag_miss_log
    ADD CONSTRAINT cs_rag_miss_log_pkey PRIMARY KEY (id);


--
-- Name: cs_session_domain_switch cs_session_domain_switch_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_session_domain_switch
    ADD CONSTRAINT cs_session_domain_switch_pkey PRIMARY KEY (id);


--
-- Name: cs_session_feedback cs_session_feedback_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_session_feedback
    ADD CONSTRAINT cs_session_feedback_pkey PRIMARY KEY (id);


--
-- Name: cs_sla_breach cs_sla_breach_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_sla_breach
    ADD CONSTRAINT cs_sla_breach_pkey PRIMARY KEY (id);


--
-- Name: cs_sla_policy cs_sla_policy_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_sla_policy
    ADD CONSTRAINT cs_sla_policy_pkey PRIMARY KEY (id);


--
-- Name: cs_tag cs_tag_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tag
    ADD CONSTRAINT cs_tag_pkey PRIMARY KEY (id);


--
-- Name: cs_tool_call_log cs_tool_call_log_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tool_call_log
    ADD CONSTRAINT cs_tool_call_log_pkey PRIMARY KEY (id);


--
-- Name: cs_tool cs_tool_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tool
    ADD CONSTRAINT cs_tool_pkey PRIMARY KEY (id);


--
-- Name: cs_visitor_tag cs_visitor_tag_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_visitor_tag
    ADD CONSTRAINT cs_visitor_tag_pkey PRIMARY KEY (visitor_id, tag_id);


--
-- Name: cs_webhook_config cs_webhook_config_pkey; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_webhook_config
    ADD CONSTRAINT cs_webhook_config_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: cs_business_hours_holiday uk_biz_holiday_date; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_business_hours_holiday
    ADD CONSTRAINT uk_biz_holiday_date UNIQUE (date);


--
-- Name: cs_sla_breach uk_sla_breach_session_type_stage; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_sla_breach
    ADD CONSTRAINT uk_sla_breach_session_type_stage UNIQUE (session_id, breach_type, stage);


--
-- Name: cs_sla_policy uk_sla_policy_name; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_sla_policy
    ADD CONSTRAINT uk_sla_policy_name UNIQUE (name);


--
-- Name: cs_tag uk_tag_name; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tag
    ADD CONSTRAINT uk_tag_name UNIQUE (name);


--
-- Name: cs_webhook_config uk_webhook_name; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_webhook_config
    ADD CONSTRAINT uk_webhook_name UNIQUE (name);


--
-- Name: cs_csat_rating uq_csat_session; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_csat_rating
    ADD CONSTRAINT uq_csat_session UNIQUE (session_id);


--
-- Name: cs_domain uq_domain_code; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_domain
    ADD CONSTRAINT uq_domain_code UNIQUE (code);


--
-- Name: cs_intent uq_intent_domain_code; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent
    ADD CONSTRAINT uq_intent_domain_code UNIQUE (domain_id, code);


--
-- Name: cs_intent_tool uq_intent_tool; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT uq_intent_tool UNIQUE (intent_id, tool_id);


--
-- Name: cs_message_feedback uq_msg_feedback; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_message_feedback
    ADD CONSTRAINT uq_msg_feedback UNIQUE (session_id, seq);


--
-- Name: cs_intent_slot uq_slot_intent_name; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot
    ADD CONSTRAINT uq_slot_intent_name UNIQUE (intent_id, slot_name);


--
-- Name: cs_tool uq_tool_code; Type: CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_tool
    ADD CONSTRAINT uq_tool_code UNIQUE (code);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX flyway_schema_history_s_idx ON cs_auth.flyway_schema_history USING btree (success);


--
-- Name: idx_ai_model_default; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_ai_model_default ON cs_auth.ai_model_config USING btree (is_default) WHERE (deleted_at IS NULL);


--
-- Name: idx_ai_model_enabled; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_ai_model_enabled ON cs_auth.ai_model_config USING btree (is_enabled) WHERE (deleted_at IS NULL);


--
-- Name: idx_ai_model_type_default; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_ai_model_type_default ON cs_auth.ai_model_config USING btree (model_type, is_default) WHERE ((deleted_at IS NULL) AND (is_enabled = true));


--
-- Name: idx_cs_dept_parent; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_dept_parent ON cs_auth.sys_dept USING btree (parent_id) WHERE (deleted_at IS NULL);


--
-- Name: idx_cs_menu_parent; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_menu_parent ON cs_auth.sys_menu USING btree (parent_id);


--
-- Name: idx_cs_menu_type_status; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_menu_type_status ON cs_auth.sys_menu USING btree (menu_type, status);


--
-- Name: idx_cs_permission_module; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_permission_module ON cs_auth.sys_permission USING btree (module);


--
-- Name: idx_cs_role_menu_menu; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_role_menu_menu ON cs_auth.sys_role_menu USING btree (menu_id);


--
-- Name: idx_cs_role_permission_perm; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_role_permission_perm ON cs_auth.sys_role_permission USING btree (permission_id);


--
-- Name: idx_cs_user_dept_dept; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_user_dept_dept ON cs_auth.sys_user_dept USING btree (dept_id);


--
-- Name: idx_cs_user_lastlogin; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_user_lastlogin ON cs_auth.sys_user USING btree (last_login_at);


--
-- Name: idx_cs_user_role_role; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_user_role_role ON cs_auth.sys_user_role USING btree (role_id);


--
-- Name: idx_cs_user_status; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_cs_user_status ON cs_auth.sys_user USING btree (status) WHERE (deleted_at IS NULL);


--
-- Name: idx_system_config_type; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE INDEX idx_system_config_type ON cs_auth.system_config USING btree (config_type) WHERE (deleted_at IS NULL);


--
-- Name: uk_cs_dept_code; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_dept_code ON cs_auth.sys_dept USING btree (dept_code) WHERE (deleted_at IS NULL);


--
-- Name: uk_cs_menu_key; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_menu_key ON cs_auth.sys_menu USING btree (menu_key);


--
-- Name: uk_cs_permission_key; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_permission_key ON cs_auth.sys_permission USING btree (permission_key);


--
-- Name: uk_cs_role_key; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_role_key ON cs_auth.sys_role USING btree (role_key);


--
-- Name: uk_cs_user_email; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_user_email ON cs_auth.sys_user USING btree (email) WHERE (deleted_at IS NULL);


--
-- Name: uk_cs_user_username; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_user_username ON cs_auth.sys_user USING btree (username) WHERE (deleted_at IS NULL);


--
-- Name: uq_system_config_key; Type: INDEX; Schema: cs_auth; Owner: postgres
--

CREATE UNIQUE INDEX uq_system_config_key ON cs_auth.system_config USING btree (config_key) WHERE (deleted_at IS NULL);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX flyway_schema_history_s_idx ON cs_conversation.flyway_schema_history USING btree (success);


--
-- Name: idx_conversation_note_session_id; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_conversation_note_session_id ON cs_conversation.cs_conversation_note USING btree (session_id);


--
-- Name: idx_conversation_tag_session_id; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_conversation_tag_session_id ON cs_conversation.cs_conversation_tag USING btree (session_id);


--
-- Name: idx_cr_fts; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_cr_fts ON cs_conversation.cs_canned_response USING gin (to_tsvector('simple'::regconfig, (((title)::text || ' '::text) || content))) WHERE (deleted = false);


--
-- Name: idx_cr_scope_owner; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_cr_scope_owner ON cs_conversation.cs_canned_response USING btree (scope, owner_id) WHERE (deleted = false);


--
-- Name: idx_cs_conv_status; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_cs_conv_status ON cs_conversation.cs_conversation USING btree (status) WHERE ((status)::text <> 'CLOSED'::text);


--
-- Name: idx_cs_conv_visitor_id; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_cs_conv_visitor_id ON cs_conversation.cs_conversation USING btree (visitor_id, status) WHERE (visitor_id IS NOT NULL);


--
-- Name: idx_cs_msg_session_seq; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_cs_msg_session_seq ON cs_conversation.cs_conversation_message USING btree (session_id, seq) WHERE (seq IS NOT NULL);


--
-- Name: idx_cs_msg_session_time; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_cs_msg_session_time ON cs_conversation.cs_conversation_message USING btree (session_id, created_at);


--
-- Name: idx_csat_agent_rated; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_csat_agent_rated ON cs_conversation.cs_csat_rating USING btree (agent_id, rated_at) WHERE (agent_id IS NOT NULL);


--
-- Name: idx_csat_status_expired; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_csat_status_expired ON cs_conversation.cs_csat_rating USING btree (status, expired_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_intent_tier_stat_domain; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_intent_tier_stat_domain ON cs_conversation.cs_intent_tier_stat USING btree (domain_code, created_at DESC);


--
-- Name: idx_intent_tier_stat_time; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_intent_tier_stat_time ON cs_conversation.cs_intent_tier_stat USING btree (created_at DESC);


--
-- Name: idx_llm_cost_log_created; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_llm_cost_log_created ON cs_conversation.cs_llm_cost_log USING btree (created_at DESC);


--
-- Name: idx_llm_cost_log_model; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_llm_cost_log_model ON cs_conversation.cs_llm_cost_log USING btree (model_name, created_at DESC);


--
-- Name: idx_llm_cost_log_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_llm_cost_log_session ON cs_conversation.cs_llm_cost_log USING btree (session_id);


--
-- Name: idx_msg_feedback_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_msg_feedback_session ON cs_conversation.cs_message_feedback USING btree (session_id);


--
-- Name: idx_rag_miss_log_miss; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_rag_miss_log_miss ON cs_conversation.cs_rag_miss_log USING btree (is_miss, created_at DESC);


--
-- Name: idx_rag_miss_log_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_rag_miss_log_session ON cs_conversation.cs_rag_miss_log USING btree (session_id);


--
-- Name: idx_session_domain_switch_created; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_session_domain_switch_created ON cs_conversation.cs_session_domain_switch USING btree (created_at);


--
-- Name: idx_session_domain_switch_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_session_domain_switch_session ON cs_conversation.cs_session_domain_switch USING btree (session_id);


--
-- Name: idx_session_feedback_pending; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_session_feedback_pending ON cs_conversation.cs_session_feedback USING btree (accumulated, kb_queued) WHERE ((accumulated = false) OR (kb_queued = false));


--
-- Name: idx_session_feedback_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_session_feedback_session ON cs_conversation.cs_session_feedback USING btree (session_id);


--
-- Name: idx_sla_breach_breach_at; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_sla_breach_breach_at ON cs_conversation.cs_sla_breach USING btree (breach_at);


--
-- Name: idx_sla_breach_session_id; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_sla_breach_session_id ON cs_conversation.cs_sla_breach USING btree (session_id);


--
-- Name: idx_sla_policy_priority; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_sla_policy_priority ON cs_conversation.cs_sla_policy USING btree (is_enabled, priority DESC);


--
-- Name: idx_tool_call_log_created; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_tool_call_log_created ON cs_conversation.cs_tool_call_log USING btree (created_at);


--
-- Name: idx_tool_call_log_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_tool_call_log_session ON cs_conversation.cs_tool_call_log USING btree (session_id);


--
-- Name: idx_visitor_tag_visitor_id; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE INDEX idx_visitor_tag_visitor_id ON cs_conversation.cs_visitor_tag USING btree (visitor_id);


--
-- Name: uk_cs_conv_session; Type: INDEX; Schema: cs_conversation; Owner: postgres
--

CREATE UNIQUE INDEX uk_cs_conv_session ON cs_conversation.cs_conversation USING btree (session_id);


--
-- Name: ai_model_config trg_ai_model_config_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_ai_model_config_updated BEFORE UPDATE ON cs_auth.ai_model_config FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_dept trg_cs_dept_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_cs_dept_updated BEFORE UPDATE ON cs_auth.sys_dept FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_menu trg_cs_menu_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_cs_menu_updated BEFORE UPDATE ON cs_auth.sys_menu FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_role_data_scope trg_cs_role_scope_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_cs_role_scope_updated BEFORE UPDATE ON cs_auth.sys_role_data_scope FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_role trg_cs_role_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_cs_role_updated BEFORE UPDATE ON cs_auth.sys_role FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: sys_user trg_cs_user_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_cs_user_updated BEFORE UPDATE ON cs_auth.sys_user FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: system_config trg_system_config_updated; Type: TRIGGER; Schema: cs_auth; Owner: postgres
--

CREATE TRIGGER trg_system_config_updated BEFORE UPDATE ON cs_auth.system_config FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();


--
-- Name: cs_business_hours_schedule trg_biz_hours_schedule_update_time; Type: TRIGGER; Schema: cs_conversation; Owner: postgres
--

CREATE TRIGGER trg_biz_hours_schedule_update_time BEFORE UPDATE ON cs_conversation.cs_business_hours_schedule FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();


--
-- Name: cs_conversation trg_cs_conv_updated; Type: TRIGGER; Schema: cs_conversation; Owner: postgres
--

CREATE TRIGGER trg_cs_conv_updated BEFORE UPDATE ON cs_conversation.cs_conversation FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_updated_at();


--
-- Name: cs_conversation_note trg_cs_conversation_note_update_time; Type: TRIGGER; Schema: cs_conversation; Owner: postgres
--

CREATE TRIGGER trg_cs_conversation_note_update_time BEFORE UPDATE ON cs_conversation.cs_conversation_note FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();


--
-- Name: cs_sla_policy trg_cs_sla_policy_update_time; Type: TRIGGER; Schema: cs_conversation; Owner: postgres
--

CREATE TRIGGER trg_cs_sla_policy_update_time BEFORE UPDATE ON cs_conversation.cs_sla_policy FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();


--
-- Name: cs_tag trg_cs_tag_update_time; Type: TRIGGER; Schema: cs_conversation; Owner: postgres
--

CREATE TRIGGER trg_cs_tag_update_time BEFORE UPDATE ON cs_conversation.cs_tag FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();


--
-- Name: cs_webhook_config trg_cs_webhook_config_update_time; Type: TRIGGER; Schema: cs_conversation; Owner: postgres
--

CREATE TRIGGER trg_cs_webhook_config_update_time BEFORE UPDATE ON cs_conversation.cs_webhook_config FOR EACH ROW EXECUTE FUNCTION cs_conversation.set_update_time();


--
-- Name: cs_canned_response cs_canned_response_group_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_canned_response
    ADD CONSTRAINT cs_canned_response_group_id_fkey FOREIGN KEY (group_id) REFERENCES cs_conversation.cs_canned_response_group(id) ON DELETE SET NULL;


--
-- Name: cs_canned_response_group cs_canned_response_group_parent_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_canned_response_group
    ADD CONSTRAINT cs_canned_response_group_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES cs_conversation.cs_canned_response_group(id) ON DELETE SET NULL;


--
-- Name: cs_intent cs_intent_domain_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent
    ADD CONSTRAINT cs_intent_domain_id_fkey FOREIGN KEY (domain_id) REFERENCES cs_conversation.cs_domain(id);


--
-- Name: cs_intent_slot cs_intent_slot_intent_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_slot
    ADD CONSTRAINT cs_intent_slot_intent_id_fkey FOREIGN KEY (intent_id) REFERENCES cs_conversation.cs_intent(id);


--
-- Name: cs_intent_tool cs_intent_tool_intent_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT cs_intent_tool_intent_id_fkey FOREIGN KEY (intent_id) REFERENCES cs_conversation.cs_intent(id);


--
-- Name: cs_intent_tool cs_intent_tool_tool_id_fkey; Type: FK CONSTRAINT; Schema: cs_conversation; Owner: postgres
--

ALTER TABLE ONLY cs_conversation.cs_intent_tool
    ADD CONSTRAINT cs_intent_tool_tool_id_fkey FOREIGN KEY (tool_id) REFERENCES cs_conversation.cs_tool(id);


--
-- PostgreSQL database dump complete
--



-- ---------- 业务种子数据 ----------
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
-- Data for Name: ai_model_config; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--



--
-- Data for Name: sys_dept; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--



--
-- Data for Name: sys_menu; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_menu VALUES (1, 0, 'DIRECTORY', '概览', 'Dashboard', '/dashboard', NULL, 'lucide:layout-dashboard', 1, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (3, 1, 'MENU', '工作台', 'DashboardWorkspace', '/dashboard/workspace', 'dashboard/workspace/index', 'carbon:workspace', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (102, 100, 'MENU', '知识库', 'CustomerServiceKnowledge', '/customerservice/knowledge', 'customerservice/knowledge/index', 'lucide:book-open', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (110, 102, 'BUTTON', '上传文档', 'knowledge:doc:upload', NULL, NULL, NULL, 1, false, false, false, NULL, 'knowledge:doc:upload', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (111, 102, 'BUTTON', '审核文档', 'knowledge:doc:review', NULL, NULL, NULL, 2, false, false, false, NULL, 'knowledge:doc:review', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (112, 102, 'BUTTON', '下线文档', 'knowledge:doc:offline', NULL, NULL, NULL, 3, false, false, false, NULL, 'knowledge:doc:offline', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (113, 102, 'BUTTON', '删除文档', 'knowledge:doc:delete', NULL, NULL, NULL, 4, false, false, false, NULL, 'knowledge:doc:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (120, 103, 'BUTTON', '接入会话', 'agent:session:accept', NULL, NULL, NULL, 1, false, false, false, NULL, 'agent:session:accept', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (121, 103, 'BUTTON', '结束会话', 'agent:session:close', NULL, NULL, NULL, 2, false, false, false, NULL, 'agent:session:close', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (122, 103, 'BUTTON', '转交会话', 'agent:session:transfer', NULL, NULL, NULL, 3, false, false, false, NULL, 'agent:session:transfer', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (200, 0, 'DIRECTORY', '系统管理', 'System', '/system', NULL, 'lucide:settings', 90, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (201, 200, 'MENU', '用户管理', 'SystemUser', '/system/user', 'system/user/index', 'lucide:users', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (202, 200, 'MENU', '角色管理', 'SystemRole', '/system/role', 'system/role/index', 'lucide:shield', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (203, 200, 'MENU', '菜单管理', 'SystemMenu', '/system/menu', 'system/menu/index', 'lucide:layout-list', 3, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (210, 201, 'BUTTON', '新增用户', 'system:user:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:user:create', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (211, 201, 'BUTTON', '编辑用户', 'system:user:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:user:update', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (212, 201, 'BUTTON', '删除用户', 'system:user:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:user:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (213, 201, 'BUTTON', '重置密码', 'system:user:reset-pwd', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:user:reset-pwd', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (214, 201, 'BUTTON', '分配角色', 'system:user:assign-role', NULL, NULL, NULL, 5, false, false, false, NULL, 'system:user:assign-role', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (220, 202, 'BUTTON', '新增角色', 'system:role:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:role:create', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (221, 202, 'BUTTON', '编辑角色', 'system:role:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:role:update', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (222, 202, 'BUTTON', '删除角色', 'system:role:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:role:delete', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (223, 202, 'BUTTON', '分配菜单', 'system:role:assign-menu', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:role:assign-menu', 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (900, 0, 'MENU', '关于', 'About', '/about', '_core/about/index', 'lucide:copyright', 99, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-06-29 07:04:09.968097+00');
INSERT INTO cs_auth.sys_menu VALUES (204, 200, 'MENU', '部门管理', 'SystemDept', '/system/dept', '_core/fallback/coming-soon', 'lucide:building-2', 4, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-07-02 15:29:51.400768+00');
INSERT INTO cs_auth.sys_menu VALUES (2, 1, 'MENU', '分析页', 'DashboardAnalysis', '/dashboard/analysis', 'dashboard/analytics/index', 'lucide:area-chart', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-07-02 15:30:07.084421+00');
INSERT INTO cs_auth.sys_menu VALUES (205, 200, 'MENU', 'AI 模型配置', 'SystemAiModel', '/system/ai-model', 'system/ai-model/index', 'lucide:cpu', 5, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-02 17:08:00.32996+00', '2026-07-02 17:08:00.32996+00');
INSERT INTO cs_auth.sys_menu VALUES (230, 205, 'BUTTON', '新增配置', 'system:ai-model:create', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:ai-model:create', 'active', NULL, NULL, '2026-07-02 17:08:16.290288+00', '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_menu VALUES (231, 205, 'BUTTON', '编辑配置', 'system:ai-model:update', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:ai-model:update', 'active', NULL, NULL, '2026-07-02 17:08:16.290288+00', '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_menu VALUES (232, 205, 'BUTTON', '删除配置', 'system:ai-model:delete', NULL, NULL, NULL, 3, false, false, false, NULL, 'system:ai-model:delete', 'active', NULL, NULL, '2026-07-02 17:08:16.290288+00', '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_menu VALUES (233, 205, 'BUTTON', '设为默认', 'system:ai-model:set-default', NULL, NULL, NULL, 4, false, false, false, NULL, 'system:ai-model:set-default', 'active', NULL, NULL, '2026-07-02 17:08:16.290288+00', '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_menu VALUES (104, 100, 'DIRECTORY', 'DIT配置', 'CustomerServiceDIT', '/customerservice/dit', NULL, 'lucide:settings-2', 40, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (105, 104, 'MENU', '领域与意图', 'CustomerServiceDITDomains', '/customerservice/dit/domains', 'customerservice/dit/domains/index', 'lucide:layers', 10, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (106, 104, 'MENU', '工具注册中心', 'CustomerServiceDITTools', '/customerservice/dit/tools', 'customerservice/dit/tools/index', 'lucide:wrench', 20, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (130, 105, 'BUTTON', '新建领域', 'dit:domain:create', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (131, 105, 'BUTTON', '编辑领域', 'dit:domain:update', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (132, 105, 'BUTTON', '删除领域', 'dit:domain:delete', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (133, 105, 'BUTTON', '管理意图', 'dit:intent:manage', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (134, 105, 'BUTTON', '管理槽位', 'dit:slot:manage', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (135, 106, 'BUTTON', '注册工具', 'dit:tool:create', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (136, 106, 'BUTTON', '编辑工具', 'dit:tool:update', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (137, 106, 'BUTTON', '删除工具', 'dit:tool:delete', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (138, 106, 'BUTTON', '测试工具', 'dit:tool:test', NULL, NULL, NULL, 0, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-04 15:48:09.284749+00', '2026-07-04 15:48:09.284749+00');
INSERT INTO cs_auth.sys_menu VALUES (901, 200, 'MENU', '系统参数配置', 'system:config', '/system/config', 'system/config/index', 'lucide:sliders', 90, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-12 04:31:30.295086+00', '2026-07-12 06:47:43.024942+00');
INSERT INTO cs_auth.sys_menu VALUES (902, 100, 'MENU', '客服参数配置', 'customerservice:config', '/customerservice/config', 'system/config/index', 'lucide:sliders-horizontal', 90, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-12 04:31:30.295086+00', '2026-07-12 06:47:43.024942+00');
INSERT INTO cs_auth.sys_menu VALUES (903, 100, 'MENU', '快捷回复', 'CustomerServiceCannedResponse', '/customerservice/canned-response', '/customerservice/canned-response/index', 'lucide:a-arrow-up', 50, true, true, false, NULL, NULL, 'active', NULL, 1, '2026-07-16 10:32:12.485447+00', '2026-07-16 16:56:31.409282+00');
INSERT INTO cs_auth.sys_menu VALUES (908, 903, 'BUTTON', '新增模板', 'canned-response:create', NULL, NULL, NULL, 1, true, true, false, NULL, NULL, 'active', NULL, 1, '2026-07-16 10:32:27.80009+00', '2026-07-16 10:32:27.80009+00');
INSERT INTO cs_auth.sys_menu VALUES (909, 903, 'BUTTON', '编辑模板', 'canned-response:update', NULL, NULL, NULL, 2, true, true, false, NULL, NULL, 'active', NULL, 1, '2026-07-16 10:32:27.80009+00', '2026-07-16 10:32:27.80009+00');
INSERT INTO cs_auth.sys_menu VALUES (910, 903, 'BUTTON', '删除模板', 'canned-response:delete', NULL, NULL, NULL, 3, true, true, false, NULL, NULL, 'active', NULL, 1, '2026-07-16 10:32:27.80009+00', '2026-07-16 10:32:27.80009+00');
INSERT INTO cs_auth.sys_menu VALUES (100, 0, 'DIRECTORY', '客服管理', 'CustomerService', '/customerservice', NULL, 'lucide:bot', 30, true, false, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-08-03 16:26:31.742242+00');
INSERT INTO cs_auth.sys_menu VALUES (101, 100, 'MENU', '对话', 'CustomerServiceChat', '/customerservice/chat', 'customerservice/chat/index', 'lucide:message-circle', 1, false, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-08-03 16:26:31.749342+00');
INSERT INTO cs_auth.sys_menu VALUES (911, 903, 'BUTTON', '分组管理', 'canned-response:group:manage', NULL, NULL, NULL, 4, true, true, false, NULL, NULL, 'active', NULL, 1, '2026-07-16 10:32:27.80009+00', '2026-07-16 10:32:27.80009+00');
INSERT INTO cs_auth.sys_menu VALUES (123, 103, 'BUTTON', '标签操作', 'session:tag:write', NULL, NULL, NULL, 4, false, false, false, NULL, 'session:tag:write', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (124, 103, 'BUTTON', '备注操作', 'session:note:write', NULL, NULL, NULL, 5, false, false, false, NULL, 'session:note:write', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (240, 206, 'BUTTON', 'SLA 管理', 'system:sla:manage', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:sla:manage', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (241, 206, 'BUTTON', 'SLA 查看', 'system:sla:view', NULL, NULL, NULL, 2, false, false, false, NULL, 'system:sla:view', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (242, 207, 'BUTTON', '营业时间管理', 'system:biz-hours:manage', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:biz-hours:manage', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (243, 208, 'BUTTON', '标签管理', 'system:tag:manage', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:tag:manage', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (244, 209, 'BUTTON', '会话查询', 'system:session:query', NULL, NULL, NULL, 1, false, false, false, NULL, 'system:session:query', 'active', NULL, NULL, '2026-07-24 03:41:17.102852+00', '2026-07-24 03:41:17.102852+00');
INSERT INTO cs_auth.sys_menu VALUES (207, 100, 'MENU', '营业时间', 'CustomerServiceBusinessHours', '/customerservice/business-hours', 'customerservice/business-hours/index', 'lucide:clock', 7, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-24 03:41:17.067599+00', '2026-07-24 04:49:28.218889+00');
INSERT INTO cs_auth.sys_menu VALUES (208, 100, 'MENU', '标签管理', 'SystemTags', '/customerservice/tags', 'customerservice/tags/index', 'lucide:tag', 5, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-24 03:41:17.067599+00', '2026-08-03 16:26:31.745014+00');
INSERT INTO cs_auth.sys_menu VALUES (206, 100, 'MENU', 'SLA 管理', 'SystemSla', '/customerservice/sla', 'customerservice/sla/index', 'lucide:alarm-clock', 6, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-24 03:41:17.067599+00', '2026-08-03 16:54:34.528781+00');
INSERT INTO cs_auth.sys_menu VALUES (245, 100, 'MENU', '通知配置', 'SystemSlaWebhooks', '/customerservice/webhooks', 'customerservice/sla/webhook', 'lucide:at-sign', 7, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-24 04:02:58.623502+00', '2026-08-03 16:54:34.528781+00');
INSERT INTO cs_auth.sys_menu VALUES (247, 1, 'MENU', '我的数据', 'DashboardMyData', '/dashboard/my-data', 'dashboard/my-data/index', 'lucide:user-cog', 3, true, true, false, NULL, NULL, 'active', '普通客服个人数据页：工作量+满意度', NULL, '2026-08-03 16:26:31.63644+00', '2026-08-03 16:26:31.63644+00');
INSERT INTO cs_auth.sys_menu VALUES (248, 0, 'DIRECTORY', '会话管理', 'Session', '/session', NULL, 'lucide:messages-square', 20, true, true, false, NULL, NULL, 'active', '会话相关页面：座席工作台/会话查询/SLA管理', NULL, '2026-08-03 16:26:31.649246+00', '2026-08-03 16:26:31.649246+00');
INSERT INTO cs_auth.sys_menu VALUES (103, 248, 'MENU', '座席工作台', 'CustomerServiceAgent', '/session/agent', 'session/agent/index', 'lucide:headphones', 1, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-06-29 07:04:09.968097+00', '2026-08-03 16:26:31.653236+00');
INSERT INTO cs_auth.sys_menu VALUES (246, 100, 'MENU', 'SLA 违规记录', 'SystemSlaBreaches', '/customerservice/sla/breaches', 'customerservice/sla/breaches', 'lucide:alert-triangle', 8, false, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-24 04:02:58.623502+00', '2026-08-03 16:54:34.528781+00');
INSERT INTO cs_auth.sys_menu VALUES (209, 248, 'MENU', '会话查询', 'SystemSession', '/session/history', 'session/history/index', 'lucide:message-square', 2, true, true, false, NULL, NULL, 'active', NULL, NULL, '2026-07-24 03:41:17.067599+00', '2026-08-04 06:53:56.70978+00');
INSERT INTO cs_auth.sys_menu VALUES (249, 0, 'DIRECTORY', '统计分析', 'Stats', '/stats', NULL, 'lucide:bar-chart-3', 25, true, true, false, NULL, NULL, 'active', 'P0 可观测性统计：意图分类命中率 / RAG检索质量 / LLM Token成本', NULL, '2026-08-06 12:12:54.464752+00', '2026-08-06 12:12:54.464752+00');
INSERT INTO cs_auth.sys_menu VALUES (250, 249, 'MENU', '意图分类', 'StatsIntent', '/stats/intent', 'stats/intent/index', 'lucide:git-branch', 1, true, true, false, NULL, NULL, 'active', 'DIT 三层意图识别命中率与延迟报表', NULL, '2026-08-06 12:12:54.474954+00', '2026-08-06 12:12:54.474954+00');
INSERT INTO cs_auth.sys_menu VALUES (251, 249, 'MENU', 'RAG质量', 'StatsRag', '/stats/rag', 'stats/rag/index', 'lucide:search-check', 2, true, true, false, NULL, NULL, 'active', 'RAG 检索质量与知识覆盖度（miss 率 / top1 分数）报表', NULL, '2026-08-06 12:12:54.477934+00', '2026-08-06 12:12:54.477934+00');
INSERT INTO cs_auth.sys_menu VALUES (252, 249, 'MENU', 'LLM成本', 'StatsLlmCost', '/stats/llm-cost', 'stats/llm-cost/index', 'lucide:coins', 3, true, true, false, NULL, NULL, 'active', 'LLM Token 消耗与成本（按模型/调用类型）报表', NULL, '2026-08-06 12:12:54.478566+00', '2026-08-06 12:12:54.478566+00');


--
-- Data for Name: sys_permission; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_permission VALUES (1, 'knowledge:doc:upload', '上传文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (2, 'knowledge:doc:review', '审核文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (3, 'knowledge:doc:offline', '下线文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (4, 'knowledge:doc:delete', '删除文档', 'knowledge', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (5, 'agent:session:accept', '接入会话', 'agent', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (6, 'agent:session:close', '结束会话', 'agent', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (7, 'agent:session:transfer', '转交会话', 'agent', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (8, 'system:user:create', '新增用户', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (9, 'system:user:update', '编辑用户', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (10, 'system:user:delete', '删除用户', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (11, 'system:user:reset-pwd', '重置密码', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (12, 'system:user:assign-role', '分配角色', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (13, 'system:role:create', '新增角色', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (14, 'system:role:update', '编辑角色', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (15, 'system:role:delete', '删除角色', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (16, 'system:role:assign-menu', '分配菜单', 'system', NULL, '2026-06-29 07:04:09.971301+00');
INSERT INTO cs_auth.sys_permission VALUES (50, 'system:ai-model:create', '新增AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_permission VALUES (51, 'system:ai-model:update', '编辑AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_permission VALUES (52, 'system:ai-model:delete', '删除AI模型配置', 'system', NULL, '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_permission VALUES (53, 'system:ai-model:set-default', '设为默认AI模型', 'system', NULL, '2026-07-02 17:08:16.290288+00');
INSERT INTO cs_auth.sys_permission VALUES (54, 'system:config:list', '系统配置-查询', 'system_config', '查看系统配置列表及详情', '2026-07-12 04:31:30.288745+00');
INSERT INTO cs_auth.sys_permission VALUES (55, 'system:config:create', '系统配置-新增', 'system_config', '新增系统配置项', '2026-07-12 04:31:30.288745+00');
INSERT INTO cs_auth.sys_permission VALUES (56, 'system:config:update', '系统配置-编辑', 'system_config', '修改配置值及启用状态', '2026-07-12 04:31:30.288745+00');
INSERT INTO cs_auth.sys_permission VALUES (57, 'system:config:delete', '系统配置-删除', 'system_config', '软删除系统配置项', '2026-07-12 04:31:30.288745+00');
INSERT INTO cs_auth.sys_permission VALUES (58, 'session:tag:write', '会话标签操作', 'agent', '新增/删除会话标签及访客标签', '2026-07-24 03:41:17.106352+00');
INSERT INTO cs_auth.sys_permission VALUES (59, 'session:note:write', '会话备注操作', 'agent', '新增/编辑/删除会话内部备注', '2026-07-24 03:41:17.106352+00');
INSERT INTO cs_auth.sys_permission VALUES (60, 'system:session:query', '会话管理-查询', 'system', '管理后台查询/导出历史会话记录', '2026-07-24 03:41:17.106352+00');
INSERT INTO cs_auth.sys_permission VALUES (61, 'system:tag:manage', '标签管理', 'system', '创建/编辑/删除全局标签', '2026-07-24 03:41:17.106352+00');
INSERT INTO cs_auth.sys_permission VALUES (62, 'system:sla:manage', 'SLA 管理', 'system', '创建/编辑/删除 SLA 策略及 Webhook', '2026-07-24 03:41:17.106352+00');
INSERT INTO cs_auth.sys_permission VALUES (63, 'system:sla:view', 'SLA 查看', 'system', '查看 SLA 策略列表及违规统计', '2026-07-24 03:41:17.106352+00');
INSERT INTO cs_auth.sys_permission VALUES (64, 'system:biz-hours:manage', '营业时间管理', 'system', '创建/编辑/删除营业时间规则', '2026-07-24 03:41:17.106352+00');


--
-- Data for Name: sys_role; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_role VALUES (10, 'super_admin', '超级管理员', NULL, true, 'active', '2026-06-29 07:04:09.972562+00', '2026-06-29 07:04:09.972562+00');
INSERT INTO cs_auth.sys_role VALUES (11, 'kf_manager', '客服管理员', NULL, false, 'active', '2026-06-29 07:04:09.972562+00', '2026-06-29 07:04:09.972562+00');
INSERT INTO cs_auth.sys_role VALUES (12, 'kf_staff', '普通客服', NULL, false, 'active', '2026-06-29 07:04:09.972562+00', '2026-06-29 07:04:09.972562+00');


--
-- Data for Name: sys_role_data_scope; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_role_data_scope VALUES (1, 10, 'ALL', '[]', '2026-06-29 07:04:09.974783+00', '2026-06-29 15:09:16.215976+00');
INSERT INTO cs_auth.sys_role_data_scope VALUES (2, 11, 'DEPT_TREE', '[]', '2026-06-29 07:04:09.974783+00', '2026-06-29 15:09:16.215976+00');
INSERT INTO cs_auth.sys_role_data_scope VALUES (3, 12, 'SELF', '[]', '2026-06-29 07:04:09.974783+00', '2026-06-29 15:09:16.215976+00');


--
-- Data for Name: sys_role_menu; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_role_menu VALUES (11, 100, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 102, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 103, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 110, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 111, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 112, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 113, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 120, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 121, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 122, '2026-06-29 07:04:09.977047+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 100, '2026-06-29 07:04:09.977935+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 104, '2026-07-04 15:48:09.318584+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 105, '2026-07-04 15:48:09.318584+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 106, '2026-07-04 15:48:09.318584+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 133, '2026-07-04 15:48:09.318584+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 134, '2026-07-04 15:48:09.318584+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 138, '2026-07-04 15:48:09.318584+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 902, '2026-07-12 04:31:30.31534+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 1, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 2, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 3, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 100, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 102, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 103, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 110, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 111, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 112, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 113, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 120, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 121, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 122, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 200, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 201, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 202, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 203, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 204, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 210, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 211, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 212, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 213, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 214, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 220, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 221, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 222, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 223, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 900, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 205, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 230, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 231, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 232, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 233, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 104, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 105, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 106, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 130, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 131, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 132, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 133, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 134, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 135, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 136, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 137, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 138, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 901, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 902, '2026-07-16 10:34:12.459975+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 903, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 903, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 903, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 908, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 908, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 908, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 909, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 909, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 909, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 910, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 910, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 910, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 911, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 911, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 911, '2026-07-16 10:34:23.492196+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 206, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 207, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 208, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 209, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 123, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 124, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 240, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 241, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 242, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 243, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 244, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 123, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 124, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 208, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 243, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 206, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 241, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 123, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 124, '2026-07-24 03:41:17.119109+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 245, '2026-07-24 04:02:58.640311+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 246, '2026-07-24 04:02:58.640311+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 245, '2026-07-24 04:02:58.640311+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 246, '2026-07-24 04:02:58.640311+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 207, '2026-07-24 04:49:28.221211+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 209, '2026-07-24 04:49:28.221211+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 1, '2026-07-28 15:55:21.352223+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 2, '2026-07-28 15:55:21.352223+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 3, '2026-07-28 15:55:21.352223+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 247, '2026-08-03 16:26:31.750012+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 248, '2026-08-03 16:26:31.750012+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 247, '2026-08-03 16:26:31.751636+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 248, '2026-08-03 16:26:31.751636+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 1, '2026-08-03 16:26:31.752273+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 247, '2026-08-03 16:26:31.752273+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 248, '2026-08-03 16:26:31.752273+00');
INSERT INTO cs_auth.sys_role_menu VALUES (12, 103, '2026-08-03 16:26:31.752273+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 249, '2026-08-06 12:12:54.481967+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 250, '2026-08-06 12:12:54.481967+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 251, '2026-08-06 12:12:54.481967+00');
INSERT INTO cs_auth.sys_role_menu VALUES (10, 252, '2026-08-06 12:12:54.481967+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 249, '2026-08-06 12:12:54.48435+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 250, '2026-08-06 12:12:54.48435+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 251, '2026-08-06 12:12:54.48435+00');
INSERT INTO cs_auth.sys_role_menu VALUES (11, 252, '2026-08-06 12:12:54.48435+00');


--
-- Data for Name: sys_role_permission; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_role_permission VALUES (10, 1);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 2);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 3);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 4);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 5);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 6);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 7);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 8);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 9);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 10);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 11);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 12);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 13);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 14);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 15);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 16);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 50);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 51);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 52);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 53);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 54);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 55);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 56);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 57);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 54);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 55);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 56);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 57);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 58);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 59);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 60);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 61);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 62);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 63);
INSERT INTO cs_auth.sys_role_permission VALUES (10, 64);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 58);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 59);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 61);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 63);
INSERT INTO cs_auth.sys_role_permission VALUES (12, 58);
INSERT INTO cs_auth.sys_role_permission VALUES (12, 59);
INSERT INTO cs_auth.sys_role_permission VALUES (11, 60);


--
-- Data for Name: sys_user; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_user VALUES (1003, 'kfstaff', '普通客服', 'kfstaff@example.com', '17688889999', '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-18 16:31:50.956635', '[]', '2026-08-03 07:21:38.265434', '192.168.158.1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982+00', '2026-08-03 07:21:38.267762+00');
INSERT INTO cs_auth.sys_user VALUES (1002, 'kfmanager', '客服管理员', 'kfmanager@example.com', '16677778888', '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 1, NULL, false, '2026-07-18 16:31:50.956635', '[]', '2026-08-03 07:19:57.516825', '192.168.158.1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982+00', '2026-08-04 08:18:50.744469+00');
INSERT INTO cs_auth.sys_user VALUES (1001, 'superadmin', '超级管理员', 'superadmin@example.com', '166999911111', '$2a$10$Eb4W1viRpoA9Bt1hh7tiJuJYU4A4cMSPviCw8Jyl/9unpvQRzI0qO', 'active', 'local', 0, NULL, false, '2026-07-18 16:31:50.956635', '[]', '2026-08-06 12:16:18.227042', '192.168.158.1', NULL, NULL, NULL, '2026-06-29 07:04:09.978982+00', '2026-08-06 12:16:18.304994+00');


--
-- Data for Name: sys_user_dept; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--



--
-- Data for Name: sys_user_role; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.sys_user_role VALUES (1003, 12, '2026-08-03 07:21:38.267762', NULL);
INSERT INTO cs_auth.sys_user_role VALUES (1002, 11, '2026-08-04 08:18:50.744469', NULL);
INSERT INTO cs_auth.sys_user_role VALUES (1001, 10, '2026-08-06 12:16:18.304994', NULL);


--
-- Data for Name: system_config; Type: TABLE DATA; Schema: cs_auth; Owner: postgres
--

INSERT INTO cs_auth.system_config VALUES (1, 'agent.maxConcurrent', '5', 'CUSTOMER_SERVICE', '单个座席同时接待的最大会话数。超出时系统拒绝新分配。取值范围：1–50', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (2, 'agent.welcomeMessage', '您好，感谢联系我们，请问有什么可以帮助您？', 'CUSTOMER_SERVICE', '会话建立时后端自动插入的欢迎消息内容', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (3, 'knowledge.searchTopK', '5', 'CUSTOMER_SERVICE', 'RAG 检索时返回的最大相关片段数（TopK）。取值范围：1–20', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (4, 'knowledge.uploadMaxFileSizeMb', '20', 'CUSTOMER_SERVICE', '知识库文件上传的单文件大小上限（单位：MB）。取值范围：1–200', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (5, 'prompt.agent.suggestion', '你是一名专业客服，请根据以下对话历史和知识库内容，为座席生成 3 条简洁的回复建议。

对话历史：
{history}

知识库参考：
{context}', 'CUSTOMER_SERVICE', '座席建议回复的 prompt 模板。占位符：{history}、{context}', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (6, 'prompt.kb.qa', '你是一名专业客服助手，请根据以下知识库内容回答用户问题。如果知识库中没有相关信息，请如实告知。

知识库内容：
{context}

用户问题：{question}', 'CUSTOMER_SERVICE', '知识库问答的 prompt 模板。占位符：{context}、{question}', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (7, 'prompt.visitor.autoReply', '你是一名智能客服，请根据以下对话历史和知识库内容，自动回复访客的最新消息。回复要简洁、友好、专业。

知识库内容：
{context}

对话历史：
{history}', 'CUSTOMER_SERVICE', '访客自动回复的 prompt 模板。占位符：{context}、{history}', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (8, 'prompt.session.summary', '请根据以下客服对话记录，生成一份简洁的会话摘要，包含：用户主要问题、解决方案、是否已解决。

对话记录：
{history}', 'CUSTOMER_SERVICE', '会话结束后生成摘要的 prompt 模板。占位符：{history}', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (9, 'prompt.intent.classify', '请分析用户消息的意图，从以下类别中选择最匹配的一个：{intents}。

用户消息：{message}

只需返回类别名称，不需要解释。', 'CUSTOMER_SERVICE', '意图识别分类的 prompt 模板。占位符：{intents}、{message}', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (10, 'dashboard.recentLimit', '10', 'SYSTEM', '仪表盘"最近记录"查询的 SQL LIMIT 值。取值范围：5–100', true, '2026-07-12 04:31:30.280237+00', '2026-07-12 04:31:30.280237+00', NULL);
INSERT INTO cs_auth.system_config VALUES (11, 'complexity.simpleMaxMessages', '5', 'CUSTOMER_SERVICE', '会话复杂度分桶：消息数 ≤ 此值为「简单」，取值范围 1–20', true, '2026-07-12 05:12:53.167476+00', '2026-07-12 05:12:53.167476+00', NULL);
INSERT INTO cs_auth.system_config VALUES (12, 'complexity.mediumMaxMessages', '15', 'CUSTOMER_SERVICE', '会话复杂度分桶：消息数 ≤ 此值为「中等」，超出则为「复杂」，取值范围 6–100', true, '2026-07-12 05:12:53.167476+00', '2026-07-12 05:12:53.167476+00', NULL);
INSERT INTO cs_auth.system_config VALUES (13, 'routing.config', '{
    "intent": {
      "embeddingEnabled": false,
      "embeddingThreshold": 0.75,
      "minLlmConfidence": 0.0,
      "maxExamplesToInject": 5
    },
    "domain": {
      "ruleEnabled": true
    }
  }', 'CUSTOMER_SERVICE', '深度验证-编辑修复-1784356772', true, '2026-07-14 10:23:19.492547+00', '2026-07-18 06:39:43.261133+00', NULL);
INSERT INTO cs_auth.system_config VALUES (14, 'agent.offlineMessage', '您好，当前不在服务时间，我们将在 {nextOpenTime} 恢复服务，感谢您的耐心等待。', 'CUSTOMER_SERVICE', '非服务时间离线自动回复消息', true, '2026-07-24 04:07:22.899268+00', '2026-07-24 04:07:22.899268+00', NULL);


--
-- Data for Name: cs_business_hours_holiday; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_business_hours_schedule; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (2, true, '[{"end": "18:00", "start": "09:00"}]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.614858');
INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (3, true, '[{"end": "18:00", "start": "09:00"}]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.61596');
INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (5, true, '[{"end": "18:00", "start": "09:00"}]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.617');
INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (6, false, '[]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.618132');
INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (7, false, '[]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.618782');
INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (1, true, '[{"end": "19:00", "start": "10:00"}]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.619387');
INSERT INTO cs_conversation.cs_business_hours_schedule VALUES (4, true, '[{"end": "18:00", "start": "09:00"}]', 'Asia/Shanghai', '2026-07-24 12:07:10.766412', '2026-08-03 07:20:08.619947');


--
-- Data for Name: cs_canned_response_group; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_canned_response_group VALUES (1, '通用', NULL, 0, 1001, '2026-07-16 15:34:53.665673+00', false);


--
-- Data for Name: cs_canned_response; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_canned_response VALUES (1, 1, '感谢等待', '很高兴为您服务', 'PUBLIC', NULL, 0, 0, 1001, '2026-07-16 15:35:23.952425+00', '2026-07-16 15:35:23.952588+00', false);


--
-- Data for Name: cs_conversation; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_conversation_message; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_conversation_note; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_conversation_tag; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_csat_rating; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_domain; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_domain VALUES (3, 'ecommerce', '电商客服', '电商平台客服，处理订单、退款、商品咨询等', '你是一名专业的电商平台客服助手，熟悉订单、退款、物流等业务流程。回答要简洁准确。', NULL, true, '2026-07-05 00:13:54.520015+00', '2026-07-05 00:13:54.520015+00', '[]', '[]');
INSERT INTO cs_conversation.cs_domain VALUES (4, 'finance', '金融客服', '银行及金融产品客服，处理账户查询、理财咨询等', '你是一名专业的金融客服助手。注意：涉及转账、取款等敏感操作必须转接人工，保护用户资金安全。', NULL, true, '2026-07-05 00:13:54.658105+00', '2026-07-05 00:13:54.658105+00', '[]', '[]');
INSERT INTO cs_conversation.cs_domain VALUES (5, 'travel', '酒旅客服', '酒店预订和旅游服务客服', '你是一名专业的酒旅客服助手，擅长酒店推荐、预订流程和旅游攻略。', NULL, true, '2026-07-05 00:13:54.721371+00', '2026-07-05 00:13:54.721371+00', '[]', '[]');
INSERT INTO cs_conversation.cs_domain VALUES (6, 'weather', '天气助手', '天气查询智能客服，支持实时天气、多日预报、空气质量查询，基于开源免费 API', '你是一个专业的天气查询助手。当用户询问天气时，请调用相应工具获取真实数据后回复，不要编造天气信息。回复时请使用简洁友好的语言，可以适当加上天气相关的贴心提示（如出行建议、穿衣提醒）。', NULL, true, '2026-07-04 16:32:05.98089+00', '2026-07-04 16:32:05.98089+00', '[]', '[]');


--
-- Data for Name: cs_intent; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_intent VALUES (1, 3, 'query_order', '查询订单', '用户想查询订单状态、物流信息或订单详情', '["帮我查订单", "我的包裹到哪了", "查一下单号ORD001", "订单什么时候发货"]', false, true, NULL, true, 10, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (2, 3, 'apply_refund', '申请退款', '用户想申请退款或退货', '["我要退款", "申请退货", "这个商品质量太差要退", "退款流程是什么"]', false, true, NULL, true, 20, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (3, 3, 'product_inquiry', '商品咨询', '用户咨询商品详情、规格、库存、适用场景等', '["这款商品有什么颜色", "尺码怎么选", "适合多大年龄", "材质是什么"]', false, false, NULL, true, 30, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (4, 3, 'complaint', '投诉', '用户对服务或商品表达强烈不满，需要投诉', '["我要投诉", "服务太差了", "要求赔偿", "找你们负责人"]', true, false, '非常抱歉给您带来不好的体验，已为您转接专属客服处理投诉。', true, 40, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (5, 3, 'chitchat', '闲聊', '用户进行日常闲聊、问候，与业务无关', '["你好", "今天天气怎么样", "你是谁", "在吗"]', false, true, NULL, true, 50, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (6, 4, 'query_balance', '查询账户余额', '用户想查询银行卡或账户的当前余额', '["我的余额是多少", "查一下账户", "卡里还有多少钱", "账户余额查询"]', false, true, NULL, true, 10, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (7, 4, 'transfer_money', '转账汇款', '用户想进行转账或汇款操作', '["我要转账", "帮我汇款", "转钱给别人", "网银转账"]', true, false, '转账操作涉及资金安全，已为您转接专属人工客服核实身份后处理。', true, 20, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (8, 4, 'investment_inquiry', '理财咨询', '用户咨询理财产品、基金、利率等投资相关问题', '["有什么理财产品", "基金怎么买", "存款利率是多少", "推荐一些低风险产品"]', false, false, NULL, true, 30, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (9, 4, 'report_loss', '挂失补办', '用户需要挂失银行卡或补办卡片', '["银行卡丢了", "卡被盗了要挂失", "怎么补办银行卡", "申请挂失"]', true, false, '挂失业务需要身份核实，已为您转接人工客服处理。', true, 40, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (10, 5, 'search_hotel', '搜索酒店', '用户想查找某城市的可用酒店', '["帮我找北京的酒店", "上海有什么好酒店", "三亚五星级酒店推荐", "明天去杭州住哪好"]', false, true, NULL, true, 10, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (11, 5, 'make_booking', '预订房间', '用户想预订特定酒店的房间', '["我要预订", "帮我订一间", "确认预订", "怎么下单"]', true, false, '预订操作需要确认详细信息，已为您转接人工客服协助完成预订。', true, 20, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (12, 5, 'travel_guide', '旅游攻略', '用户想了解景点推荐、旅游路线、当地特色', '["三亚有什么好玩的", "推荐一下北京景点", "云南旅游攻略", "西藏几月份去好"]', false, false, NULL, true, 30, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (13, 6, 'query_current_weather', '查询当前天气', '用户询问某城市当前天气状况，包含温度、湿度、风速、天气描述等实时信息', '["今天北京天气怎么样", "上海现在多少度", "广州天气", "深圳今天热不热", "现在武汉天气如何", "帮我查一下成都的天气"]', false, true, '抱歉，暂时无法获取天气信息，请稍后重试或访问天气应用查询。', true, 1, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (14, 6, 'query_weather_forecast', '查询天气预报', '用户询问某城市未来几天的天气预报，包含每日天气、温度区间、降水概率等', '["明天上海天气", "北京未来三天天气", "这周广州会下雨吗", "杭州周末天气怎么样", "成都明后天天气预报", "深圳本周天气"]', false, true, '抱歉，暂时无法获取天气预报，请稍后重试。', true, 2, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (15, 6, 'query_air_quality', '查询空气质量', '用户询问某城市的空气质量、AQI指数、PM2.5浓度、是否适合户外活动等', '["北京今天空气质量怎么样", "上海PM2.5多少", "今天适合出门跑步吗", "广州空气质量好吗", "深圳AQI是多少", "今天口罩要戴吗"]', false, true, '抱歉，暂时无法获取空气质量数据，请稍后重试。', true, 3, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (16, 6, 'travel_weather_advice', '出行天气建议', '用户询问某城市是否适合出行、旅游，或者询问某段时间内某地的天气是否适合特定活动', '["下周去北京旅游天气好吗", "去三亚度假天气怎么样", "明天开车去上海路上天气咋样", "这周末适合去爬山吗", "去杭州西湖游玩天气合适吗"]', false, false, '抱歉，暂时无法获取出行天气建议，请查看天气应用或联系客服。', true, 4, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (17, 6, 'out_of_scope', '超出范围', '用户提问与天气无关，或需要人工处理的情况，自动转人工服务', '["帮我订机票", "我要投诉", "怎么退款", "人工客服", "找真人"]', true, true, '您的问题超出了天气助手的服务范围，正在为您转接人工客服...', true, 5, '[]', '[]');
INSERT INTO cs_conversation.cs_intent VALUES (24, 6, 'Artificial', '人工', '转人工', '["人工", "转人工", "我要人工", "人工客服"]', true, true, NULL, true, 0, '["转人工", "人工"]', '[]');


--
-- Data for Name: cs_intent_slot; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_intent_slot VALUES (1, 1, 'order_id', 'string', '订单号，格式为ORD开头的字符串', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', NULL, 'list_orders', '{}', '请提供您要查询的订单号，可在购买确认短信中找到', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot VALUES (2, 2, 'order_id', 'string', '需要退款的订单号', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', NULL, 'list_orders', '{}', '请提供需要退款的订单号', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot VALUES (3, 6, 'account_id', 'string', '账户ID或银行卡号', true, '["SESSION"]', 'account_id', NULL, '{}', '请提供您的账户ID', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot VALUES (4, 10, 'city', 'string', '目标城市名称', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问您要去哪个城市？', NULL, 0);
INSERT INTO cs_conversation.cs_intent_slot VALUES (5, 10, 'check_in', 'date', '入住日期，格式 YYYY-MM-DD', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问您计划哪天入住？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot VALUES (6, 10, 'check_out', 'date', '退房日期，格式 YYYY-MM-DD', true, '["EXTRACT", "ASK_USER"]', NULL, NULL, '{}', '请问哪天退房？', NULL, 2);
INSERT INTO cs_conversation.cs_intent_slot VALUES (7, 13, 'city', 'string', '需要查询天气的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的天气？例如：北京、上海、广州', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot VALUES (8, 14, 'city', 'string', '需要查询天气预报的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的天气预报？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot VALUES (9, 14, 'days', 'integer', '预报天数，1-3天', false, '["EXTRACT"]', NULL, NULL, '{}', NULL, '[1, 2, 3]', 2);
INSERT INTO cs_conversation.cs_intent_slot VALUES (10, 15, 'city', 'string', '需要查询空气质量的城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您想查询哪个城市的空气质量？', NULL, 1);
INSERT INTO cs_conversation.cs_intent_slot VALUES (11, 16, 'city', 'string', '目的地城市名称', true, '["EXTRACT", "SESSION", "DISCOVER", "ASK_USER"]', 'last_city', 'geocoding_search', '{}', '请问您打算去哪个城市？我来帮您查询出行天气。', NULL, 1);


--
-- Data for Name: cs_intent_tier_stat; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_tool; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_tool VALUES (3, 'list_orders', '查询订单列表', '查询用户的订单列表，支持按状态过滤。isDiscoverTool=true，可作为槽位DISCOVER级发现工具', 'HTTP', 'GET', 'https://api.example.com/orders', '{}', NULL, '{"status": {"type": "string", "description": "订单状态（unpaid/shipped/completed）"}, "user_id": {"type": "string", "description": "用户ID"}}', '$.data.orders', 'NONE', '{}', 5000, true, true, '2026-07-05 00:13:54.474449+00', '2026-07-05 00:13:54.474449+00');
INSERT INTO cs_conversation.cs_tool VALUES (4, 'get_order', '查询订单详情', '根据订单号获取订单详情，包含商品信息、金额、物流状态', 'HTTP', 'GET', 'https://api.example.com/orders/{order_id}', '{}', NULL, '{"order_id": {"type": "string", "required": true, "description": "订单号"}}', '$.data', 'NONE', '{}', 5000, false, true, '2026-07-05 00:13:54.487366+00', '2026-07-05 00:13:54.487366+00');
INSERT INTO cs_conversation.cs_tool VALUES (5, 'create_refund', '创建退款申请', '为指定订单创建退款申请，需要订单号，退款原因可选。LLM决定是否调用', 'HTTP', 'POST', 'https://api.example.com/refunds', '{}', '{"reason": "{reason}", "order_id": "{order_id}"}', '{"reason": {"type": "string", "description": "退款原因"}, "order_id": {"type": "string", "required": true, "description": "订单号"}}', '$.data', 'NONE', '{}', 8000, false, true, '2026-07-05 00:13:54.495835+00', '2026-07-05 00:13:54.495835+00');
INSERT INTO cs_conversation.cs_tool VALUES (6, 'get_balance', '查询账户余额', '查询指定账户的当前余额和可用额度', 'HTTP', 'GET', 'https://api.example.com/accounts/{account_id}/balance', '{}', NULL, '{"account_id": {"type": "string", "required": true, "description": "账户ID"}}', '$.data', 'NONE', '{}', 5000, false, true, '2026-07-05 00:13:54.504385+00', '2026-07-05 00:13:54.504385+00');
INSERT INTO cs_conversation.cs_tool VALUES (7, 'search_hotel', '搜索酒店', '根据城市和入离店日期搜索可用酒店列表，返回房型和价格', 'HTTP', 'POST', 'https://api.example.com/hotels/search', '{}', '{"city": "{city}", "check_in": "{check_in}", "check_out": "{check_out}"}', '{"city": {"type": "string", "required": true, "description": "城市名称"}, "check_in": {"type": "string", "required": true, "description": "入住日期 YYYY-MM-DD"}, "check_out": {"type": "string", "required": true, "description": "退房日期 YYYY-MM-DD"}}', '$.data.hotels', 'NONE', '{}', 8000, false, true, '2026-07-05 00:13:54.511747+00', '2026-07-05 00:13:54.511747+00');
INSERT INTO cs_conversation.cs_tool VALUES (8, 'geocoding_search', '城市名搜索', '根据城市名关键词搜索匹配的城市列表，返回城市名称、经纬度、国家等信息，用于槽位 DISCOVER 级候选发现', 'HTTP', 'GET', 'https://geocoding-api.open-meteo.com/v1/search?name={city_name}&count=5&language=zh&format=json', '{}', NULL, '{"city_name": {"type": "string", "required": true, "description": "城市名称关键词，如北京、上海、纽约"}}', '$.results[*].name', 'NONE', '{}', 5000, true, true, '2026-07-04 16:32:05.961021+00', '2026-07-04 16:32:05.961021+00');
INSERT INTO cs_conversation.cs_tool VALUES (9, 'get_current_weather', '查询当前天气', '查询指定城市的实时天气，包含温度、体感温度、湿度、风速、风向、天气状况等信息。使用 wttr.in 开源免费 API，支持中英文城市名。', 'HTTP', 'GET', 'https://wttr.in/{city}?format=j1', '{"Accept": "application/json"}', NULL, '{"city": {"type": "string", "required": true, "description": "城市名称，支持中文（如：北京）或英文（如：Beijing）"}}', '$.current_condition[0]', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.973958+00', '2026-07-04 16:32:05.973958+00');
INSERT INTO cs_conversation.cs_tool VALUES (10, 'get_weather_forecast', '查询天气预报', '查询指定城市未来3天的天气预报，包含每日最高/最低温度、降水概率、UV指数、日出日落时间等。使用 wttr.in 开源免费 API。', 'HTTP', 'GET', 'https://wttr.in/{city}?format=j1', '{"Accept": "application/json"}', NULL, '{"city": {"type": "string", "required": true, "description": "城市名称，支持中文或英文"}, "days": {"type": "integer", "default": 3, "required": false, "description": "预报天数，1-3天，默认3天"}}', '$.weather', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.977465+00', '2026-07-04 16:32:05.977465+00');
INSERT INTO cs_conversation.cs_tool VALUES (11, 'get_air_quality', '查询空气质量', '查询指定城市的实时空气质量指数（AQI），包含PM2.5、PM10、臭氧、一氧化碳等污染物浓度。使用 Open-Meteo Air Quality API，开源免费。需要先用 geocoding_search 获取城市经纬度。', 'HTTP', 'GET', 'https://air-quality-api.open-meteo.com/v1/air-quality?latitude={latitude}&longitude={longitude}&current=pm2_5,pm10,european_aqi,us_aqi,carbon_monoxide,ozone', '{}', NULL, '{"latitude": {"type": "number", "required": true, "description": "城市纬度（WGS84），如北京为 39.9042"}, "longitude": {"type": "number", "required": true, "description": "城市经度（WGS84），如北京为 116.4074"}}', '$.current', 'NONE', '{}', 8000, false, true, '2026-07-04 16:32:05.979324+00', '2026-07-04 16:32:05.979324+00');


--
-- Data for Name: cs_intent_tool; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_intent_tool VALUES (1, 1, 3, 'REQUIRED', 0, '{"user_id": {"key": "user_id", "source": "session"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (2, 1, 4, 'REQUIRED', 1, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (3, 2, 4, 'REQUIRED', 0, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (4, 2, 5, 'OPTIONAL', 1, '{"order_id": {"key": "order_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (5, 6, 6, 'REQUIRED', 0, '{"account_id": {"key": "account_id", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (6, 10, 7, 'REQUIRED', 0, '{"city": {"key": "city", "source": "slot"}, "check_in": {"key": "check_in", "source": "slot"}, "check_out": {"key": "check_out", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (7, 13, 9, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (8, 14, 10, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}, "days": {"key": "days", "source": "slot"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (9, 15, 11, 'OPTIONAL', 1, '{"latitude": {"key": "geocoding.latitude", "source": "tool_result"}, "longitude": {"key": "geocoding.longitude", "source": "tool_result"}}');
INSERT INTO cs_conversation.cs_intent_tool VALUES (10, 16, 10, 'REQUIRED', 1, '{"city": {"key": "city", "source": "slot"}}');


--
-- Data for Name: cs_llm_cost_log; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_message_feedback; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_pending_slot; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_rag_miss_log; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_session_domain_switch; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_session_feedback; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_sla_breach; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_sla_policy; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_sla_policy VALUES (1, '默认 SLA', true, 0, '[]', '[]', 'CALENDAR', 120, 60, 1800, 80, '{"sseAlert": true, "autoEscalate": false, "escalateToUserId": null, "recordBreachOnly": true}', '2026-07-24 12:07:10.769625', '2026-08-03 07:19:15.355969');


--
-- Data for Name: cs_tag; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--

INSERT INTO cs_conversation.cs_tag VALUES (3, ' 高风险用户', '#f5510a', 'PRESET', 0, NULL, '2026-07-24 06:27:04.027223', '2026-07-24 06:27:04.027223');
INSERT INTO cs_conversation.cs_tag VALUES (1, 'VIP', '#F59E0B', 'PRESET', 3, NULL, '2026-07-24 06:26:43.31284', '2026-08-02 16:19:46.510228');
INSERT INTO cs_conversation.cs_tag VALUES (2, '意向客户', '#F59E0B', 'PRESET', 3, NULL, '2026-07-24 06:26:51.841831', '2026-08-03 04:08:16.394715');


--
-- Data for Name: cs_tool_call_log; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_visitor_tag; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Data for Name: cs_webhook_config; Type: TABLE DATA; Schema: cs_conversation; Owner: postgres
--



--
-- Name: ai_model_config_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.ai_model_config_id_seq', 135, true);


--
-- Name: sys_dept_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.sys_dept_id_seq', 1, false);


--
-- Name: sys_menu_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.sys_menu_id_seq', 252, true);


--
-- Name: sys_permission_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.sys_permission_id_seq', 64, true);


--
-- Name: sys_role_data_scope_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.sys_role_data_scope_id_seq', 56, true);


--
-- Name: sys_role_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.sys_role_id_seq', 156, true);


--
-- Name: sys_user_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.sys_user_id_seq', 1003, true);


--
-- Name: system_config_id_seq; Type: SEQUENCE SET; Schema: cs_auth; Owner: postgres
--

SELECT pg_catalog.setval('cs_auth.system_config_id_seq', 99, true);


--
-- Name: cs_business_hours_holiday_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_business_hours_holiday_id_seq', 129, true);


--
-- Name: cs_canned_response_group_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_canned_response_group_id_seq', 85, true);


--
-- Name: cs_canned_response_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_canned_response_id_seq', 107, true);


--
-- Name: cs_conversation_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_id_seq', 773, true);


--
-- Name: cs_conversation_message_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_message_id_seq', 1336, true);


--
-- Name: cs_conversation_note_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_conversation_note_id_seq', 44, true);


--
-- Name: cs_csat_rating_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_csat_rating_id_seq', 170, true);


--
-- Name: cs_domain_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_domain_id_seq', 156, true);


--
-- Name: cs_intent_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_id_seq', 146, true);


--
-- Name: cs_intent_slot_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_slot_id_seq', 64, true);


--
-- Name: cs_intent_tier_stat_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_tier_stat_id_seq', 1, false);


--
-- Name: cs_intent_tool_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_intent_tool_id_seq', 51, true);


--
-- Name: cs_llm_cost_log_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_llm_cost_log_id_seq', 1, false);


--
-- Name: cs_message_feedback_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_message_feedback_id_seq', 15, true);


--
-- Name: cs_rag_miss_log_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_rag_miss_log_id_seq', 1, false);


--
-- Name: cs_session_domain_switch_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_session_domain_switch_id_seq', 77, true);


--
-- Name: cs_session_feedback_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_session_feedback_id_seq', 1, false);


--
-- Name: cs_sla_breach_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_sla_breach_id_seq', 634, true);


--
-- Name: cs_sla_policy_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_sla_policy_id_seq', 37, true);


--
-- Name: cs_tag_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_tag_id_seq', 83, true);


--
-- Name: cs_tool_call_log_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_tool_call_log_id_seq', 12, true);


--
-- Name: cs_tool_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_tool_id_seq', 87, true);


--
-- Name: cs_webhook_config_id_seq; Type: SEQUENCE SET; Schema: cs_conversation; Owner: postgres
--

SELECT pg_catalog.setval('cs_conversation.cs_webhook_config_id_seq', 50, true);


--
-- PostgreSQL database dump complete
--



-- ==========================================================================
-- 数据库 2：aria_knowledge （独立库，此处显式创建）
-- ==========================================================================
CREATE DATABASE aria_knowledge;
\connect aria_knowledge

-- ---------- 结构（扩展 pg_jieba / vector + 表） ----------
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
-- Name: pg_jieba; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_jieba WITH SCHEMA public;


--
-- Name: EXTENSION pg_jieba; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION pg_jieba IS 'a parser for full-text search of Chinese';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.set_updated_at() OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.flyway_schema_history OWNER TO postgres;

--
-- Name: knowledge_chunk; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.knowledge_chunk OWNER TO postgres;

--
-- Name: knowledge_doc; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.knowledge_doc OWNER TO postgres;

--
-- Name: TABLE knowledge_doc; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON TABLE public.knowledge_doc IS '知识库文档表，支持多格式文件';


--
-- Name: COLUMN knowledge_doc.content_hash; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.knowledge_doc.content_hash IS 'SHA-256(文件内容)，相同内容跳过重摄取';


--
-- Name: COLUMN knowledge_doc.status; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.knowledge_doc.status IS 'DRAFT=草稿 / REVIEW=审核中 / PUBLISHED=已发布 / DEPRECATED=已下线 / FAILED=摄取失败';


--
-- Name: COLUMN knowledge_doc.expires_at; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON COLUMN public.knowledge_doc.expires_at IS '文档过期日期，NULL=永久有效；过期后定时任务自动下线';


--
-- Name: knowledge_kb; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.knowledge_kb OWNER TO postgres;

--
-- Name: TABLE knowledge_kb; Type: COMMENT; Schema: public; Owner: postgres
--

COMMENT ON TABLE public.knowledge_kb IS '知识库表，一个知识库对应一类业务文档集合';


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: knowledge_chunk knowledge_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_pkey PRIMARY KEY (id);


--
-- Name: knowledge_doc knowledge_doc_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.knowledge_doc
    ADD CONSTRAINT knowledge_doc_pkey PRIMARY KEY (id);


--
-- Name: knowledge_kb knowledge_kb_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.knowledge_kb
    ADD CONSTRAINT knowledge_kb_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_chunk_doc; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chunk_doc ON public.knowledge_chunk USING btree (doc_id);


--
-- Name: idx_chunk_kb_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chunk_kb_status ON public.knowledge_chunk USING btree (kb_id, doc_status, retrieval_weight) WHERE (((doc_status)::text = 'PUBLISHED'::text) AND (retrieval_weight > (0)::numeric));


--
-- Name: idx_chunk_parent; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chunk_parent ON public.knowledge_chunk USING btree (parent_chunk_id) WHERE (parent_chunk_id IS NOT NULL);


--
-- Name: idx_chunk_type; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_chunk_type ON public.knowledge_chunk USING btree (chunk_type) WHERE ((doc_status)::text = 'PUBLISHED'::text);


--
-- Name: idx_doc_expires; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_doc_expires ON public.knowledge_doc USING btree (expires_at) WHERE ((expires_at IS NOT NULL) AND ((status)::text <> 'DEPRECATED'::text));


--
-- Name: idx_doc_hash; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_doc_hash ON public.knowledge_doc USING btree (content_hash);


--
-- Name: idx_doc_kb_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_doc_kb_status ON public.knowledge_doc USING btree (kb_id, status);


--
-- Name: idx_kb_owner; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_kb_owner ON public.knowledge_kb USING btree (owner_id);


--
-- Name: idx_kb_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_kb_status ON public.knowledge_kb USING btree (status);


--
-- Name: knowledge_doc trg_doc_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_doc_updated BEFORE UPDATE ON public.knowledge_doc FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: knowledge_kb trg_kb_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_kb_updated BEFORE UPDATE ON public.knowledge_kb FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: knowledge_chunk knowledge_chunk_doc_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.knowledge_chunk
    ADD CONSTRAINT knowledge_chunk_doc_id_fkey FOREIGN KEY (doc_id) REFERENCES public.knowledge_doc(id) ON DELETE CASCADE;


--
-- Name: knowledge_doc knowledge_doc_kb_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.knowledge_doc
    ADD CONSTRAINT knowledge_doc_kb_id_fkey FOREIGN KEY (kb_id) REFERENCES public.knowledge_kb(id);


--
-- PostgreSQL database dump complete
--



-- ---------- 业务种子数据（知识库定义） ----------
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
-- Data for Name: knowledge_kb; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.knowledge_kb VALUES ('default', '默认知识库', '系统默认知识库，通用问答文档', '1001', 'ACTIVE', '2026-08-02 18:43:02.584814+00', '2026-08-02 18:43:02.584814+00');
INSERT INTO public.knowledge_kb VALUES ('faq', 'FAQ知识库', '常见问题解答', '1001', 'ACTIVE', '2026-08-02 18:43:02.584814+00', '2026-08-02 18:43:02.584814+00');
INSERT INTO public.knowledge_kb VALUES ('ticket', '历史工单库', '历史工单语料', '1001', 'ACTIVE', '2026-08-02 18:43:02.584814+00', '2026-08-02 18:43:02.584814+00');


--
-- Data for Name: knowledge_doc; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- Data for Name: knowledge_chunk; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- PostgreSQL database dump complete
--


