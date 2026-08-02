package com.aria.knowledge.infrastructure.splitter;

import com.aria.common.core.util.TokenUtils;
import com.aria.knowledge.domain.model.ChunkType;
import com.aria.knowledge.infrastructure.parser.ParsedBlock;
import com.aria.knowledge.infrastructure.parser.ParsedDocument;
import com.aria.knowledge.infrastructure.parser.ParsedPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 递归四层 Chunk 拆分器。
 * 拆分优先级（语义完整性优先于固定长度）：
 * 1. Markdown 标题边界（\n## / \n### / \n####）
 * 2. 段落边界（\n\n）
 * 3. 换行边界（\n）
 * 4. 句子边界（。！？/ . ! ?）
 *
 * <p>目标 chunk 大小：256~512 token，overlap 50 token 防止关键信息截断。
 */
@Slf4j
@Component
public class RecursiveChunkSplitter {

    /**
     * 四层递归拆分分隔符，优先级从高到低（含 #### 三级标题）
     */
    private static final List<String> SEPARATORS =
            List.of("\n## ", "\n### ", "\n#### ", "\n\n", "\n", "。", ".");
    /**
     * 单个 chunk 最大 token 数
     */
    private final int maxTokens;
    /**
     * 相邻 chunk 重叠 token 数，防止关键信息被截断
     */
    private final int overlapTokens;
    /**
     * 最小 chunk token 数，低于此值的切片与下一个切片合并，避免碎片化
     */
    private final int minTokens;

    public RecursiveChunkSplitter(
            @Value("${knowledge.chunk.max-tokens:512}") int maxTokens,
            @Value("${knowledge.chunk.overlap-tokens:50}") int overlapTokens,
            @Value("${knowledge.chunk.min-tokens:100}") int minTokens) {
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
        this.minTokens = Math.min(minTokens, maxTokens / 2);
    }

