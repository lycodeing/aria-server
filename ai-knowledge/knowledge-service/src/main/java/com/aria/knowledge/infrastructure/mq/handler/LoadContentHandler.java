package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import com.aria.knowledge.infrastructure.storage.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 责任链 Step 1：从 MinIO 下载原始文件字节流。
 * 结果写入 {@link IngestContext#rawContent}。
 */
@Slf4j
@Order(2)
@Component
@RequiredArgsConstructor
public class LoadContentHandler implements IngestHandler {

    private final MinioStorageService minioStorageService;

    @Override
    public void handle(IngestContext ctx) {
        String storagePath = ctx.getEvent().getStoragePath();
        log.debug("[加载内容] 从 MinIO 下载文件 storagePath={}", storagePath);
        byte[] content = minioStorageService.download(storagePath);
        ctx.setRawContent(content);
        log.debug("[加载内容] 下载完成，字节数={}", content.length);
    }
}
