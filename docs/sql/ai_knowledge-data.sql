--
-- PostgreSQL database dump
--

\restrict 4DMtGH62pJahsgh8Ui6BdQ1VN25At6lLZyN2ux89cXvmOTklnqxnlpiCkP1CHV2

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

\unrestrict 4DMtGH62pJahsgh8Ui6BdQ1VN25At6lLZyN2ux89cXvmOTklnqxnlpiCkP1CHV2

