package com.aidevplatform.knowledge.infrastructure.scheduler;

import com.aidevplatform.knowledge.application.service.DocExpiryService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 文档过期清理定时任务（遵循阿里规范：@Scheduled 方法体不超过 5 行）。
 * 每天凌晨 02:00 执行，业务逻辑全部委托 DocExpiryService。
 */
@Component
@RequiredArgsConstructor
public class DocExpiryScheduler {

    private final DocExpiryService docExpiryService;

    @Scheduled(cron = "0 0 2 * * *")
    public void deprecateExpiredDocs() {
        docExpiryService.deprecateExpired(LocalDate.now());
    }
}
