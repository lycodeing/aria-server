package com.aria.knowledge.infrastructure.parser;

import com.aria.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

/**
 * 文档解析器抽象基类（模板方法模式）。
 *
 * <p>定义解析流程骨架：空内容校验 → 日志 → 实际解析 → 日志，
 * 子类只需实现 {@link #doParse} 专注于格式解析本身，无需重复防御代码。
 */
@Slf4j
public abstract class AbstractDocumentParser implements DocumentParser {

    /**
     * 模板方法：定义解析骨架，子类不可覆盖（final）。
     */
    @Override
    public final ParsedDocument parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(5000, "文件内容不能为空");
        }
        log.debug("开始解析 [{}]，字节数={}", supportedType(), content.length);
        ParsedDocument result = doParse(content);
        log.debug("解析完成 [{}]，页数={}", supportedType(),
            result != null && result.getPages() != null ? result.getPages().size() : 0);
        return result;
    }

    /**
     * 实际解析钩子，子类实现格式相关逻辑。
     * 调用时 content 已保证非空非 null。
     *
     * @param content 原始文件字节数组（非空）
     * @return 结构化文档对象
     * @throws BusinessException 解析失败时抛出
     */
    protected abstract ParsedDocument doParse(byte[] content);
}
