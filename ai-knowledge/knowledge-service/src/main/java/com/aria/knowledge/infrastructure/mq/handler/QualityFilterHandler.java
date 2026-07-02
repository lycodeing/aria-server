package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import com.aria.knowledge.domain.service.ChunkQualityDomainService;
import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import com.aria.knowledge.infrastructure.splitter.SplitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 责任链 Step 4：过滤低质量切片，空结果时中断链。
 * 从 {@link IngestContext#splits} 读取，结果写入 {@link IngestContext#qualifiedSplits}。
 */
@Slf4j
@Order(5)
@Component
@RequiredArgsConstructor
public class QualityFilterHandler implements IngestHandler {

    private final ChunkQualityDomainService qualityService;
    private final KnowledgeDocRepository   docRepository;

    @Override
    public void handle(IngestContext ctx) {
        String docId = ctx.getEvent().getDocId();
        List<SplitResult> qualified = ctx.getSplits().stream()
            .filter(s -> qualityService.passable(s.getContent()))
            .toList();

        log.info("[质量过滤] docId={}，原始={}，合格={}",
            docId, ctx.getSplits().size(), qualified.size());

        if (qualified.isEmpty()) {
            log.warn("[质量过滤] 全部切片未通过质量门控，标记为 FAILED docId={}", docId);
            // 全量过滤：文档内容无法提取有效 chunk（空白/乱码/图片扫描件等），
            // 必须标记为 FAILED 而非 PUBLISHED，避免用户误以为文档已成功入库
            docRepository.updateStatusBatch(List.of(docId), DocStatus.FAILED);
            ctx.abort();
            return;
        }
        ctx.setQualifiedSplits(qualified);
    }
}
