-- 迁移脚本：knowledge_chunk 表新增结构元数据列
-- 执行方式：psql -U {user} -d ai_customerservice -f migration-001-chunk-metadata.sql
-- 幂等：使用 IF NOT EXISTS，可重复执行

ALTER TABLE knowledge_chunk
    ADD COLUMN IF NOT EXISTS page_num      INTEGER      DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS section_title TEXT         DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS chunk_type    VARCHAR(20)  NOT NULL DEFAULT 'TEXT';

-- 按类型过滤的部分索引（支持后续 chunk_type 差异化检索）
CREATE INDEX IF NOT EXISTS idx_chunk_type ON knowledge_chunk(chunk_type)
    WHERE doc_status = 'PUBLISHED';

COMMENT ON COLUMN knowledge_chunk.page_num      IS '来源页码（1-based），PDF 逐页提取时填充，非 PDF 文档为 NULL';
COMMENT ON COLUMN knowledge_chunk.section_title IS '所属章节标题，从文档结构或标题行提取，无法检测时为 NULL';
COMMENT ON COLUMN knowledge_chunk.chunk_type    IS 'Chunk 内容类型：TEXT / TABLE / IMAGE_CAPTION';
