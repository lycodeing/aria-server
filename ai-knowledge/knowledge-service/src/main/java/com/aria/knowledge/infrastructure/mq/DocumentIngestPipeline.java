package com.aria.knowledge.infrastructure.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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
public class DocumentIngestPipeline {

    /**
     * 事务边界阈值：{@code @Order} 值 &ge; 此值的 Handler 才是写 DB 步骤（PersistHandler=8、
     * StatusUpdateHandler=9），只有它们需要包进数据库事务；阈值之前的步骤（下载/解析/切片/
     * 向量化）为纯计算或外部 IO，不得持有 DB 连接。
     *
     * <p>KNOW-1 修复：原实现用方法级 {@code @Transactional} 包裹整条链，导致 EmbedHandler
     * 发起的 BGE-M3 HTTP 调用（可能数秒）期间 DB 连接被占用不释放，高并发时连接池耗尽。
     */
    private static final int TX_ORDER_THRESHOLD = 8;

    /** 不需要事务的前段 Handler（下载/解析/切片/向量化），按 @Order 排序 */
    private final List<IngestHandler> prePersistHandlers = new ArrayList<>();
    /** 需要事务的写库段 Handler（Persist/StatusUpdate），按 @Order 排序 */
    private final List<IngestHandler> persistHandlers = new ArrayList<>();

    private final TransactionTemplate transactionTemplate;

    /**
     * 按 {@code @Order} 值把注入的 Handler 划分为「事务外前段」与「事务内写库段」。
     * Spring 已按 @Order 升序注入 {@code handlers}，此处仅按阈值分桶。
     *
     * @param handlers  Spring 按 @Order 自动排序注入的全部 Handler
     * @param txManager 平台事务管理器，用于构建仅包裹写库段的 TransactionTemplate
     */
    public DocumentIngestPipeline(List<IngestHandler> handlers,
                                  PlatformTransactionManager txManager) {
        this.transactionTemplate = new TransactionTemplate(txManager);
        for (IngestHandler handler : handlers) {
            Integer order = OrderUtils.getOrder(handler.getClass());
            int orderValue = order != null ? order : Integer.MAX_VALUE;
            if (orderValue >= TX_ORDER_THRESHOLD) {
                persistHandlers.add(handler);
            } else {
                prePersistHandlers.add(handler);
            }
        }
    }

    /**
     * 执行完整摄取管道。
     *
     * <p>事务边界：前段（下载/解析/切片/质量过滤/构建/向量化）在<b>事务外</b>执行，不占用
     * DB 连接；写库段（Persist/StatusUpdate）在<b>单个事务</b>内执行，任一步骤抛异常则整体
     * 回滚，由 Spring AMQP 触发 MQ 重试。前段任一 Handler {@link IngestContext#abort()}
     * 时跳过写库段（幂等跳过/质量过滤空结果场景）。
     *
     * @param event 文档摄取事件（来自 RabbitMQ knowledge.doc.ingest.queue）
     */
    public void process(DocIngestEvent event) {
        log.info("[Pipeline] 开始摄取 docId={} fileType={}", event.getDocId(), event.getFileType());

        IngestContext ctx = IngestContext.builder().event(event).build();

        // 前段：事务外执行，避免长耗时向量化 HTTP 调用占用 DB 连接
        for (IngestHandler handler : prePersistHandlers) {
            handler.handle(ctx);
            if (ctx.isAborted()) {
                log.info("[Pipeline] 责任链在 [{}] 处中断，跳过写库，docId={}",
                    handler.getClass().getSimpleName(), event.getDocId());
                return;
            }
        }

        // 写库段：单个事务内执行，保证 chunk 写入与状态更新原子性
        transactionTemplate.executeWithoutResult(status -> {
            for (IngestHandler handler : persistHandlers) {
                handler.handle(ctx);
                if (ctx.isAborted()) {
                    log.info("[Pipeline] 写库段在 [{}] 处中断，docId={}",
                        handler.getClass().getSimpleName(), event.getDocId());
                    break;
                }
            }
        });

        log.info("[Pipeline] 摄取结束 docId={}", event.getDocId());
    }
}

