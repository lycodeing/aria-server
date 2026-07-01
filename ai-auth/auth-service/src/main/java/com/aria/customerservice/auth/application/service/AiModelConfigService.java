package com.aria.customerservice.auth.application.service;

import com.aria.common.core.exception.BusinessException;
import com.aria.customerservice.auth.infrastructure.persistence.ai.AiModelConfigDO;
import com.aria.customerservice.auth.infrastructure.persistence.ai.AiModelConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private static final String PUBSUB_TOPIC = "aria:config:ai-changed";

    private final AiModelConfigMapper mapper;
    private final StringRedisTemplate redisTemplate;

    /** 分页查询（api_key_enc 原样返回，Controller 层负责脱敏） */
    public Page<AiModelConfigDO> page(int pageNum, int pageSize) {
        Page<AiModelConfigDO> page = new Page<>(pageNum, pageSize);
        return mapper.selectPage(page,
                new LambdaQueryWrapper<AiModelConfigDO>()
                        .isNull(AiModelConfigDO::getDeletedAt)
                        .orderByDesc(AiModelConfigDO::getCreatedAt));
    }

    /** 新建配置 */
    @Transactional
    public AiModelConfigDO create(AiModelConfigDO req, Long operatorId) {
        req.setCreatedBy(operatorId);
        req.setCreatedAt(LocalDateTime.now());
        req.setUpdatedAt(LocalDateTime.now());
        req.setIsDefault(false);
        mapper.insert(req);
        return req;
    }

    /**
     * 更新配置。
     * api_key_enc 为空时保留原值，避免编辑时意外清除 Key。
     */
    @Transactional
    public void update(Long id, AiModelConfigDO req) {
        AiModelConfigDO existing = getOrThrow(id);
        req.setId(id);
        req.setUpdatedAt(LocalDateTime.now());
        if (req.getApiKeyEnc() == null || req.getApiKeyEnc().isBlank()) {
            req.setApiKeyEnc(existing.getApiKeyEnc());
        }
        mapper.updateById(req);
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
        // 非默认配置删除也需广播，确保下游缓存感知到配置列表变化
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
     * 解密 API Key。
     * 当前仅支持 PLAINTEXT: 前缀（开发环境），Phase-2 接入 AES-GCM 后扩展此方法。
     */
    public String decryptApiKey(String apiKeyEnc) {
        if (apiKeyEnc == null) return "";
        if (apiKeyEnc.startsWith("PLAINTEXT:")) return apiKeyEnc.substring(10);
        throw new IllegalStateException("不支持的加密格式，仅支持 PLAINTEXT: 前缀");
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
            // 加密格式不支持时安全兜底，不暴露原始内容
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
                    redisTemplate.convertAndSend(PUBSUB_TOPIC, "{}");
                    log.info("[AiModelConfig] 已广播配置变更通知（事务提交后）");
                }
            }
        );
    }
}
