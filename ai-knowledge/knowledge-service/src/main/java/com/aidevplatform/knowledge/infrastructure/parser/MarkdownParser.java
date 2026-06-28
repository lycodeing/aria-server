package com.aidevplatform.knowledge.infrastructure.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Markdown 文档解析器。
 * Markdown 本身是纯文本格式，直接 UTF-8 解码即可，保留标题层级结构（## ### 等）。
 * 标题层级由 RecursiveChunkSplitter 在拆分阶段使用，不在此处处理。
 */
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public String parse(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    @Override
    public String supportedType() {
        return "MARKDOWN";
    }
}
