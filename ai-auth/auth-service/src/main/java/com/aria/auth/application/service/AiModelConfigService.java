package com.aria.auth.application.service;

import com.aria.auth.infrastructure.event.AiConfigEventPublisher;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigMapper;
import com.aria.auth.interfaces.dto.AiModelRequest;
import com.aria.common.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * AI 模型配置应用服务。
 * 负责 CRUD 操作、默认配置切换、缓存失效广播。
 *
 * <p>API Key 存储格式：PLAINTEXT:{rawKey}（开发）或 AES:{base64}（生产）。
 * Phase-2 接入 AES-256-GCM 加密时替换解密逻辑，存储格式不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    private final AiModelConfigMapper mapper;
    /**
     * 配置变更事件发布器，封装 Redis Pub/Sub 细节
     */
    private final AiConfigEventPublisher eventPublisher;

    /**
     * 分页查询（api_key_enc 原样返回，Controller 层负责脱敏）
     */
    public Page<AiModelConfigDO> page(int pageNum, int pageSize, String modelType) {
        Page<AiModelConfigDO> page = new Page<>(pageNum, pageSize);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiModelConfigDO> wrapper =
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .isNull(AiModelConfigDO::getDeletedAt)
                        .orderByDesc(AiModelConfigDO::getCreatedAt);
        // modelType 为空时查全部，非空时按类型过滤（前端 TAB 切换时传入）
        if (modelType != null && !modelType.isBlank()) {
            wrapper.eq(AiModelConfigDO::getModelType, modelType);
        }
        return mapper.selectPage(page, wrapper);
    }

    /**
     * 新建配置（接受 DTO，防止调用方通过 DO 设置内部字段）。
     */
    @Transactional
    public AiModelConfigDO create(AiModelRequest req,
                                  Long operatorId) {
        AiModelConfigDO do_ = new AiModelConfigDO();
        do_.setName(req.getName());
        do_.setProvider(req.getProvider());
        do_.setApiProtocol(req.getApiProtocol());
        do_.setBaseUrl(req.getBaseUrl());
        do_.setApiKeyEnc(req.getApiKeyEnc());
        do_.setModelName(req.getModelName());
        do_.setTemperature(req.getTemperature() != null
                ? new java.math.BigDecimal(req.getTemperature().toString()) : null);
        do_.setMaxTokens(req.getMaxTokens());
        do_.setTimeoutSec(req.getTimeoutSec());
        do_.setIsEnabled(Boolean.TRUE.equals(req.getIsEnabled()));
        do_.setModelType(req.getModelType());
        do_.setCreatedBy(operatorId);
        do_.setCreatedAt(LocalDateTime.now());
        do_.setUpdatedAt(LocalDateTime.now());
        do_.setIsDefault(false);
        mapper.insert(do_);
        return do_;
    }

    /**
     * 更新配置（接受 DTO，防止调用方通过 DO 修改内部字段）。
     */
    @Transactional
    public void update(Long id,
                       AiModelRequest req) {
        AiModelConfigDO existing = getOrThrow(id);
        existing.setName(req.getName());
        existing.setProvider(req.getProvider());
        existing.setApiProtocol(req.getApiProtocol());
        existing.setBaseUrl(req.getBaseUrl());
        existing.setModelName(req.getModelName());
        if (req.getTemperature() != null) {
            existing.setTemperature(new java.math.BigDecimal(req.getTemperature().toString()));
        }
        if (req.getMaxTokens() != null) existing.setMaxTokens(req.getMaxTokens());
        if (req.getTimeoutSec() != null) existing.setTimeoutSec(req.getTimeoutSec());
        if (req.getIsEnabled() != null) existing.setIsEnabled(req.getIsEnabled());
        // api_key_enc 为空时保留原值，避免编辑时意外清除 Key
        if (req.getApiKeyEnc() != null && !req.getApiKeyEnc().isBlank()) {
            existing.setApiKeyEnc(req.getApiKeyEnc());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        broadcastChangeAfterCommit();
    }

    /**
     * 将指定配置设为默认（原同类型默认自动取消）。
     * CHAT 和 EMBEDDING 各自独立管理默认，互不影响。
     * 使用 clearAllDefaultByType + updateById 两步，依赖事务保证原子性。
     */
    @Transactional
    public void setDefault(Long id) {
        AiModelConfigDO record = getOrThrow(id);
        // 按 model_type 范围清除默认，避免误清另一类型的默认配置
        mapper.clearAllDefaultByType(record.getModelType());
        AiModelConfigDO upd = new AiModelConfigDO();
        upd.setId(id);
        upd.setIsDefault(true);
        upd.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(upd);
        broadcastChangeAfterCommit();
        log.info("[AiModelConfig] 默认配置切换为 id={} type={}", id, record.getModelType());
    }

    /**
     * 软删除配置。
     * 默认配置不允许删除，需先切换默认后再删除。
     */
    @Transactional
    public void delete(Long id) {
        AiModelConfigDO existing = getOrThrow(id);
        if (Boolean.TRUE.equals(existing.getIsDefault())) {
            throw new BusinessException(422, "默认配置不允许删除，请先切换默认配置");
        }
        AiModelConfigDO upd = new AiModelConfigDO();
        upd.setId(id);
        upd.setDeletedAt(LocalDateTime.now());
        mapper.updateById(upd);
        broadcastChangeAfterCommit();
    }

    /**
     * 查询当前默认 CHAT 配置（内部接口使用，含原始加密 Key）。
     * 返回 null 表示无激活配置。
     */
    public AiModelConfigDO getActiveConfig() {
        return mapper.selectOne(
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .eq(AiModelConfigDO::getModelType, "CHAT")
                        .eq(AiModelConfigDO::getIsDefault, true)
                        .eq(AiModelConfigDO::getIsEnabled, true)
                        .isNull(AiModelConfigDO::getDeletedAt));
    }

    /**
     * 查询当前默认 EMBEDDING 配置（供 knowledge-service 拉取向量模型）。
     * 返回 null 表示无激活的向量模型配置。
     */
    public AiModelConfigDO getActiveEmbeddingConfig() {
        return mapper.selectOne(
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .eq(AiModelConfigDO::getModelType, "EMBEDDING")
                        .eq(AiModelConfigDO::getIsDefault, true)
                        .eq(AiModelConfigDO::getIsEnabled, true)
                        .isNull(AiModelConfigDO::getDeletedAt));
    }

    /**
     * 查询当前默认 ROUTER 配置（域路由小模型，供 conversation-service 拉取）。
     * 返回 null 表示无激活配置。
     */
    public AiModelConfigDO getActiveRouterConfig() {
        return mapper.selectOne(
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .eq(AiModelConfigDO::getModelType, "ROUTER")
                        .eq(AiModelConfigDO::getIsDefault, true)
                        .eq(AiModelConfigDO::getIsEnabled, true)
                        .isNull(AiModelConfigDO::getDeletedAt));
    }

    /**
     * 解密 API Key，支持 PLAINTEXT: 和 AES: 两种格式。
     * <ul>
     *   <li>{@code PLAINTEXT:{raw}}  — 开发环境明文存储，直接返回原始值</li>
     *   <li>{@code AES:{base64}}     — 生产环境 AES-256-GCM 加密，通过 EncryptUtils 解密</li>
     * </ul>
     */
    public String decryptApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null) return "";
        if (apiKeyEnc.startsWith("PLAINTEXT:")) return apiKeyEnc.substring(10);
        if (apiKeyEnc.startsWith("AES:")) {
            return com.aria.common.core.util.EncryptUtils.decrypt(apiKeyEnc.substring(4));
        }
        throw new IllegalStateException("不支持的加密格式（期望 PLAINTEXT: 或 AES: 前缀）：" + apiKeyEnc);
    }

    /**
     * 脱敏展示 API Key：前 4 位 + **** + 后 4 位，长度不足时全部遮掩。
     */
    public String maskApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null) return "****";
        try {
            String raw = decryptApiKey(apiKeyEnc);
            if (raw.length() <= 8) return "****";
            return raw.substring(0, 4) + "****" + raw.substring(raw.length() - 4);
        } catch (Exception e) {
            return "****";
        }
    }

    private AiModelConfigDO getOrThrow(Long id) {
        AiModelConfigDO record = mapper.selectOne(
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .eq(AiModelConfigDO::getId, id)
                        .isNull(AiModelConfigDO::getDeletedAt));
        if (record == null) throw new IllegalArgumentException("AI 模型配置不存在: id=" + id);
        return record;
    }

    // -------------------------------------------------------
    // 连接测试
    // -------------------------------------------------------

    /**
     * 测试指定模型配置的连通性。
     * <ul>
     *   <li>CHAT：向 /v1/chat/completions 发送一条非流式极简请求，验证 API Key 和地址有效性</li>
     *   <li>EMBEDDING：向 /v1/embeddings 发送一条测试文本，验证向量服务可访问</li>
     * </ul>
     *
     * @return map 包含 success(boolean)、latencyMs(long)、message(string)
     */
    public java.util.Map<String, Object> testConnection(Long id) {
        AiModelConfigDO cfg = getOrThrow(id);
        String apiKey = decryptApiKey(cfg.getApiKeyEnc());
        String baseUrl = cfg.getBaseUrl().stripTrailing();
        if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";

        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String body;
            String endpoint;
            boolean isEmbedding = "EMBEDDING".equalsIgnoreCase(cfg.getModelType());

            if (isEmbedding) {
                // Embedding 测试：发一条极短文本
                endpoint = baseUrl + "embeddings";
                body = String.format(
                        "{\"model\":\"%s\",\"input\":\"test\"}",
                        cfg.getModelName());
            } else {
                // Chat 测试：发一条极简消息，max_tokens=1 降低延迟
                endpoint = baseUrl + "chat/completions";
                body = String.format(
                        "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1,\"stream\":false}",
                        cfg.getModelName());
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String detail = isEmbedding ? "向量模型连通正常" : "对话模型连通正常";
                log.info("[AI Test] id={} type={} latency={}ms OK", id, cfg.getModelType(), latency);
                return java.util.Map.of("success", true, "latencyMs", latency, "message", detail);
            } else {
                // 截取响应体前 200 字符作为错误提示，避免泄露过多信息
                String errBody = resp.body();
                if (errBody != null && errBody.length() > 200) errBody = errBody.substring(0, 200) + "...";
                log.warn("[AI Test] id={} status={} body={}", id, resp.statusCode(), errBody);
                return java.util.Map.of("success", false, "latencyMs", latency,
                        "message", "HTTP " + resp.statusCode() + "：" + errBody);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[AI Test] id={} error={}", id, e.getMessage());
            return java.util.Map.of("success", false, "latencyMs", latency,
                    "message", "连接失败：" + e.getMessage());
        }
    }


    /**
     * 注册事务提交后回调，确保 DB 变更已持久化再广播，避免下游读到旧数据
     */
    private void broadcastChangeAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishChanged();
                    }
                }
        );
    }
}
