package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.exception.BusinessException;
import com.aria.knowledge.domain.model.ChunkType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历专用文档解析器（支持 PDF / DOCX / HTML / MD / TXT）。
 *
 * <p>设计思路：通用 PdfParser 用一套正则规则适配所有 PDF，简历的 emoji 章节头、
 * 密集中文排版等格式导致 sectionTitle 识别率低。本解析器按简历标准结构
 * （个人信息、求职意向、教育背景、工作经历、项目经历、专业技能、荣誉奖励、
 * 自我评价、兴趣爱好）用关键词规则识别章节边界，产出语义准确的 ParsedBlock。
 *
 * <p>文本提取层：按文件魔数/内容特征自动选择提取方式：
 * <ul>
 *   <li>PDF → PDFBox</li>
 *   <li>DOCX（ZIP 魔数 + word/ 头）→ Apache POI</li>
 *   <li>HTML（&lt;html 标签）→ 正则去标签</li>
 *   <li>其余 → UTF-8 直接解码</li>
 * </ul>
 * 结构化层：逐行扫描，匹配简历章节关键词作为段落边界，每段赋标准化 sectionTitle。
 */
@Slf4j
@Component
public class ResumeParser extends AbstractDocumentParser {

    // ===== 简历章节关键词规则 =====

    /**
     * 简历标准章节匹配规则。
     * 每条规则：关键词正则 → 标准化 sectionTitle。
     * 兼容 emoji 前缀（🛠 💼 🚀 💡 📄 🎓 等）和可变空格。
     * 匹配优先级：按列表顺序，第一个命中的规则生效。
     */
    private static final List<SectionRule> SECTION_RULES = List.of(
            new SectionRule("个人简介|个人介绍|联系方式|基本信息|个人资料", "个人信息"),
            new SectionRule("求职意向|期望职位", "求职意向"),
            new SectionRule("教育背景|教育经历|学历|教育", "教育背景"),
            new SectionRule("工作经历|工作经验|就业经历|实习经历", "工作经历"),
            new SectionRule("项目经历|项目经验|个人项目", "项目经历"),
            new SectionRule("技术能力|技术栈|专业技能|技能清单|核心能力|个人技能", "专业技能"),
            new SectionRule("荣誉奖励|获奖经历|证书|荣誉", "荣誉奖励"),
            new SectionRule("自我评价|个人评价|个人优势", "自我评价"),
            new SectionRule("兴趣爱好|兴趣|爱好", "兴趣爱好")
    );

    /**
     * emoji 前缀正则：匹配 0~1 个 emoji 字符 + 可选变体选择符 + 可变空格
     */
    private static final String EMOJI_PREFIX = "[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF]?[\uFE00-\uFE0F]?\\s*";

    /**
     * 预编译的章节匹配 Pattern 列表（emoji 前缀 + 关键词，整行匹配）
     */
    private static final List<Pattern> SECTION_PATTERNS;
    /**
     * 行长度上限：超过此长度的行不可能是章节标题
     */
    private static final int MAX_TITLE_LINE_LENGTH = 30;

    static {
        SECTION_PATTERNS = SECTION_RULES.stream()
                .map(rule -> Pattern.compile("^" + EMOJI_PREFIX + "(" + rule.keywordRegex() + ")\\s*[:：]?\\s*$"))
                .toList();
    }

    @Override
    protected ParsedDocument doParse(byte[] content) {
        String text = extractText(content);
        if (text == null || text.isBlank()) {
            throw new BusinessException(5000, "简历内容为空，无法提取文本");
        }
        List<ParsedPage> pages = splitByResumeSections(text);
        log.info("简历解析完成，章节数={}，总行数={}", pages.size(), text.split("\n").length);
        return ParsedDocument.builder()
                .pdfType(PdfType.NATIVE_TEXT)
                .pages(pages)
                .build();
    }

    @Override
    public String supportedType() {
        return "RESUME";
    }

    // ===== 文本提取层 =====

