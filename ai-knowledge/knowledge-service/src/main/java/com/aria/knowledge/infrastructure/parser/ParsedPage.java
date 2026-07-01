package com.aria.knowledge.infrastructure.parser;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 单页解析结果，含页码和该页内所有文本块。
 */
@Data
@Builder
public class ParsedPage {
    /** 1-based 页码 */
    private int               pageNum;
    /** 该页所有文本块，按阅读顺序排列 */
    private List<ParsedBlock> blocks;

    /** 将本页所有块拼接为纯文本（供非结构化处理路径使用） */
    public String toPlainText() {
        if (blocks == null || blocks.isEmpty()) return "";
        return blocks.stream()
            .map(ParsedBlock::getContent)
            .collect(Collectors.joining("\n\n"));
    }
}
