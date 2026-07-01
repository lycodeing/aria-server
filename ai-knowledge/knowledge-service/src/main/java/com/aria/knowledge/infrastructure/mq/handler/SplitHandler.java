package com.aria.knowledge.infrastructure.mq.handler;

import com.aria.knowledge.infrastructure.mq.IngestContext;
import com.aria.knowledge.infrastructure.mq.IngestHandler;
import com.aria.knowledge.infrastructure.splitter.RecursiveChunkSplitter;
import com.aria.knowledge.infrastructure.splitter.SplitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 责任链 Step 3：将结构化文档按语义递归切分为带元数据的切片列表。
 * 从 {@link IngestContext#parsedDoc} 读取，结果写入 {@link IngestContext#splits}。
 */
@Slf4j
@Order(4)
@Component
@RequiredArgsConstructor
public class SplitHandler implements IngestHandler {

    private final RecursiveChunkSplitter splitter;

    @Override
    public void handle(IngestContext ctx) {
        String fileType = ctx.getEvent().getFileType();
        List<SplitResult> splits = splitter.split(ctx.getParsedDoc(), fileType);
        ctx.setSplits(splits);
        log.debug("[文本切分] docId={}，切片数={}", ctx.getEvent().getDocId(), splits.size());
    }
}
