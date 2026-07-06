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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

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
     *
     * @param req        创建请求 DTO
     * @param operatorId 操作者 ID
     * @return 新建的配置 DO
     */
    @Transactional
    public AiModelConfigDO create(AiModelRequest req, Long operatorId) {
        AiModelConfigDO do_ = new AiModelConfigDO();
        copyRequestToDo(req, do_);
        // 新建时 apiKeyEnc 必须写入（允许空值，代表无鉴权）
        do_.setApiKeyEnc(normalizeApiKey(req.getApiKeyEnc()));
        do_.setCreatedBy(operatorId);
        do_.setCreatedAt(LocalDateTime.now());
        do_.setUpdatedAt(LocalDateTime.now());
        do_.setIsDefault(false);
        mapper.insert(do_);
        return do_;
    }

    /**
     * 更新配置（接受 DTO，防止调用方通过 DO 修改内部字段）。
     *
     * @param id  配置 ID
     * @param req 更新请求 DTO
     */
    @Transactional
    public void update(Long id, AiModelRequest req) {
        AiModelConfigDO existing = getOrThrow(id);
        copyRequestToDo(req, existing);
        // apiKeyEnc 不为空才更新，留空代表"不修改现有 Key"
        if (req.getApiKeyEnc() != null && !req.getApiKeyEnc().isBlank()) {
            existing.setApiKeyEnc(normalizeApiKey(req.getApiKeyEnc()));
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
     *
     * @param apiKeyEnc 加密后的 API Key 字符串
     * @return 解密后的原始 API Key
     */
    public String decryptApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null || apiKeyEnc.isBlank()) {
            return "";  // 空值表示无鉴权（本地 Ollama 等），直接返回空串
        }
        if (apiKeyEnc.startsWith("PLAINTEXT:")) {
            return apiKeyEnc.substring(10);
        }
        if (apiKeyEnc.startsWith("AES:")) {
            return com.aria.common.core.util.EncryptUtils.decrypt(apiKeyEnc.substring(4));
        }
        // 历史遗留裸 Key（无格式前缀），以明文处理并打印警告；
        // 应在管理后台重新保存该配置使其规范化。
        log.warn("[AiModelConfig] apiKeyEnc 缺少格式前缀，以明文处理 id 对应配置，建议重新保存");
        return apiKeyEnc;
    }

    /**
     * 脱敏展示 API Key：前 4 位 + **** + 后 4 位，长度不足时全部遮掩。
     *
     * @param apiKeyEnc 加密后的 API Key 字符串
     * @return 脱敏后的展示字符串
     */
    public String maskApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null) {
            return "****";
        }
        try {
            String raw = decryptApiKey(apiKeyEnc);
            if (raw.length() <= 8) {
                return "****";
            }
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
        if (record == null) {
            throw new BusinessException(404, "AI 模型配置不存在: id=" + id);
        }
        return record;
    }

    /**
     * 将请求 DTO 字段拷贝到 DO 对象。
     * 供 create() 和 update() 复用，避免字段映射代码重复。
     *
     * @param req 请求 DTO
     * @param do_ 目标 DO 对象
     */
    private void copyRequestToDo(AiModelRequest req, AiModelConfigDO do_) {
        do_.setName(req.getName());
        do_.setProvider(req.getProvider());
        do_.setApiProtocol(req.getApiProtocol());
        do_.setBaseUrl(req.getBaseUrl());
        do_.setModelName(req.getModelName());
        // modelType 为空时不覆盖，防止 update 时意外清除已有类型（CHAT/EMBEDDING/ROUTER）
        if (req.getModelType() != null) {
            do_.setModelType(req.getModelType());
        }
        if (req.getTemperature() != null) {
            do_.setTemperature(new BigDecimal(req.getTemperature().toString()));
        }
        if (req.getMaxTokens() != null) {
            do_.setMaxTokens(req.getMaxTokens());
        }
        if (req.getTimeoutSec() != null) {
            do_.setTimeoutSec(req.getTimeoutSec());
        }
        if (req.getIsEnabled() != null) {
            do_.setIsEnabled(req.getIsEnabled());
        }
        // apiKeyEnc 不在此方法中处理：
        // create() 直接调用 normalizeApiKey() 写入；
        // update() 只在非空时调用 normalizeApiKey() 写入，空值表示不修改原 Key。
    }

    /**
     * 规范化 API Key 存储格式。
     * <ul>
     *   <li>null / 空串 → 返回空串（无鉴权场景，如本地 Ollama）</li>
     *   <li>已有 PLAINTEXT:/AES: 前缀 → 原样返回</li>
     *   <li>裸 Key（无前缀）→ 自动补 PLAINTEXT: 前缀</li>
     * </ul>
     */
    private String normalizeApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null || apiKeyEnc.isBlank()) {
            return "";
        }
        if (apiKeyEnc.startsWith("PLAINTEXT:") || apiKeyEnc.startsWith("AES:")) {
            return apiKeyEnc;
        }
        return "PLAINTEXT:" + apiKeyEnc;
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
     * @param id 配置 ID
     * @return map 包含 success(boolean)、latencyMs(long)、message(string)
     */
    public Map<String, Object> testConnection(Long id) {
        AiModelConfigDO cfg = getOrThrow(id);
        String apiKey = decryptApiKey(cfg.getApiKeyEnc());
        String baseUrl = normalizeBaseUrl(cfg.getBaseUrl());

        long start = System.currentTimeMillis();
        try {
            String requestBody = buildTestRequestBody(cfg);
            HttpResponse<String> resp = executeTestRequest(baseUrl, apiKey, requestBody, cfg);
            long latency = System.currentTimeMillis() - start;
            return parseTestResponse(resp, latency, cfg.getModelType(), id);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[AI Test] id={} error={}", id, e.getMessage());
            return Map.of("success", false, "latencyMs", latency,
                    "message", "连接失败：" + e.getMessage());
        }
    }

    /**
     * 规范化 baseUrl：去除尾部空格，确保以 "/" 结尾。
     */
    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.stripTrailing();
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return normalized;
    }

    /**
     * 根据模型类型构建测试请求体。
     *
     * @param cfg 模型配置
     * @return JSON 格式的请求体
     */
    private String buildTestRequestBody(AiModelConfigDO cfg) {
        boolean isEmbedding = "EMBEDDING".equalsIgnoreCase(cfg.getModelType());
        if (isEmbedding) {
            // Embedding 测试：发一条极短文本
            return String.format("{\"model\":\"%s\",\"input\":\"test\"}", cfg.getModelName());
        } else {
            // Chat 测试：发一条极简消息，max_tokens=1 降低延迟
            return String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1,\"stream\":false}",
                    cfg.getModelName());
        }
    }

    /**
     * 执行 HTTP 测试请求。
     *
     * @param baseUrl     API 基础地址
     * @param apiKey      API Key
     * @param requestBody 请求体
     * @param cfg         模型配置（用于获取超时和端点类型）
     * @return HTTP 响应
     */
    private HttpResponse<String> executeTestRequest(String baseUrl, String apiKey,
                                                     String requestBody, AiModelConfigDO cfg) throws Exception {
        boolean isEmbedding = "EMBEDDING".equalsIgnoreCase(cfg.getModelType());
        String endpoint = baseUrl + (isEmbedding ? "embeddings" : "chat/completions");
        int timeout = cfg.getTimeoutSec() != null ? cfg.getTimeoutSec() : 30;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 解析 HTTP 测试响应。
     *
     * @param resp      HTTP 响应
     * @param latency   延迟（毫秒）
     * @param modelType 模型类型
     * @param id        配置 ID
     * @return 测试结果 Map
     */
    private Map<String, Object> parseTestResponse(HttpResponse<String> resp, long latency,
                                                    String modelType, Long id) {
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            boolean isEmbedding = "EMBEDDING".equalsIgnoreCase(modelType);
            String detail = isEmbedding ? "向量模型连通正常" : "对话模型连通正常";
            log.info("[AI Test] id={} type={} latency={}ms OK", id, modelType, latency);
            return Map.of("success", true, "latencyMs", latency, "message", detail);
        } else {
            // 截取响应体前 200 字符作为错误提示，避免泄露过多信息
            String errBody = resp.body();
            if (errBody != null && errBody.length() > 200) {
                errBody = errBody.substring(0, 200) + "...";
            }
            log.warn("[AI Test] id={} status={} body={}", id, resp.statusCode(), errBody);
            return Map.of("success", false, "latencyMs", latency,
                    "message", "HTTP " + resp.statusCode() + "：" + errBody);
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
