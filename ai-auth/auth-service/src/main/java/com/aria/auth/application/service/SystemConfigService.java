package com.aria.auth.application.service;

import com.aria.auth.infrastructure.persistence.systemconfig.SystemConfigDO;
import com.aria.auth.infrastructure.persistence.systemconfig.SystemConfigMapper;
import com.aria.auth.interfaces.dto.SystemConfigRequest;
import com.aria.auth.interfaces.rest.vo.SystemConfigVO;
import com.aria.common.core.exception.BusinessException;
import com.aria.common.core.page.PageQuery;
import com.aria.common.core.page.PageResult;
import com.aria.common.core.page.PageUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    // ── 查询 ─────────────────────────────────────────────────────────────────

    /**
     * 分页查询配置列表
     *
     * @param configType 配置类型过滤，null 表示不过滤
     * @param keyword    config_key 或 description 关键字，null 表示不过滤
     * @param pageQuery  分页参数
     */
    public PageResult<SystemConfigVO> page(String configType, String keyword, PageQuery pageQuery) {
        LambdaQueryWrapper<SystemConfigDO> wrapper = new LambdaQueryWrapper<SystemConfigDO>()
                .isNull(SystemConfigDO::getDeletedAt);

        if (StringUtils.hasText(configType)) {
            wrapper.eq(SystemConfigDO::getConfigType, configType);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w
                    .like(SystemConfigDO::getConfigKey, keyword)
                    .or()
                    .like(SystemConfigDO::getDescription, keyword));
        }
        wrapper.orderByDesc(SystemConfigDO::getCreatedAt);

        Page<SystemConfigDO> mpPage = systemConfigMapper.selectPage(
                PageUtil.toMpPage(pageQuery), wrapper);

        return PageUtil.toPageResult(mpPage, this::toVO, pageQuery);
    }

    /**
     * 查询单条配置（已删除则抛 404）
     */
    public SystemConfigVO getById(Long id) {
        return toVO(requireExists(id));
    }

    /**
     * 按类型批量加载，返回 key→value 映射。
     * 仅返回 is_enabled=true 且未删除的记录，供业务模块读取配置。
     */
    public Map<String, String> mapByType(String configType) {
        LambdaQueryWrapper<SystemConfigDO> wrapper = new LambdaQueryWrapper<SystemConfigDO>()
                .eq(SystemConfigDO::getConfigType, configType)
                .eq(SystemConfigDO::getIsEnabled, true)
                .isNull(SystemConfigDO::getDeletedAt);

        return systemConfigMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(
                        SystemConfigDO::getConfigKey,
                        SystemConfigDO::getConfigValue,
                        (a, b) -> a  // configKey 有唯一约束，正常不会冲突
                ));
    }

    /**
     * 读取单个配置值，key 不存在、已禁用或 DB 异常时返回 defaultValue。
     */
    public String getValue(String configKey, String defaultValue) {
        LambdaQueryWrapper<SystemConfigDO> wrapper = new LambdaQueryWrapper<SystemConfigDO>()
                .eq(SystemConfigDO::getConfigKey, configKey)
                .eq(SystemConfigDO::getIsEnabled, true)
                .isNull(SystemConfigDO::getDeletedAt)
                .last("LIMIT 1");

        SystemConfigDO config = systemConfigMapper.selectOne(wrapper);
        return config != null ? config.getConfigValue() : defaultValue;
    }

    // ── 写操作 ────────────────────────────────────────────────────────────────

    /**
     * 新增配置
     */
    @Transactional
    public SystemConfigVO create(SystemConfigRequest req) {
        assertTypeAccess(req.getConfigType());

        // configKey 唯一性校验
        long count = systemConfigMapper.selectCount(
                new LambdaQueryWrapper<SystemConfigDO>()
                        .eq(SystemConfigDO::getConfigKey, req.getConfigKey())
                        .isNull(SystemConfigDO::getDeletedAt));
        if (count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "配置键已存在: " + req.getConfigKey());
        }

        SystemConfigDO config = new SystemConfigDO();
        config.setConfigKey(req.getConfigKey());
        config.setConfigValue(req.getConfigValue());
        config.setConfigType(req.getConfigType());
        config.setDescription(req.getDescription() != null ? req.getDescription() : "");
        config.setIsEnabled(req.getIsEnabled() != null ? req.getIsEnabled() : Boolean.TRUE);

        systemConfigMapper.insert(config);
        log.info("系统配置已创建: key={}, type={}", config.getConfigKey(), config.getConfigType());
        return toVO(config);
    }

    /**
     * 更新配置（configKey 和 configType 不可修改）
     */
    @Transactional
    public SystemConfigVO update(Long id, SystemConfigRequest req) {
        SystemConfigDO config = requireExists(id);
        assertTypeAccess(config.getConfigType());

        if (StringUtils.hasText(req.getConfigValue())) {
            config.setConfigValue(req.getConfigValue());
        }
        if (req.getDescription() != null) {
            config.setDescription(req.getDescription());
        }
        if (req.getIsEnabled() != null) {
            config.setIsEnabled(req.getIsEnabled());
        }

        systemConfigMapper.updateById(config);
        log.info("系统配置已更新: id={}, key={}", id, config.getConfigKey());
        return toVO(config);
    }

    /**
     * 软删除配置
     */
    @Transactional
    public void delete(Long id) {
        SystemConfigDO config = requireExists(id);
        assertTypeAccess(config.getConfigType());

        config.setDeletedAt(LocalDateTime.now());
        systemConfigMapper.updateById(config);
        log.info("系统配置已软删除: id={}, key={}", id, config.getConfigKey());
    }

    /**
     * 切换启用 / 禁用状态
     */
    @Transactional
    public void toggleEnabled(Long id, boolean enabled) {
        SystemConfigDO config = requireExists(id);
        assertTypeAccess(config.getConfigType());

        config.setIsEnabled(enabled);
        systemConfigMapper.updateById(config);
        log.info("系统配置状态变更: id={}, key={}, enabled={}", id, config.getConfigKey(), enabled);
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────

    private SystemConfigDO requireExists(Long id) {
        SystemConfigDO config = systemConfigMapper.selectOne(
                new LambdaQueryWrapper<SystemConfigDO>()
                        .eq(SystemConfigDO::getId, id)
                        .isNull(SystemConfigDO::getDeletedAt));
        if (config == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "系统配置不存在");
        }
        return config;
    }

    /**
     * SYSTEM 类型配置仅超级管理员可操作
     */
    private void assertTypeAccess(String configType) {
        if ("SYSTEM".equals(configType) && !StpUtil.hasRole("super_admin")) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "SYSTEM 类型配置仅超级管理员可操作");
        }
    }

    private SystemConfigVO toVO(SystemConfigDO config) {
        return SystemConfigVO.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .configValue(config.getConfigValue())
                .configType(config.getConfigType())
                .description(config.getDescription())
                .isEnabled(config.getIsEnabled())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }
}
