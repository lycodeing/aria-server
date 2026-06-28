-- ============================================================
-- V1：客服认证服务 Schema 初始化
-- cs_auth schema，独立于 ai-dev-platform 的 auth schema
-- 包含：用户/角色/权限/菜单/部门/数据权限
-- ============================================================

CREATE SCHEMA IF NOT EXISTS cs_auth;

-- updated_at 自动维护触发器函数
CREATE OR REPLACE FUNCTION cs_auth.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 1. 用户表
-- ============================================================
CREATE TABLE cs_auth.sys_user (
    id                   BIGSERIAL    PRIMARY KEY,
    username             VARCHAR(50)  NOT NULL,
    display_name         VARCHAR(100) NOT NULL,
    email                VARCHAR(200) NOT NULL,
    phone                VARCHAR(20),
    password_hash        VARCHAR(200) NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'active',
    provider             VARCHAR(30)  NOT NULL DEFAULT 'LOCAL',
    login_fail_count     INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMP,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    password_changed_at  TIMESTAMP,
    password_history     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    last_login_at        TIMESTAMP,
    last_login_ip        VARCHAR(50),
    dept_id              BIGINT,
    dept_name            VARCHAR(100),
    deleted_at           TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_cs_user_username ON cs_auth.sys_user(username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_cs_user_email    ON cs_auth.sys_user(email)    WHERE deleted_at IS NULL;
CREATE INDEX idx_cs_user_status        ON cs_auth.sys_user(status)   WHERE deleted_at IS NULL;
CREATE INDEX idx_cs_user_lastlogin     ON cs_auth.sys_user(last_login_at);

COMMENT ON TABLE  cs_auth.sys_user IS '用户表';
COMMENT ON COLUMN cs_auth.sys_user.password_hash IS 'BCrypt(cost=12) 密码哈希';
COMMENT ON COLUMN cs_auth.sys_user.password_history IS '最近5次密码哈希，防重用';
COMMENT ON COLUMN cs_auth.sys_user.locked_until IS '锁定截止时间，NULL=未锁定';
COMMENT ON COLUMN cs_auth.sys_user.dept_id   IS '主部门ID，冗余字段，与 sys_user_dept.is_primary=true 同步';
COMMENT ON COLUMN cs_auth.sys_user.dept_name IS '主部门名称，冗余字段，写入时同步更新';

CREATE TRIGGER trg_cs_user_updated BEFORE UPDATE ON cs_auth.sys_user
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- ============================================================
-- 2. 角色表
-- ============================================================
CREATE TABLE cs_auth.sys_role (
    id          BIGSERIAL    PRIMARY KEY,
    role_key    VARCHAR(50)  NOT NULL,
    role_name   VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_cs_role_key ON cs_auth.sys_role(role_key);
COMMENT ON TABLE cs_auth.sys_role IS '角色表';

CREATE TRIGGER trg_cs_role_updated BEFORE UPDATE ON cs_auth.sys_role
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- ============================================================
-- 3. 用户-角色关联表
-- ============================================================
CREATE TABLE cs_auth.sys_user_role (
    user_id    BIGINT    NOT NULL,
    role_id    BIGINT    NOT NULL,
    granted_at TIMESTAMP NOT NULL DEFAULT NOW(),
    granted_by BIGINT,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_cs_user_role_role ON cs_auth.sys_user_role(role_id);

-- ============================================================
-- 4. 权限定义表（接口/按钮级）
-- ============================================================
CREATE TABLE cs_auth.sys_permission (
    id              BIGSERIAL    PRIMARY KEY,
    permission_key  VARCHAR(100) NOT NULL,
    permission_name VARCHAR(200) NOT NULL,
    module          VARCHAR(50)  NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_cs_permission_key ON cs_auth.sys_permission(permission_key);
CREATE INDEX idx_cs_permission_module    ON cs_auth.sys_permission(module);

-- ============================================================
-- 5. 角色-权限关联表
-- ============================================================
CREATE TABLE cs_auth.sys_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_cs_role_permission_perm ON cs_auth.sys_role_permission(permission_id);

-- ============================================================
-- 6. 菜单/按钮表
-- 支持三种类型：目录(DIRECTORY) / 菜单(MENU) / 按钮(BUTTON)
-- ============================================================
CREATE TABLE cs_auth.sys_menu (
    id              BIGSERIAL     PRIMARY KEY,
    parent_id       BIGINT        NOT NULL DEFAULT 0,
    menu_type       VARCHAR(20)   NOT NULL,
    menu_name       VARCHAR(100)  NOT NULL,
    menu_key        VARCHAR(100)  NOT NULL,
    path            VARCHAR(200),
    component       VARCHAR(200),
    icon            VARCHAR(100),
    sort_order      INT           NOT NULL DEFAULT 0,
    is_visible      BOOLEAN       NOT NULL DEFAULT TRUE,
    is_cache        BOOLEAN       NOT NULL DEFAULT TRUE,
    is_external     BOOLEAN       NOT NULL DEFAULT FALSE,
    redirect        VARCHAR(200),
    permission_key  VARCHAR(100),
    status          VARCHAR(20)   NOT NULL DEFAULT 'active',
    remark          TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_cs_menu_key    ON cs_auth.sys_menu(menu_key);
CREATE INDEX idx_cs_menu_parent       ON cs_auth.sys_menu(parent_id);
CREATE INDEX idx_cs_menu_type_status  ON cs_auth.sys_menu(menu_type, status);

COMMENT ON TABLE  cs_auth.sys_menu IS '菜单与按钮权限表，支持多级树状结构';
COMMENT ON COLUMN cs_auth.sys_menu.menu_type      IS '菜单类型：DIRECTORY=目录，MENU=菜单页面，BUTTON=按钮/接口';
COMMENT ON COLUMN cs_auth.sys_menu.component      IS '前端Vue组件路径';
COMMENT ON COLUMN cs_auth.sys_menu.permission_key IS 'BUTTON类型对应的接口权限标识';
COMMENT ON COLUMN cs_auth.sys_menu.is_visible     IS 'false=隐藏菜单但路由仍可访问';

CREATE TRIGGER trg_cs_menu_updated BEFORE UPDATE ON cs_auth.sys_menu
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- ============================================================
-- 7. 角色-菜单关联表
-- ============================================================
CREATE TABLE cs_auth.sys_role_menu (
    role_id    BIGINT    NOT NULL,
    menu_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (role_id, menu_id)
);

CREATE INDEX idx_cs_role_menu_menu ON cs_auth.sys_role_menu(menu_id);

-- ============================================================
-- 8. 部门表（树形结构，用于数据权限）
-- ============================================================
CREATE TABLE cs_auth.sys_dept (
    id           BIGSERIAL    PRIMARY KEY,
    parent_id    BIGINT       NOT NULL DEFAULT 0,
    dept_name    VARCHAR(100) NOT NULL,
    dept_code    VARCHAR(50)  NOT NULL,
    ancestor_ids TEXT         NOT NULL DEFAULT '',
    sort_order   INT          NOT NULL DEFAULT 0,
    leader       VARCHAR(50),
    phone        VARCHAR(20),
    email        VARCHAR(100),
    status       VARCHAR(20)  NOT NULL DEFAULT 'active',
    deleted_at   TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_cs_dept_code ON cs_auth.sys_dept(dept_code) WHERE deleted_at IS NULL;
CREATE INDEX idx_cs_dept_parent     ON cs_auth.sys_dept(parent_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE  cs_auth.sys_dept IS '部门树，用于数据权限的组织维度过滤';
COMMENT ON COLUMN cs_auth.sys_dept.ancestor_ids IS '祖先ID路径，格式：0,parentId,...,deptId';

CREATE TRIGGER trg_cs_dept_updated BEFORE UPDATE ON cs_auth.sys_dept
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();

-- ============================================================
-- 9. 用户-部门关联表
-- ============================================================
CREATE TABLE cs_auth.sys_user_dept (
    user_id    BIGINT    NOT NULL,
    dept_id    BIGINT    NOT NULL,
    is_primary BOOLEAN   NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, dept_id)
);

CREATE INDEX idx_cs_user_dept_dept ON cs_auth.sys_user_dept(dept_id);
COMMENT ON TABLE cs_auth.sys_user_dept IS '用户-部门关联，支持兼职，is_primary=true 为主部门';

-- ============================================================
-- 10. 角色数据权限范围表
-- ============================================================
CREATE TABLE cs_auth.sys_role_data_scope (
    id              BIGSERIAL    PRIMARY KEY,
    role_id         BIGINT       NOT NULL UNIQUE,
    scope_type      VARCHAR(30)  NOT NULL DEFAULT 'SELF',
    custom_dept_ids JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  cs_auth.sys_role_data_scope IS '角色数据权限范围：ALL/DEPT_TREE/DEPT_ONLY/CUSTOM_DEPT/SELF';

CREATE TRIGGER trg_cs_role_scope_updated BEFORE UPDATE ON cs_auth.sys_role_data_scope
    FOR EACH ROW EXECUTE FUNCTION cs_auth.set_updated_at();
