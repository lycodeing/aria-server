package com.aidevplatform.knowledge.infrastructure.parser;

import com.aidevplatform.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多格式文档解析工厂。
 * Spring 自动注入所有 {@link DocumentParser} 实现类，按 fileType 分发到对应解析器。
 * 新增文档格式只需添加新的 DocumentParser 实现，无需修改此类（开闭原则）。
 *
 * <p>支持的格式：
 * <ul>
 *   <li>MARKDOWN → {@link MarkdownParser}</li>
 *   <li>PDF      → {@link PdfParser}</li>
 *   <li>HTML     → {@link HtmlParser}</li>
 *   <li>DOCX     → {@link WordParser}</li>
 *   <li>TICKET   → {@link TicketParser}</li>
 * </ul>
 */
@Slf4j
@Component
public class MultiFormatParser {

    /** fileType → 对应解析器，由 Spring 自动发现所有实现注入 */
    private final Map<String, DocumentParser> parsers;

    public MultiFormatParser(List<DocumentParser> parserList) {
        this.parsers = parserList.stream()
            .collect(Collectors.toMap(
                p -> p.supportedType().toUpperCase(),
                p -> p
            ));
        log.info("已注册文档解析器：{}", parsers.keySet());
    }

    /**
     * 按文件类型分发到对应解析器，返回清洗后的纯文本。
     *
     * @param content  原始文件字节数组
     * @param fileType 文件类型标识（如 PDF/MARKDOWN/HTML/DOCX/TICKET），大小写不敏感
     * @return 清洗后的纯文本
     * @throws BusinessException 不支持的格式或解析失败时抛出
     */
    public String parse(byte[] content, String fileType) {
        if (content == null || content.length == 0) {
            throw new BusinessException(5000, "文件内容不能为空");
        }
        DocumentParser parser = parsers.get(fileType.toUpperCase());
        if (parser == null) {
            throw new BusinessException(5002,
                "不支持的文档格式：" + fileType + "，当前支持：" + parsers.keySet());
        }
        log.debug("开始解析文档，fileType={}，字节数={}", fileType, content.length);
        return parser.parse(content);
    }

    /** 判断是否支持指定格式 */
    public boolean supports(String fileType) {
        return parsers.containsKey(fileType.toUpperCase());
    }
}
