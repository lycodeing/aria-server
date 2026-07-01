package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.model.KnowledgeChunk;
import com.aria.knowledge.infrastructure.mq.DocIngestEvent;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.parser.ChunkType;
import com.aria.knowledge.infrastructure.splitter.SplitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BuildChunksHandler 单元测试。
 * 验证 SplitResult 中的 pageNum / sectionTitle / chunkType 是否正确注入到 KnowledgeChunk。
 * 不依赖 Spring 上下文，纯 POJO 测试。
 */
class BuildChunksHandlerTest {

    private BuildChunksHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BuildChunksHandler();
    }

    @Test
    @DisplayName("SplitResult 的元数据（pageNum/sectionTitle/chunkType）应正确注入 KnowledgeChunk")
    void handle_injectsMetadataFromSplitResult() {
        SplitResult split = SplitResult.builder()
            .content("净利润同比增长 23.5%，达到 48 亿元，全年经营状况良好。")
            .pageNum(12)
            .sectionTitle("第三章 经营业绩")
            .chunkType(ChunkType.TEXT)
            .build();

        DocIngestEvent event = DocIngestEvent.builder()
            .docId("doc-001")
            .kbId("kb-001")
            .fileType("PDF")
            .storagePath("oss://bucket/docs/doc-001/report.pdf")
            .build();

        IngestContext ctx = IngestContext.builder()
            .event(event)
            .qualifiedSplits(List.of(split))
            .build();

        handler.handle(ctx);

        assertThat(ctx.getChunks()).hasSize(1);
        KnowledgeChunk chunk = ctx.getChunks().get(0);
        assertThat(chunk.getPageNum()).isEqualTo(12);
        assertThat(chunk.getSectionTitle()).isEqualTo("第三章 经营业绩");
        assertThat(chunk.getChunkType()).isEqualTo(ChunkType.TEXT);
        assertThat(chunk.getDocId()).isEqualTo("doc-001");
        assertThat(chunk.getKbId()).isEqualTo("kb-001");
        assertThat(chunk.getContent()).isEqualTo(split.getContent());
        assertThat(chunk.getTokenCount()).isGreaterThan(0);
        assertThat(chunk.getRetrievalWeight()).isNotNull();
    }

    @Test
    @DisplayName("TABLE 类型切片应保留 TABLE chunkType")
    void handle_tableChunkType_preserved() {
        SplitResult split = SplitResult.builder()
            .content("年份    净利润    营收\n2023    38亿    120亿\n2024    48亿    150亿")
            .pageNum(5)
            .sectionTitle("财务数据")
            .chunkType(ChunkType.TABLE)
            .build();

        DocIngestEvent event = DocIngestEvent.builder()
            .docId("doc-002").kbId("kb-001")
            .fileType("PDF").storagePath("oss://bucket/docs/doc-002/report.pdf")
            .build();

        IngestContext ctx = IngestContext.builder()
            .event(event)
            .qualifiedSplits(List.of(split))
            .build();

        handler.handle(ctx);

        assertThat(ctx.getChunks().get(0).getChunkType()).isEqualTo(ChunkType.TABLE);
    }

    @Test
    @DisplayName("空切片列表应产生空 chunk 列表（不抛异常）")
    void handle_emptyQualifiedSplits_producesEmptyChunks() {
        DocIngestEvent event = DocIngestEvent.builder()
            .docId("doc-003").kbId("kb-001")
            .fileType("PDF").storagePath("oss://bucket/docs/doc-003/empty.pdf")
            .build();

        IngestContext ctx = IngestContext.builder()
            .event(event)
            .qualifiedSplits(List.of())
            .build();

        handler.handle(ctx);

        assertThat(ctx.getChunks()).isEmpty();
    }
}
