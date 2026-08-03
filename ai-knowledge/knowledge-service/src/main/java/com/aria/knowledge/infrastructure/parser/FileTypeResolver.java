package com.aria.knowledge.infrastructure.parser;

import java.util.Map;

/**
 * 文件类型解析器（策略查表模式）。
 *
 * <p>统一文件后缀 → fileType 的映射逻辑，消除 {@code DocIngestAppService.resolveFileType}
 * 和 {@code ZipParser.resolveMemberType} 中的重复 if-else 链。
 *
 * <p>新增文件格式只需在 {@link #EXTENSION_MAP} 中添加映射，无需修改调用方。
 */
public final class FileTypeResolver {

    private FileTypeResolver() {}

    /** 文件后缀 → fileType 映射（小写后缀 → 大写类型标识） */
    private static final Map<String, String> EXTENSION_MAP = Map.of(
        ".pdf",   "PDF",
        ".html",  "HTML",
        ".htm",   "HTML",
        ".docx",  "DOCX",
        ".zip",   "ZIP",
        ".md",    "MARKDOWN",
        ".txt",   "MARKDOWN"
    );

    /**
     * 根据文件名后缀解析文件类型。
     *
     * @param fileName 文件名（大小写不敏感）
     * @return fileType 标识，不匹配时返回 null
     */
    public static String resolveByExtension(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        for (Map.Entry<String, String> entry : EXTENSION_MAP.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
