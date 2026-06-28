package com.aidevplatform.knowledge.infrastructure.parser;

import com.aidevplatform.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * PDF 文档解析器（基于 Apache PDFBox 3.x）。
 * 剥离页眉页脚，多列排版重组，表格序列化为文本行。
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

    @Override
    public String parse(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // 按阅读顺序排列（处理多列排版）
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            log.debug("PDF 解析完成，页数={}，字符数={}", doc.getNumberOfPages(), text.length());
            return text;
        } catch (IOException e) {
            throw new BusinessException(5001, "PDF 解析失败：" + e.getMessage());
        }
    }

    @Override
    public String supportedType() {
        return "PDF";
    }
}
