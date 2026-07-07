--
-- PostgreSQL database dump
--

\restrict oN1VZ9h4Kvh5w9cLsHTEirsYmaVGxQf5pBMQ5cLm26lVTm3pDE9TbCkHsg7d1RD

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

\unrestrict oN1VZ9h4Kvh5w9cLsHTEirsYmaVGxQf5pBMQ5cLm26lVTm3pDE9TbCkHsg7d1RD

