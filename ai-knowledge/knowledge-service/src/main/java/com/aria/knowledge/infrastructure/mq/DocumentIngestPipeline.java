package com.aria.knowledge.infrastructure.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文档摄取管道（责任链模式）。
 *
 * <p>原有 8 步硬编码逻辑已拆分为独立的 {@link IngestHandler} 实现类，
 * Spring 通过 {@code @Order} 自动排序后注入，Pipeline 本身只负责驱动链条执行。
 *
 * <p>Handler 执行顺序（@Order 值）：
 * <ol>
 *   <li>IdempotencyCheckHandler  (1)  — 幂等校验，终态文档跳过</li>
 *   <li>LoadContentHandler       (2)  — MinIO 下载字节流</li>
 *   <li>ParseHandler             (3)  — 多格式文档解析（模板方法）</li>
 *   <li>SplitHandler             (4)  — 递归语义切片</li>
 *   <li>QualityFilterHandler     (5)  — 质量过滤，空结果时 abort</li>
 *   <li>BuildChunksHandler       (6)  — 构建 KnowledgeChunk 领域对象</li>
 *   <li>EmbedHandler             (7)  — BGE-M3 批量向量化</li>
 *   <li>PersistHandler           (8)  — 写入 pgvector（幂等删旧写新）</li>
 *   <li>StatusUpdateHandler      (9)  — 更新文档状态为 PUBLISHED（状态模式）</li>
 * </ol>
 *
 * <p>扩展指引：新增处理步骤（如 Contextual Retrieval）只需：
 * <ol>
 *   <li>新建实现 {@link IngestHandler} 的 @Component</li>
 *   <li>用 @Order 指定插入位置，如 @Order(65) 插入 Embed 和 Persist 之间</li>
 *   <li>无需修改本类</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestPipeline {

    /** Spring 按 @Order 自动排序注入，保证 Handler 执行顺序 */
    private final List<IngestHandler> handlers;

    /**
     * 执行完整摄取管道。
     * 事务保证：任意 Handler 抛出异常时全部回滚。
     * 任意 Handler 调用 {@link IngestContext#abort()} 时，后续 Handler 被跳过但事务正常提交。
     *
     * @param event 文档摄取事件（来自 RabbitMQ knowledge.doc.ingest.queue）
     */
    @Transactional(rollbackFor = Exception.class)
    public void process(DocIngestEvent event) {
        log.info("[Pipeline] 开始摄取 docId={} fileType={}", event.getDocId(), event.getFileType());

        IngestContext ctx = IngestContext.builder().event(event).build();

        for (IngestHandler handler : handlers) {
            handler.handle(ctx);
            if (ctx.isAborted()) {
                log.info("[Pipeline] 责任链在 [{}] 处中断，docId={}",
                    handler.getClass().getSimpleName(), event.getDocId());
                break;
            }
        }

        log.info("[Pipeline] 摄取结束 docId={}", event.getDocId());
    }
}

