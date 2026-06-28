package com.aidevplatform.knowledge.infrastructure.parser;

import com.aidevplatform.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Word 文档解析器（基于 Apache POI，支持 .docx）。
 * 提取段落和标题文本，保留段落分隔符。
 */
@Slf4j
@Component
public class WordParser implements DocumentParser {

    @Override
    public String parse(byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            log.debug("Word 解析完成，字符数={}", text.length());
            return text;
        } catch (IOException e) {
            throw new BusinessException(5003, "Word 文档解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "DOCX";
    }
}
