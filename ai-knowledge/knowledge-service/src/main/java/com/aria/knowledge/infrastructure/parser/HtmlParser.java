package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * HTML 文档解析器（基于 Jsoup）。
 * 优先提取语义标签（main/article/[role=main]），去除导航栏、页脚、广告等噪声。
 *
 * <p>编码处理：使用 {@link Jsoup#parse(java.io.InputStream, String, String)} 重载，
 * charset=null 时 Jsoup 自动从 {@code <meta charset>} 或 BOM 检测编码，
 * 避免强制 UTF-8 导致 GBK/GB2312 等编码文档解析乱码。
 */
@Slf4j
@Component
public class HtmlParser extends AbstractDocumentParser {

    @Override
    protected ParsedDocument doParse(byte[] content) {
        try {
            // charset=null：由 Jsoup 从 <meta charset> 自动检测，不强制 UTF-8
            Document doc = Jsoup.parse(new ByteArrayInputStream(content), null, "");
            // 优先提取语义化内容区，降级到 body
            Element main = doc.selectFirst("main, article, [role=main], .content, #content");
            String text = (main != null ? main : doc.body()).text();
            log.debug("HTML 语义区提取完成，使用标签={}，字符数={}",
                main != null ? main.tagName() : "body", text.length());
            return ParsedDocument.ofSinglePage(text, null);
        } catch (IOException e) {
            throw new BusinessException(5001, "HTML 解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "HTML";
    }
}

