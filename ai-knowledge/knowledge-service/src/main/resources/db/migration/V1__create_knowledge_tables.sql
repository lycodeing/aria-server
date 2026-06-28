-- ============================================================
-- V1: 知识库服务初始化 Schema
-- 包含：知识库、文档、Chunk（pgvector 向量存储）
-- ============================================================

-- 启用 pgvector 扩展（需 PostgreSQL 管理员权限）
CREATE EXTENSION IF NOT EXISTS vector;
-- 启用中文分词扩展（需提前安装 pg_jieba，未安装时全文检索降级为 simple 字典）
-- CREATE EXTENSION IF NOT EXISTS pg_jieba;

-- updated_at 自动维护函数
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 1. 知识库表（knowledge_kb）
-- ============================================================
CREATE TABLE knowledge_kb (
    id          VARCHAR(36)   PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description TEXT,
    owner_id    VARCHAR(36)   NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_kb_owner  ON knowledge_kb(owner_id);
CREATE INDEX idx_kb_status ON knowledge_kb(status);

COMMENT ON TABLE  knowledge_kb IS '知识库表，一个知识库对应一类业务文档集合';
COMMENT ON COLUMN knowledge_kb.owner_id IS '知识库所有者（用户ID）';

CREATE TRIGGER trg_kb_updated BEFORE UPDATE ON knowledge_kb
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
-- 2. 文档表（knowledge_doc）
-- 状态机：DRAFT → REVIEW → PUBLISHED → DEPRECATED
-- ============================================================
CREATE TABLE knowledge_doc (
    id             VARCHAR(36)   PRIMARY KEY,
    kb_id          VARCHAR(36)   NOT NULL REFERENCES knowledge_kb(id),
    file_name      VARCHAR(255)  NOT NULL,
    -- 文件类型：MARKDOWN / PDF / HTML / DOCX / TICKET
    file_type      VARCHAR(20)   NOT NULL,
    storage_path   VARCHAR(500)  NOT NULL,
    -- SHA-256 内容哈希，用于变更检测，相同则跳过重摄取
    content_hash   VARCHAR(64)   NOT NULL DEFAULT 'pending',
    -- 状态机：DRAFT=草稿 / REVIEW=审核中 / PUBLISHED=已发布 / DEPRECATED=已下线 / FAILED=摄取失败
    status         VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    version        VARCHAR(50),
    effective_from DATE,
    -- 过期日期，NULL=永久有效，定时任务扫描此字段自动下线
    expires_at     DATE,
    uploader_id    VARCHAR(36)   NOT NULL,
    reviewer_id    VARCHAR(36),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_kb_status ON knowledge_doc(kb_id, status);
CREATE INDEX idx_doc_expires   ON knowledge_doc(expires_at) WHERE expires_at IS NOT NULL AND status != 'DEPRECATED';
CREATE INDEX idx_doc_hash      ON knowledge_doc(content_hash);

COMMENT ON TABLE  knowledge_doc IS '知识库文档表，支持多格式文件';
COMMENT ON COLUMN knowledge_doc.content_hash  IS 'SHA-256(文件内容)，变更检测，相同内容跳过重摄取';
COMMENT ON COLUMN knowledge_doc.expires_at    IS '文档过期日期，NULL=永久有效；过期后由定时任务自动下线';
COMMENT ON COLUMN knowledge_doc.storage_path  IS '文件存储路径（OSS/MinIO），格式：oss://bucket/path/file.pdf';

CREATE TRIGGER trg_doc_updated BEFORE UPDATE ON knowledge_doc
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
-- 3. Chunk 表（knowledge_chunk）
-- 核心：pgvector 向量存储，支持余弦相似度检索
-- 冗余 kb_id / doc_status，避免查询时 JOIN knowledge_doc
-- ============================================================
CREATE TABLE knowledge_chunk (
    id                     VARCHAR(36)   PRIMARY KEY,
    doc_id                 VARCHAR(36)   NOT NULL REFERENCES knowledge_doc(id),
    -- 冗余字段：避免检索时 JOIN knowledge_doc
    kb_id                  VARCHAR(36)   NOT NULL,
    doc_status             VARCHAR(20)   NOT NULL DEFAULT 'PUBLISHED',
    -- Parent-Child 双层存储：小 chunk 用于检索，大 chunk 用于生成
    parent_chunk_id        VARCHAR(36),
    -- 面包屑上下文，格式：产品手册 > 快速开始 > 安装配置
    breadcrumb             TEXT,
    content                TEXT          NOT NULL,
    -- pgvector 格式：[0.1,0.2,...,0.9]，BGE-M3 输出 1024 维
    content_vector         vector(1024)  NOT NULL,
    token_count            INTEGER       NOT NULL,
    -- 用户反馈降权：被踩 ≥3 次下调，≥5 次暂停检索，≥10 次自动下线
    retrieval_weight       DECIMAL(3,2)  NOT NULL DEFAULT 1.0,
    feedback_downvotes     INTEGER       NOT NULL DEFAULT 0,
    -- LLM 生成的假设性问题列表，HyDE 检索增强
    hypothetical_questions JSONB,
    -- 来源 URL、文档版本等元数据
    metadata               JSONB,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- 向量检索索引（HNSW 算法，牺牲内存换查询速度）
-- m=16：每层最大连接数；ef_construction=64：构建时搜索宽度
CREATE INDEX idx_chunk_vector ON knowledge_chunk
    USING hnsw (content_vector vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 单表检索辅助索引：覆盖 WHERE kb_id=? AND doc_status='PUBLISHED' AND retrieval_weight>0
CREATE INDEX idx_chunk_kb_status ON knowledge_chunk (kb_id, doc_status, retrieval_weight)
    WHERE doc_status = 'PUBLISHED' AND retrieval_weight > 0;

CREATE INDEX idx_chunk_doc    ON knowledge_chunk(doc_id);
CREATE INDEX idx_chunk_parent ON knowledge_chunk(parent_chunk_id) WHERE parent_chunk_id IS NOT NULL;

COMMENT ON TABLE  knowledge_chunk IS 'Chunk 向量表，核心检索单元，使用 pgvector 存储 embedding';
COMMENT ON COLUMN knowledge_chunk.kb_id            IS '冗余字段，来自 knowledge_doc.kb_id，避免检索时 JOIN';
COMMENT ON COLUMN knowledge_chunk.doc_status       IS '冗余字段，随 knowledge_doc.status 同步，避免检索时 JOIN';
COMMENT ON COLUMN knowledge_chunk.content_vector   IS 'BGE-M3 生成的 1024 维 embedding，pgvector 格式';
COMMENT ON COLUMN knowledge_chunk.retrieval_weight IS '检索权重 0~1.0，被用户反馈踩多次时下调至 0 则停止检索';
COMMENT ON COLUMN knowledge_chunk.parent_chunk_id  IS 'Parent-Child 架构：检索用小 chunk，生成用父 chunk（更完整的上下文）';
COMMENT ON COLUMN knowledge_chunk.hypothetical_questions IS '假设性问题 JSON 数组，HyDE 增强召回率';
