package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.exception.BusinessException;
import com.aria.knowledge.domain.model.ChunkType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP 压缩包解析器。
 *
 * <p>解压后按成员文件后缀递归分发到 {@link MultiFormatParser} 解析各成员，
 * 将所有成员的页合并为单个 {@link ParsedDocument}，页码连续递增。
 * 不支持的成员文件（图片、表格等）跳过并记录日志，不中断整体解析。
 *
 * <p>单个成员解析失败时记录告警并跳过，保证其余成员正常入库。
 * 成员文件的原始 sectionTitle 保留，为空时回填成员文件名，便于检索追溯来源。
 */
@Slf4j
@Component
public class ZipParser extends AbstractDocumentParser {

    private static final int BUFFER_SIZE  = 8192;
    /** 条目数上限，防御 zip 炸弹 */
    private static final int MAX_ENTRIES  = 1000;
    /** 单个成员解压后大小上限（100MB），防御 zip 炸弹 */
    private static final int MAX_MEMBER_BYTES = 100 * 1024 * 1024;
    /** 单个 ZIP 累计解压总量上限（256MB），防御"众多中等成员累积撑爆堆"型 zip 炸弹 */
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    /** 嵌套 ZIP 最大递归深度，防止 zip-of-zip 爆栈 */
    private static final int MAX_NESTING_DEPTH = 5;

    private final MultiFormatParser multiFormatParser;

    /**
     * 递归深度跟踪：每次进入 doParse 自增，退出自减。
     * 通过 ThreadLocal 传递，无需修改 MultiFormatParser.parse 签名。
     */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /**
     * {@code @Lazy} 打破循环依赖：MultiFormatParser 注入所有 DocumentParser（含本类），
     * 本类又需要 MultiFormatParser 分发内部成员文件，形成环。Lazy 注入代理解决。
     */
    public ZipParser(@Lazy MultiFormatParser multiFormatParser) {
        this.multiFormatParser = multiFormatParser;
    }

    @Override
    protected ParsedDocument doParse(byte[] content) {
        int depth = DEPTH.get() + 1;
        if (depth > MAX_NESTING_DEPTH) {
            throw new BusinessException(5001,
                "ZIP 嵌套深度超过上限 " + MAX_NESTING_DEPTH + "，可能存在 zip 炸弹");
        }
        DEPTH.set(depth);
        try {
            return doParseInternal(content);
        } finally {
            DEPTH.set(depth - 1);
        }
    }

    private ParsedDocument doParseInternal(byte[] content) {
        List<ParsedPage> allPages    = new ArrayList<>();
        int entryCount               = 0;
        int parsedCount              = 0;
        int skippedCount             = 0;
        int virtualPage              = 1;
        long totalBytes              = 0L;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    log.warn("[ZIP] 条目数超过上限 {}，停止解析剩余条目", MAX_ENTRIES);
                    break;
                }
                String memberName = entry.getName();
                // 跳过 macOS 压缩元数据
                if (memberName.startsWith("__MACOSX/") || memberName.endsWith(".DS_Store")) {
                    skippedCount++;
                    continue;
                }
                String memberType = resolveMemberType(memberName);
                if (memberType == null || !multiFormatParser.supports(memberType)) {
                    log.info("[ZIP] 跳过不支持的成员文件: {} (type={})", memberName, memberType);
                    skippedCount++;
                    continue;
                }
                byte[] memberBytes = readEntry(zis, memberName);
                totalBytes += memberBytes.length;
                // 累计解压总量上限：防御「大量中等成员累加撑爆堆」的分片式 zip 炸弹
                // （单成员上限之外的第二道防线）
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new BusinessException(5001,
                        "ZIP 解压累计总量超过上限 " + MAX_TOTAL_BYTES + " bytes，可能存在 zip 炸弹");
                }
                try {
                    ParsedDocument memberDoc = multiFormatParser.parse(memberBytes, memberType);
                    if (memberDoc != null && memberDoc.getPages() != null) {
                        for (ParsedPage page : memberDoc.getPages()) {
                            allPages.add(renumberPage(page, virtualPage++, memberName));
                        }
                        parsedCount++;
                    }
                } catch (BusinessException e) {
                    // 单个成员解析失败不中断整体，记录后跳过
                    log.warn("[ZIP] 成员文件解析失败，跳过: {} — code={} msg={}",
                        memberName, e.getCode(), e.getMessage());
                    skippedCount++;
                }
            }
        } catch (IOException e) {
            throw new BusinessException(5001, "ZIP 解压失败：" + e.getMessage());
        }

        log.info("[ZIP] 解析完成，成员文件数={}，成功解析={}，跳过={}",
            entryCount, parsedCount, skippedCount);

        // 空结果让 QualityFilterHandler 标记 FAILED（全部为图片/不支持格式时）
        return ParsedDocument.builder()
            .pdfType(PdfType.NATIVE_TEXT)
            .pages(allPages)
            .build();
    }

    /**
     * 重新编号页码，并在 block 的 sectionTitle 为空时回填来源文件名，便于检索追溯。
     */
    private ParsedPage renumberPage(ParsedPage page, int pageNum, String memberName) {
        List<ParsedBlock> blocks = page.getBlocks() == null
            ? List.of()
            : page.getBlocks().stream()
                .map(b -> ParsedBlock.builder()
                    .content(b.getContent())
                    .chunkType(b.getChunkType() != null ? b.getChunkType() : ChunkType.TEXT)
                    .sectionTitle(b.getSectionTitle() != null && !b.getSectionTitle().isBlank()
                        ? b.getSectionTitle()
                        : memberName)
                    .build())
                .toList();
        return ParsedPage.builder()
            .pageNum(pageNum)
            .blocks(blocks)
            .build();
    }

    /**
     * 读取当前 zip 条目全部字节，带大小上限保护。
     */
    private byte[] readEntry(ZipInputStream zis, String memberName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = zis.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_MEMBER_BYTES) {
                throw new BusinessException(5001,
                    "ZIP 成员文件过大，超过上限 " + MAX_MEMBER_BYTES + " bytes: " + memberName);
            }
        }
        return out.toByteArray();
    }

    /**
     * 根据成员文件名后缀推断类型（委托 {@link FileTypeResolver}）。
     * 不支持的格式返回 null（不 fallback 到 MARKDOWN，避免把图片等二进制当文本解析）。
     */
    private String resolveMemberType(String fileName) {
        return FileTypeResolver.resolveByExtension(fileName);
    }

    @Override
    public String supportedType() {
        return "ZIP";
    }
}
