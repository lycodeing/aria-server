package com.aria.knowledge.domain.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chunk 质量领域服务（Domain Service）。
 * 职责：判断 chunk 是否具备入库资格，过滤低质量内容。
 * 纯业务规则，无框架依赖，无数据库访问。
 *
 * <p>质量评估维度（启发式规则，后续可替换为 LLM 评分）：
 * <ul>
 *   <li>信息密度：有效字符（汉字/字母/数字/标点）占比</li>
 *   <li>最低长度：过短的 chunk 语义不完整，直接过滤</li>
 *   <li>非空校验：纯空白 chunk 直接过滤</li>
 * </ul>
 */
@Slf4j
@Service
public class ChunkQualityDomainService {

    /** 有效字符占比最低阈值（低于此值视为噪声内容） */
    private static final double MIN_DENSITY = 0.5;
    /** chunk 最低字符数（少于此值视为信息不足） */
    private static final int    MIN_LENGTH  = 20;

    /**
     * 过滤低质量 chunk，返回通过质量门控的列表。
     *
     * @param chunks 待过滤的 chunk 文本列表
     * @return 通过质量门控的 chunk 列表
     */
    public List<String> filter(List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> qualified = chunks.stream()
            .filter(this::passable)
            .toList();
        log.debug("Chunk 质量过滤：输入={}，通过={}，过滤={}", 
            chunks.size(), qualified.size(), chunks.size() - qualified.size());
        return qualified;
    }

    /**
     * 判断单个 chunk 是否通过质量门控。
     *
     * @param chunk 待检测的 chunk 文本
     * @return true=通过，false=不通过
     */
    public boolean passable(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }
        // 长度检查
        if (chunk.length() < MIN_LENGTH) {
            return false;
        }
        // 信息密度：有效字符占比
        long validCount = chunk.chars()
            .filter(c -> Character.isLetterOrDigit(c)
                || c == '。' || c == '，' || c == '、'
                || c == '.' || c == ',' || c == '!')
            .count();
        double density = (double) validCount / chunk.length();
        return density >= MIN_DENSITY;
    }
}