    /**
     * 按文件格式自动选择文本提取方式。
     * PDF → PDFBox；DOCX → POI；HTML → 去标签；其余 → UTF-8。
     */
    private String extractText(byte[] content) {
        if (content == null || content.length == 0) return "";

        // PDF 魔数：%PDF
        if (startsWith(content, "%PDF")) {
            return extractFromPdf(content);
        }
        // DOCX 魔数：ZIP 头 PK\x03\x04（完整 4 字节，避免 "PK" 开头的文本误匹配）
        if (content.length >= 4
                && content[0] == 'P' && content[1] == 'K'
                && content[2] == 0x03 && content[3] == 0x04) {
            return extractFromDocx(content);
        }
        // HTML 检测：前 1KB 内含 <html 或 <body 或 <!DOCTYPE
        String head = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.UTF_8);
        if (head.toLowerCase().contains("<html") || head.toLowerCase().contains("<body")
                || head.toLowerCase().contains("<!doctype")) {
            return extractFromHtml(content);
        }
        // 其余按 UTF-8 文本处理（Markdown / TXT）
        return new String(content, StandardCharsets.UTF_8);
    }

    private boolean startsWith(byte[] content, String prefix) {
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.US_ASCII);
        if (content.length < prefixBytes.length) return false;
        for (int i = 0; i < prefixBytes.length; i++) {
            if (content[i] != prefixBytes[i]) return false;
        }
        return true;
    }

    private String extractFromPdf(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc).trim();
        } catch (IOException e) {
            throw new BusinessException(5001, "简历 PDF 解析失败：" + e.getMessage());
        }
    }

    private String extractFromDocx(byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (IOException | POIXMLException e) {
            // 不是 DOCX（ZIP 头匹配但内部非 OOXML），fallback 到 UTF-8 文本
            log.debug("DOCX 解析失败，fallback 到 UTF-8 文本：{}", e.getMessage());
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private String extractFromHtml(byte[] content) {
        String html = new String(content, StandardCharsets.UTF_8);
        // 去 script/style 块
        html = html.replaceAll("(?is)<script.*?</script>", "");
        html = html.replaceAll("(?is)<style.*?</style>", "");
        // 块级标签转换行
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</(p|div|li|tr|td|th|h[1-6])>", "\n");
        // 去所有标签
        html = html.replaceAll("(?s)<[^>]+>", "");
        // 去 HTML 实体
        html = html.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"");
        return html.trim();
    }

    // ===== 简历结构化层 =====

    /**
     * 按简历章节关键词切分文本为 ParsedPage 列表。
     *
     * <p>逐行扫描，匹配到简历章节关键词的行作为段落边界：
     * <ul>
     *   <li>遇到章节标题行 → 提交旧段、开启新段，sectionTitle 为标准化的章节名</li>
     *   <li>首行到第一个章节标题之间的内容 → sectionTitle="个人信息"</li>
     *   <li>每个章节内容合并为一个 ParsedBlock（含标题行本身）</li>
     * </ul>
     */
    private List<ParsedPage> splitByResumeSections(String text) {
        List<ParsedPage> pages = new ArrayList<>();
        String[] lines = text.split("\n");
        StringBuilder currentContent = new StringBuilder();
        String currentSection = "个人信息";  // 首段默认为个人信息
        boolean firstSectionFound = false;
        int virtualPage = 1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                if (!currentContent.isEmpty()) {
                    currentContent.append("\n");
                }
                continue;
            }

            // 检测是否为简历章节标题行
            String matchedSection = matchResumeSection(trimmed);
            if (matchedSection != null) {
                // 遇到新章节：提交旧段
                if (!currentContent.isEmpty()) {
                    pages.add(buildPage(virtualPage++, currentContent.toString().trim(), currentSection));
                    currentContent.setLength(0);
                }
                currentSection = matchedSection;
                firstSectionFound = true;
            } else if (!firstSectionFound) {
                // 首个章节标题之前的内容归为"个人信息"
                currentSection = "个人信息";
            }

            currentContent.append(trimmed).append("\n");
        }

        // 提交最后一段
        if (!currentContent.isEmpty()) {
            pages.add(buildPage(virtualPage, currentContent.toString().trim(), currentSection));
        }

        return pages;
    }

    /**
     * 匹配行是否为简历章节标题。
     * 按规则列表顺序匹配，第一个命中的返回标准化 sectionTitle。
     *
     * <p>匹配前对行文本做 Unicode NFKC 归一化，将 CJK 兼容字符（如 ⼒ U+2F12）
     * 转换为标准字符（力 U+529B），解决 PDF 提取出的兼容字符导致关键词不匹配的问题。
     *
     * @param line 待检测的行（已 trim）
     * @return 匹配到的标准化章节名，未匹配返回 null
     */
    private String matchResumeSection(String line) {
        if (line.length() > MAX_TITLE_LINE_LENGTH) return null;
        // NFKC 归一化：CJK 兼容字符 → 标准字符，确保关键词匹配不受 PDF 编码差异影响
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC);
        for (int i = 0; i < SECTION_PATTERNS.size(); i++) {
            Matcher m = SECTION_PATTERNS.get(i).matcher(normalized);
            if (m.find()) {
                return SECTION_RULES.get(i).standardTitle;
            }
        }
        return null;
    }

    private ParsedPage buildPage(int pageNum, String content, String sectionTitle) {
        ParsedBlock block = ParsedBlock.builder()
                .content(content)
                .chunkType(ChunkType.TEXT)
                .sectionTitle(sectionTitle)
                .build();
        return ParsedPage.builder()
                .pageNum(pageNum)
                .blocks(List.of(block))
                .build();
    }

    // ===== 内部数据结构 =====

    /**
     * 章节匹配规则：关键词正则 → 标准化 sectionTitle
     */
    private record SectionRule(String keywordRegex, String standardTitle) {
    }
}
