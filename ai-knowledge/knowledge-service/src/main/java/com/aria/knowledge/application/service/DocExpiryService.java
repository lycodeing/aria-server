package com.aria.knowledge.application.service;

import com.aria.knowledge.domain.model.DocStatus;
import com.aria.knowledge.domain.model.KnowledgeDoc;
import com.aria.knowledge.domain.repository.KnowledgeChunkRepository;
import com.aria.knowledge.domain.repository.KnowledgeDocRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * 文档过期清理应用服务。
 * 批量下线已过期文档，物理删除对应 chunk，保持向量库干净。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocExpiryService {

    private final KnowledgeDocRepository   docRepository;
    private final KnowledgeChunkRepository chunkRepository;

    /**
     * 批量下线过期文档（expires_at < today 且 status != DEPRECATED）。
     *
     * @param today 当前日期（由 Scheduler 传入，便于单测 Mock）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deprecateExpired(LocalDate today) {
        List<KnowledgeDoc> expired = docRepository.findExpired(today);
        if (CollectionUtils.isEmpty(expired)) {
            log.debug("今日无过期文档，date={}", today);
            return;
        }

        List<String> docIds = expired.stream().map(KnowledgeDoc::getId).toList();

        // 批量更新状态（一条 SQL，避免 N+1）
        docRepository.updateStatusBatch(docIds, DocStatus.DEPRECATED);

        // 逐个删除 chunk（各文档 chunk 数量不同，逐个事务更安全）
        docIds.forEach(docId -> {
            chunkRepository.deleteByDocId(docId);
            log.info("过期文档已下线，docId={}", docId);
        });

        log.info("过期文档批量下线完成，共处理 {} 篇，date={}", docIds.size(), today);
    }
}
