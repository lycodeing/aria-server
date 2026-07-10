package com.aria.conversation.infrastructure.websocket.cluster;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 当前 Pod 的唯一身份标识。
 *
 * <p>使用 UUID 生成 podId，在 JVM 启动时确定，Pod 停止时自动失效（Redis presence TTL 兜底）。
 * 无任何外部依赖，不会与其他 Bean 形成循环依赖。
 *
 * <p>WsDeliveryConsumer 的 @RabbitListener 使用 {@code key = "#{@podIdentity.get()}"}
 * 将专属匿名队列绑定到 ws.delivery Direct Exchange，routing key 即本 podId。
 * WsMessageRouter 发送时以 podId 为 routing key 精确路由到目标 Pod。
 */
@Slf4j
@Component
public class PodIdentity {

    private final String podId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    /**
     * 返回当前 Pod 的唯一标识（UUID 前 16 位十六进制字符串）。
     *
     * @return podId 字符串，不为 null，JVM 生命周期内不变
     */
    public String get() {
        return podId;
    }

    /**
     * 判断给定 podId 是否属于本 Pod。
     *
     * @param targetPodId 目标 podId
     * @return {@code true} 表示目标在本 Pod；{@code false} 表示在其他 Pod
     */
    public boolean isLocal(String targetPodId) {
        return podId.equals(targetPodId);
    }

    @PostConstruct
    public void logPodId() {
        log.info("[Cluster] Pod 身份初始化完成 podId={}", podId);
    }
}
