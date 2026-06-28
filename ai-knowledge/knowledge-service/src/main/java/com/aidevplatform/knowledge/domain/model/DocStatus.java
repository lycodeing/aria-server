package com.aidevplatform.knowledge.domain.model;

/**
 * 文档状态枚举。
 * DRAFT     = 草稿，上传完成等待审核
 * REVIEW    = 审核中（人工或自动）
 * PUBLISHED = 已发布，chunk 已进入向量库
 * DEPRECATED = 已下线，chunk 已物理删除
 * FAILED    = 摄取失败，需要人工处理
 */
public enum DocStatus {
    DRAFT, REVIEW, PUBLISHED, DEPRECATED, FAILED
}
