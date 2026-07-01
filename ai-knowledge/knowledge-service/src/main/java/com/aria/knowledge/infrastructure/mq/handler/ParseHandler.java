package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import com.aria.knowledge.infrastructure.parser.MultiFormatParser;
import com.aria.knowledge.infrastructure.parser.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 责任链 Step 2：按文件类型解析原始字节流为结构化文档。
 * 从 {@link IngestContext#rawContent} 读取，结果写入 {@link IngestContext#parsedDoc}。
 */
@Slf4j
@Order(3)
@Component
@RequiredArgsConstructor
public class ParseHandler implements IngestHandler {

    private final MultiFormatParser multiFormatParser;

    @Override
    public void handle(IngestContext ctx) {
        String fileType = ctx.getEvent().getFileType();
        String docId    = ctx.getEvent().getDocId();
        ParsedDocument parsedDoc = multiFormatParser.parse(ctx.getRawContent(), fileType);
        ctx.setParsedDoc(parsedDoc);
        log.debug("[文档解析] docId={}，fileType={}，页数={}",
            docId, fileType, parsedDoc.getPages() != null ? parsedDoc.getPages().size() : 0);
    }
}
