package com.aria.conversation.infrastructure.observability;

import com.aria.conversation.infrastructure.knowledge.KnowledgeSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 检索质量记录器。
 *
 * <p>接收原始检索结果，计算 {@code isMiss} / {@code top1Score}，构建实体后异步落库到
 * {@code cs_conversation.cs_rag_miss_log}，供管理台分析知识库覆盖度、驱动 KB 补充。
 *
 * <p><b>为什么是普通 {@code @Component} 而非继承 MyBatis-Plus {@code ServiceImpl}：</b>
 * miss 阈值通过字段注入（{@code @Value}）获取。若继承 {@code ServiceImpl} 并用自定义构造函数 +
 * {@code @Value} 构造注入，框架实例化时不会触发该注入，阈值会静默取 {@code 0.0}，
 * 导致所有检索都被判成 miss。这里用普通组件 + 字段注入规避该问题。
 *
 * <p>写入使用独立的 {@code observabilityExecutor}（DiscardPolicy），队列满时直接丢弃，
 * 绝不回退到调用方线程（SSE 主线程），主链路不受影响。
 */
@Slf4j
@Component
public class RagQualityRecorder {

    /**
     * RAG 命中判定阈值：top1Score 低于此值判定为 miss。
     * 默认 0.5，可通过 {@code aria.rag.miss-threshold} 覆盖。
     */
    @Value("${aria.rag.miss-threshold:0.5}")
    private double missThreshold;

    private final RagMissLogMapper missLogMapper;

    public RagQualityRecorder(RagMissLogMapper missLogMapper) {
        this.missLogMapper = missLogMapper;
    }

    /**
     * 异步记录一次 RAG 检索的质量快照。
     *
     * @param sessionId   当前会话 ID
     * @param query       用户原始问题
     * @param hits        检索命中列表（可为空）
     * @param domainCode  当前域 code（可为 null）
     * @param intentCodes 本次识别到的意图 code 列表（可为 null）
     */
    @Async("observabilityExecutor")
    public void logAsync(String sessionId, String query,
                         List<KnowledgeSearchResult.Hit> hits,
                         String domainCode, List<String> intentCodes) {
        try {
            boolean empty = hits == null || hits.isEmpty();
            Double top1Score = empty ? null : hits.get(0).getScore();
            boolean isMiss = empty || top1Score < missThreshold;

            RagMissLogEntity entity = RagMissLogEntity.builder()
                    .sessionId(sessionId)
                    .queryText(query)
                    .top1Score(top1Score)
                    .hitCount(empty ? 0 : hits.size())
                    .isMiss(isMiss)
                    .source(empty ? null : hits.get(0).getSource())
                    .domainCode(domainCode)
                    .intentCodes(intentCodes == null ? null : String.join(",", intentCodes))
                    .build();

            missLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("[RagQuality] 检索质量记录写入失败 session={} query={}", sessionId, query, e);
        }
    }
}
