package com.aria.conversation.infrastructure.observability;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * RAG 检索质量快照。
 *
 * <p>每次 FAQ 链路完成知识库检索后异步写入一行，记录 top1 分数、命中数与是否判定为 miss，
 * 作为知识覆盖度分析（哪些查询检索质量差、需要补充 KB）的数据源。
 */
@Data
@Builder
@TableName("cs_conversation.cs_rag_miss_log")
public class RagMissLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String queryText;

    /** 最高分 chunk 的 score；完全未命中（0 结果）时为 null */
    private Double top1Score;

    /** 本次检索命中 chunk 数 */
    private Integer hitCount;

    /** true = hitCount=0 或 top1Score 低于阈值 */
    private Boolean isMiss;

    /** top1 命中来源：VECTOR / FULL_TEXT / RERANK；空结果时为 null */
    private String source;

    /** 当前域 code，可为 null */
    private String domainCode;

    /** 本次识别到的意图 code，逗号分隔；可为 null。用于分析哪类意图 KB 覆盖度差 */
    private String intentCodes;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
