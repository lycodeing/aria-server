package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.util.SensitiveDataUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 历史工单解析器。
 * 工单通常是半结构化的对话文本，解析逻辑：
 * 1. UTF-8 解码为纯文本
 * 2. PII 脱敏（手机号、身份证、银行卡号替换为 **）
 * 3. 不做额外结构化处理，由 RecursiveChunkSplitter 按句子边界拆分
 */
@Slf4j
@Component
public class TicketParser extends AbstractDocumentParser {

    @Override
    protected ParsedDocument doParse(byte[] content) {
        String raw = new String(content, StandardCharsets.UTF_8);
        // PII 脱敏，防止手机号/身份证进入向量库
        String desensitized = SensitiveDataUtils.desensitize(raw);
        log.debug("工单 PII 脱敏完成，原始字符数={}，脱敏后字符数={}",
            raw.length(), desensitized.length());
        return ParsedDocument.ofSinglePage(desensitized, null);
    }

    @Override
    public String supportedType() {
        return "TICKET";
    }
}