    /**
     * 按文件类型和 token 上限拆分文本。
     *
     * @param text     解析后的纯文本
     * @param fileType 文件类型（MARKDOWN 按标题优先，其余走通用递归）
     * @return 语义尽量完整的 chunk 列表，每个 chunk ≤ maxTokens token
     */
    public List<String> split(String text, String fileType) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if ("MARKDOWN".equalsIgnoreCase(fileType)) {
            return splitByHeading(text);
        }
        return splitRecursive(text, 0);
    }

    /**
     * 按 ParsedDocument 结构切片，保留页码/章节/类型元数据。
     * 每个 ParsedBlock 独立做递归切分，不跨块合并，避免跨章节语义混淆。
     *
     * @param doc      结构化文档（由 DocumentParser 各实现返回）
     * @param fileType 文件类型，传给底层递归切分逻辑
     * @return 带元数据的切片结果列表
     */
    public List<SplitResult> split(ParsedDocument doc, String fileType) {
        if (doc == null || doc.getPages() == null || doc.getPages().isEmpty()) {
            return List.of();
        }
        // Step 1：扁平化所有 block，并预合并相邻小 block（切分前合并，保持语义连贯）
        List<SplitResult> results = new ArrayList<>();
        List<FlatBlock> flatBlocks = flattenAndMergeBlocks(doc);

        // Step 2：对每个（可能已合并的）block 做递归切分
        for (FlatBlock fb : flatBlocks) {
            List<String> rawChunks = splitRecursive(fb.content, 0);
            for (String chunk : rawChunks) {
                results.add(SplitResult.builder()
                        .content(chunk)
                        .pageNum(fb.pageNum)
                        .sectionTitle(fb.sectionTitle)
                        .chunkType(fb.chunkType != null ? fb.chunkType : ChunkType.TEXT)
                        .build());
            }
        }
        // Step 3：合并碎片化小 chunk（切分后仍可能产生小碎片，兜底合并）
        results = mergeSmallChunks(results);
        log.debug("ParsedDocument 切片完成，fileType={}，总 chunk 数={}", fileType, results.size());
        return results;
    }

    /**
     * 将 ParsedDocument 的所有 page/block 扁平化，并合并相邻的小 block。
     *
     * <p>合并规则：当一个 block 的 token 数低于 {@link #minTokens} 时，与下一个 block 合并，
     * 直到合并后的 token 数接近 maxTokens 或没有更多 block。
     * 合并后保留第一个 block 的 sectionTitle（通常是章节标题），避免碎片化小 block
     * 产生无 sectionTitle 的独立 chunk。
     */
    private List<FlatBlock> flattenAndMergeBlocks(ParsedDocument doc) {
        List<FlatBlock> raw = new ArrayList<>();
        for (ParsedPage page : doc.getPages()) {
            if (page.getBlocks() == null) continue;
            for (ParsedBlock block : page.getBlocks()) {
                if (block.getContent() == null || block.getContent().isBlank()) continue;
                raw.add(new FlatBlock(
                        block.getContent().trim(),
                        page.getPageNum(),
                        block.getSectionTitle(),
                        block.getChunkType()));
            }
        }

        if (raw.size() <= 1) return raw;

        // 合并相邻小 block
        List<FlatBlock> merged = new ArrayList<>();
        for (FlatBlock fb : raw) {
            if (!merged.isEmpty()) {
                FlatBlock prev = merged.get(merged.size() - 1);
                if (TokenUtils.estimate(prev.content()) < minTokens) {
                    String combined = prev.content() + "\n" + fb.content();
                    if (TokenUtils.estimate(combined) <= maxTokens) {
                        merged.set(merged.size() - 1, new FlatBlock(
                                combined, prev.pageNum(), prev.sectionTitle(), prev.chunkType()));
                        continue;
                    }
                }
            }
            merged.add(fb);
        }
        return merged;
    }

    /**
     * 合并过小的切片：token 数低于 {@link #minTokens} 的切片与下一个切片合并，
     * 避免页脚、短句等碎片化 chunk 浪费 embedding 配额且降低检索质量。
     * 合并后若超过 maxTokens，保留原样不合并（避免引入超限 chunk）。
     */
    private List<SplitResult> mergeSmallChunks(List<SplitResult> chunks) {
        if (chunks.size() <= 1) return chunks;
        List<SplitResult> merged = new ArrayList<>();
        for (SplitResult chunk : chunks) {
            if (!merged.isEmpty()) {
                SplitResult prev = merged.get(merged.size() - 1);
                if (TokenUtils.estimate(prev.getContent()) < minTokens) {
                    String combined = prev.getContent() + "\n" + chunk.getContent();
                    // 仅当合并后不超限时才合并，否则保留小 chunk（最后手段）
                    if (TokenUtils.estimate(combined) <= maxTokens) {
                        merged.set(merged.size() - 1, SplitResult.builder()
                                .content(combined)
                                .pageNum(prev.getPageNum())
                                .sectionTitle(prev.getSectionTitle())
                                .chunkType(prev.getChunkType())
                                .build());
                        continue;
                    }
                }
            }
            merged.add(chunk);
        }
        return merged;
    }

    // ===== 私有方法 =====

    /**
     * Markdown 按标题边界优先拆分，超长段落再走递归。
     */
    private List<String> splitByHeading(String text) {
        List<String> result = new ArrayList<>();
        // 按 ## 前的换行符切分（保留标题内容）
        String[] sections = text.split("(?=\n##)");
        for (String section : sections) {
            if (section.isBlank()) continue;
            if (TokenUtils.estimate(section) <= maxTokens) {
                result.add(section.trim());
            } else {
                result.addAll(splitRecursive(section, 1)); // 从段落边界开始
            }
        }
        return result;
    }

    /**
     * 递归四层拆分：依次尝试各分隔符，直到 chunk 满足 token 上限。
     *
     * @param text         待拆分文本
     * @param separatorIdx 当前使用的分隔符层级（0=标题，1=段落，…）
     */
    private List<String> splitRecursive(String text, int separatorIdx) {
        // 已满足 token 上限，直接返回
        if (TokenUtils.estimate(text) <= maxTokens) {
            return List.of(text.trim());
        }
        // 所有分隔符层级已用尽，强制按 token 硬切（最后兜底）
        if (separatorIdx >= SEPARATORS.size()) {
            return hardSplit(text);
        }

        String sep = SEPARATORS.get(separatorIdx);
        String[] parts = text.split(Pattern.quote(sep), -1);

        // 当前分隔符无法拆分，尝试下一层
        if (parts.length <= 1) {
            return splitRecursive(text, separatorIdx + 1);
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            String candidate = current.isEmpty()
                    ? part
                    : current + sep + part;

            if (TokenUtils.estimate(candidate) > maxTokens && !current.isEmpty()) {
                // 当前积累已满，提交本 chunk（若单片仍超长会继续下钻）
                addChunk(result, current.toString(), separatorIdx);
                // overlap：保留末尾若干内容，防止语义截断
                current = new StringBuilder(getOverlap(current.toString()) + sep + part);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        // 提交最后一段（若单片仍超长会继续下钻）
        addChunk(result, current.toString(), separatorIdx);
        return result;
    }

    /**
     * 提交一个候选 chunk：若其自身仍超过 {@link #maxTokens}（例如本层分隔符存在但
     * 某单片自身过长），继续用下一层分隔符递归下钻，避免产出超限的 chunk。
     *
     * @param result       结果收集列表
     * @param chunk        候选 chunk 文本
     * @param separatorIdx 当前分隔符层级
     */
    private void addChunk(List<String> result, String chunk, int separatorIdx) {
        if (chunk == null || chunk.isBlank()) {
            return;
        }
        if (TokenUtils.estimate(chunk) > maxTokens) {
            result.addAll(splitRecursive(chunk, separatorIdx + 1));
        } else {
            result.add(chunk.trim());
        }
    }

    /**
     * 获取末尾 overlapTokens 的内容，用于下一个 chunk 的开头。
     */
    private String getOverlap(String text) {
        if (text.isBlank()) return "";
        // 按句子边界反向取 overlap
        String[] sentences = text.split("(?<=。)|(?<=\\.)|(?<=！)|(?<=？)|(?<=!)|(?<=\\?)");
        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.length - 1; i >= 0; i--) {
            String candidate = sentences[i] + overlap;
            if (TokenUtils.estimate(candidate) > overlapTokens) break;
            overlap.insert(0, sentences[i]);
        }
        return overlap.toString();
    }

    /**
     * 硬切兜底：按 maxTokens 滑动窗口切分（最后手段，会破坏语义）。
     * 使用 TokenUtils.estimate 精确控制 token 数，替代原来 *2 的经验系数，
     * 避免中英混合文本下切片过大或过小。
     */
    private List<String> hardSplit(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            // 逐步扩展窗口直到 token 超限，保留上一个合法边界
            int end = start + 1;
            while (end < text.length()
                    && TokenUtils.estimate(text.substring(start, end + 1)) <= maxTokens) {
                end++;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }
            start = end;
        }
        log.warn("触发硬切兜底，文本长度={}，建议检查文档格式", text.length());
        return result;
    }

    /**
     * 扁平化 block 的中间结构，便于合并
     */
    private record FlatBlock(String content, Integer pageNum, String sectionTitle, ChunkType chunkType) {
    }
}
