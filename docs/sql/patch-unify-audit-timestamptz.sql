-- ============================================================================
-- Patch: 统一审计时间列为 timestamptz（配合 BaseDO + OffsetDateTime 全局统一）
-- ----------------------------------------------------------------------------
-- 背景：
--   实体审计字段 created_at / updated_at 已抽取到 common-core 的 BaseDO，
--   Java 类型统一为 OffsetDateTime，并由 MyBatis-Plus 全局自动填充。
--   为使 DB 与代码类型一致，需把仍为 `timestamp without time zone` 的审计列
--   迁移为 `timestamp with time zone`（timestamptz）。
--
-- 数据库分布：
--   - aria_cs 库：schema cs_auth + cs_conversation（本脚本处理）
--   - aria_knowledge 库：所有审计列已是 timestamptz，无需处理
--
-- 时区换算：
--   现网 Postgres(容器)默认 TZ=UTC，JVM 未设置 serverTimezone / TZ，
--   历史 LocalDateTime.now() 写入的裸时间戳即 UTC 墙钟。
--   因此用 `col AT TIME ZONE 'UTC'` 把裸值按 UTC 解释为时刻，值不发生偏移。
--   若你的部署 Postgres/JVM 时区不是 UTC，请把下方 v_source_tz 改为实际时区。
--
-- 幂等性：
--   仅当列当前为 `timestamp without time zone` 时才执行 ALTER，可安全重复运行。
--
-- 执行方式（连接到 aria_cs 库）：
--   psql -h <host> -U postgres -d aria_cs -f patch-unify-audit-timestamptz.sql
-- ============================================================================

DO $$
DECLARE
    -- 历史裸时间戳所代表的时区；现网为 UTC。
    v_source_tz text := 'UTC';
    r           record;
    v_type      text;
BEGIN
    FOR r IN
        SELECT s AS schema_name, t AS table_name, c AS column_name
        FROM (VALUES
            -- ---- cs_auth ----
            ('cs_auth', 'ai_model_config',     'created_at'),
            ('cs_auth', 'ai_model_config',     'updated_at'),
            ('cs_auth', 'sys_dept',            'created_at'),
            ('cs_auth', 'sys_dept',            'updated_at'),
            ('cs_auth', 'sys_menu',            'created_at'),
            ('cs_auth', 'sys_menu',            'updated_at'),
            ('cs_auth', 'sys_permission',      'created_at'),
            ('cs_auth', 'sys_role',            'created_at'),
            ('cs_auth', 'sys_role',            'updated_at'),
            ('cs_auth', 'sys_role_data_scope', 'created_at'),
            ('cs_auth', 'sys_role_data_scope', 'updated_at'),
            ('cs_auth', 'sys_role_menu',       'created_at'),
            ('cs_auth', 'sys_user',            'created_at'),
            ('cs_auth', 'sys_user',            'updated_at'),
            ('cs_auth', 'sys_user_dept',       'created_at'),
            ('cs_auth', 'system_config',       'created_at'),
            ('cs_auth', 'system_config',       'updated_at'),
            -- ---- cs_conversation ----
            ('cs_conversation', 'cs_domain',        'created_at'),
            ('cs_conversation', 'cs_domain',        'updated_at'),
            ('cs_conversation', 'cs_pending_slot',  'created_at'),
            ('cs_conversation', 'cs_tool',          'created_at'),
            ('cs_conversation', 'cs_tool',          'updated_at'),
            ('cs_conversation', 'cs_tool_call_log', 'created_at')
        ) AS x(s, t, c)
    LOOP
        SELECT data_type
          INTO v_type
          FROM information_schema.columns
         WHERE table_schema = r.schema_name
           AND table_name   = r.table_name
           AND column_name  = r.column_name;

        IF v_type IS NULL THEN
            RAISE NOTICE 'SKIP  %.%.% (列不存在)', r.schema_name, r.table_name, r.column_name;
        ELSIF v_type = 'timestamp with time zone' THEN
            RAISE NOTICE 'SKIP  %.%.% (已是 timestamptz)', r.schema_name, r.table_name, r.column_name;
        ELSIF v_type = 'timestamp without time zone' THEN
            EXECUTE format(
                'ALTER TABLE %I.%I ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE %L',
                r.schema_name, r.table_name, r.column_name, r.column_name, v_source_tz
            );
            RAISE NOTICE 'ALTER %.%.% -> timestamptz (源时区=%)',
                r.schema_name, r.table_name, r.column_name, v_source_tz;
        ELSE
            RAISE WARNING 'UNEXPECTED %.%.% 当前类型=% ，未处理', r.schema_name, r.table_name, r.column_name, v_type;
        END IF;
    END LOOP;
END $$;

-- ============================================================================
-- 校验：执行后确认目标列均为 timestamp with time zone
-- ============================================================================
-- SELECT table_schema, table_name, column_name, data_type
--   FROM information_schema.columns
--  WHERE (table_schema, table_name, column_name) IN (
--        ('cs_auth','ai_model_config','created_at'),
--        ('cs_conversation','cs_tool','updated_at')
--        -- ... 其余同理
--  )
--  ORDER BY table_schema, table_name, column_name;
