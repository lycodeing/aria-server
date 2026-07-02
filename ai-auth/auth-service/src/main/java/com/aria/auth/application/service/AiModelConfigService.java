package com.aria.auth.application.service;

import com.aria.auth.interfaces.dto.AiModelRequest;
import com.aria.common.core.exception.BusinessException;
import com.aria.auth.infrastructure.event.AiConfigEventPublisher;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.auth.infrastructure.persistence.ai.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private final AiModelConfigMapper    mapper;
    /** 配置变更事件发布器，封装 Redis Pub/Sub 细节 */
    private final AiConfigEventPublisher eventPublisher;

    /** 分页查询（api_key_enc 原样返回，Controller 层负责脱敏） */
    public Page<AiModelConfigDO> page(int pageNum, int pageSize) {
        Page<AiModelConfigDO> page = new Page<>(pageNum, pageSize);
        return mapper.selectPage(page,
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .isNull(AiModelConfigDO::getDeletedAt)
                        .orderByDesc(AiModelConfigDO::getCreatedAt));
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
        if (req.getMaxTokens() != null)  existing.setMaxTokens(req.getMaxTokens());
        if (req.getTimeoutSec() != null) existing.setTimeoutSec(req.getTimeoutSec());
        if (req.getIsEnabled() != null)  existing.setIsEnabled(req.getIsEnabled());
        // api_key_enc 为空时保留原值，避免编辑时意外清除 Key
        if (req.getApiKeyEnc() != null && !req.getApiKeyEnc().isBlank()) {
            existing.setApiKeyEnc(req.getApiKeyEnc());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        broadcastChangeAfterCommit();
    }

    /**
     * 将指定配置设为默认（原默认自动取消）。
     * 使用 clearAllDefault + updateById 两步，依赖事务保证原子性。
     */
    @Transactional
    public void setDefault(Long id) {
        getOrThrow(id);
        mapper.clearAllDefault();
        AiModelConfigDO upd = new AiModelConfigDO();
        upd.setId(id);
        upd.setIsDefault(true);
        upd.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(upd);
        broadcastChangeAfterCommit();
        log.info("[AiModelConfig] 默认配置切换为 id={}", id);
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
     * 查询当前默认配置（内部接口使用，含原始加密 Key）。
     * 返回 null 表示无激活配置。
     */
    public AiModelConfigDO getActiveConfig() {
        return mapper.selectOne(
                new LambdaQueryWrapper<AiModelConfigDO>()
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

    /** 注册事务提交后回调，确保 DB 变更已持久化再广播，避免下游读到旧数据 */
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
