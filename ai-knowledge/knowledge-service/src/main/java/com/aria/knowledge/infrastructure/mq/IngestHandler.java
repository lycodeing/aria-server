package com.aria.knowledge.infrastructure.mq;

/**
 * 文档摄取责任链处理器接口。
 *
 * <p>实现类通过 Spring {@code @Order} 注解控制执行顺序，
 * {@link DocumentIngestPipeline} 按顺序遍历所有处理器。
 *
 * <p>处理器可调用 {@link IngestContext#abort()} 中断链，
 * 后续处理器将不再执行（用于幂等跳过、质量过滤空结果等场景）。
 */
public interface IngestHandler {

    /**
     * 执行当前处理步骤。
     *
     * @param ctx 贯穿全链的共享上下文，携带入参和各步骤产出
     */
    void handle(IngestContext ctx);
}
