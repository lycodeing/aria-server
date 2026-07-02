package com.aria.knowledge.infrastructure.parser;

import com.aria.knowledge.domain.model.ChunkType;

import com.aria.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PDF 文档解析器（Apache PDFBox 3.x）。
 *
 * <p>处理策略：
 * <ol>
 *   <li>类型检测：采样前5页平均字符数，&lt;10 判定为扫描件立即拒绝</li>
 *   <li>逐页提取：按页提取文本，每页携带 1-based 页码</li>
 *   <li>块拆分：每页内按双空行分块，启发式检测表格行标记 TABLE 类型</li>
 *   <li>章节标题检测：数字编号/中文编号/短行视为 sectionTitle</li>
 * </ol>
 *
 * <p>扫描件（ErrorCode 5003）：前端提示"PDF 为扫描件，请上传含文字层的 PDF"。
 * 待 OCR 服务接入后，在此处替换为 OCR 分支，ParsedDocument 结构无需变化。
 */
@Slf4j
@Component
public class PdfParser extends AbstractDocumentParser {

    /** 每页平均有效字符数低于此值判定为扫描件 */
    private static final int SCANNED_THRESHOLD = 10;
    /** 每页平均有效字符数低于此值判定为混合型 */
    private static final int MIXED_THRESHOLD = 50;

    /** 表格行检测：含有连续两个以上空格或制表符分隔 */
    private static final Pattern TABLE_ROW =
        Pattern.compile("^.{2,}(\\s{2,}|\\t).{2,}$");

    /** 章节标题检测：中文编号、数字序号、英文 Chapter/Section、纯大写短行 */
    private static final Pattern SECTION_TITLE =
        Pattern.compile(
            "^(第[一二三四五六七八九十百]+[章节条]" +  // 第X章/节/条
            "|\\d+\\.\\s"                            +  // 1. 序号
            "|\\d+\\.\\d+\\s"                        +  // 1.1 子序号
            "|[一二三四五六七八九十]+[、.]"           +  // 一、中文序号
            "|[Cc]hapter\\s+\\d+"                    +  // Chapter 1
            "|[Ss]ection\\s+\\d+"                    +  // Section 1
            "|[A-Z][A-Z\\s]{2,29}"                   +  // 全大写短行
            ")");

    @Override
    protected ParsedDocument doParse(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            int totalPages = doc.getNumberOfPages();
            PdfType pdfType = detectType(doc, totalPages);
            log.info("PDF 类型检测完成，类型={}，总页数={}", pdfType, totalPages);

            if (pdfType == PdfType.SCANNED) {
                throw new BusinessException(5003,
                    "PDF 为扫描件，当前版本暂不支持提取，请上传含文字层的 PDF 文件");
            }

            List<ParsedPage> pages = extractPages(doc, totalPages);
            String title = extractTitle(doc);

            return ParsedDocument.builder()
                .title(title)
                .pdfType(pdfType)
                .pages(pages)
                .build();
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(5001, "PDF 解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "PDF";
    }

    // ===== 私有方法 =====

    /**
     * 类型检测：采样前5页，计算平均有效字符数判断 PDF 类型。
     * 空白字符不计入有效字符，排除页眉页脚干扰。
     */
    private PdfType detectType(PDDocument doc, int totalPages) throws IOException {
        int samplePages = Math.min(5, totalPages);
        PDFTextStripper sampler = new PDFTextStripper();
        sampler.setSortByPosition(true);
        int totalChars = 0;
        for (int i = 1; i <= samplePages; i++) {
            sampler.setStartPage(i);
            sampler.setEndPage(i);
            totalChars += sampler.getText(doc).replaceAll("\\s", "").length();
        }
        double avg = (double) totalChars / samplePages;
        if (avg < SCANNED_THRESHOLD)  return PdfType.SCANNED;
        if (avg < MIXED_THRESHOLD)    return PdfType.MIXED;
        return PdfType.NATIVE_TEXT;
    }

    /** 逐页提取，每页拆分为多个 ParsedBlock。 */
    private List<ParsedPage> extractPages(PDDocument doc, int totalPages) throws IOException {
        List<ParsedPage> pages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        for (int i = 1; i <= totalPages; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(doc).trim();
            if (pageText.isBlank()) continue;

            List<ParsedBlock> blocks = splitPageIntoBlocks(pageText);
            if (!blocks.isEmpty()) {
                pages.add(ParsedPage.builder()
                    .pageNum(i)
                    .blocks(blocks)
                    .build());
            }
        }
        log.debug("PDF 逐页提取完成，有效页数={}", pages.size());
        return pages;
    }

    /**
     * 将单页文本按双空行拆分为段落块，检测表格行和章节标题。
     * 章节标题检测：逐行扫描段落，取第一个匹配 SECTION_TITLE 的行作为章节标题，
     * 避免因页眉文字混入导致整段超过长度阈值而漏检。
     */
    private List<ParsedBlock> splitPageIntoBlocks(String pageText) {
        List<ParsedBlock> blocks = new ArrayList<>();
        String[] paragraphs = pageText.split("\n{2,}");
        String currentSection = null;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isBlank()) continue;

            // 逐行扫描，取第一个匹配标题模式的行更新 currentSection
            String[] lines = trimmed.split("\n");
            for (String line : lines) {
                String lineTrimmed = line.trim();
                if (lineTrimmed.length() <= 80
                        && SECTION_TITLE.matcher(lineTrimmed).find()) {
                    currentSection = lineTrimmed;
                    break;
                }
            }

            ChunkType type = detectBlockType(trimmed);
            blocks.add(ParsedBlock.builder()
                .content(trimmed)
                .chunkType(type)
                .sectionTitle(currentSection)
                .build());
        }
        return blocks;
    }

    /**
     * 判断段落是否为表格：超过60%的行匹配表格行模式。
     * 至少需要2行才判定为表格，单行数据不算表格。
     */
    private ChunkType detectBlockType(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 2) return ChunkType.TEXT;
        long tableLines = Arrays.stream(lines)
            .filter(l -> TABLE_ROW.matcher(l.trim()).matches())
            .count();
        return (double) tableLines / lines.length > 0.6
            ? ChunkType.TABLE
            : ChunkType.TEXT;
    }

    /** 从 PDF 文档信息提取标题，失败时返回 null。 */
    private String extractTitle(PDDocument doc) {
        try {
            if (doc.getDocumentInformation() != null) {
                return doc.getDocumentInformation().getTitle();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
