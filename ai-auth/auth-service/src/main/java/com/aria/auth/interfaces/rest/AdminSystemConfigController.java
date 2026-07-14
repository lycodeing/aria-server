package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.auth.application.service.SystemConfigService;
import com.aria.auth.interfaces.dto.SystemConfigRequest;
import com.aria.auth.interfaces.rest.vo.SystemConfigVO;
import com.aria.common.core.page.PageQuery;
import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统配置管理接口
 * <p>基础路径：/api/v1/admin/system-config</p>
 */
@RestController
@RequestMapping("/api/v1/admin/system-config")
@SaCheckLogin
@RequiredArgsConstructor
public class AdminSystemConfigController {

    private final SystemConfigService systemConfigService;

    /**
     * 分页查询配置列表
     *
     * @param configType 配置类型过滤（SYSTEM | CUSTOMER_SERVICE），可选
     * @param keyword    config_key 或 description 模糊搜索，可选
     */
    @GetMapping
    @SaCheckPermission("system:config:list")
    public R<PageResult<SystemConfigVO>> list(
            @RequestParam(required = false) String configType,
            @RequestParam(required = false) String keyword,
            PageQuery pageQuery) {
        return R.ok(systemConfigService.page(configType, keyword, pageQuery));
    }

    /**
     * 按配置类型批量加载 key→value 映射，仅返回启用且未删除的配置。
     * 前端登录后预加载，供业务模块读取配置。
     *
     * @param configType 配置类型（SYSTEM | CUSTOMER_SERVICE）
     */
    @GetMapping("/map")
    @SaCheckPermission("system:config:list")
    public R<Map<String, String>> mapByType(@RequestParam String configType) {
        return R.ok(systemConfigService.mapByType(configType));
    }

    /**
     * 查询单条配置
     */
    @GetMapping("/{id}")
    @SaCheckPermission("system:config:list")
    public R<SystemConfigVO> getById(@PathVariable Long id) {
        return R.ok(systemConfigService.getById(id));
    }

    /**
     * 新增配置
     */
    @PostMapping
    @SaCheckPermission("system:config:create")
    public R<SystemConfigVO> create(@Valid @RequestBody SystemConfigRequest req) {
        return R.ok(systemConfigService.create(req));
    }

    /**
     * 更新配置（configKey 和 configType 不可修改）
     */
    @PutMapping("/{id}")
    @SaCheckPermission("system:config:update")
    public R<SystemConfigVO> update(
            @PathVariable Long id,
            @Valid @RequestBody SystemConfigRequest req) {
        return R.ok(systemConfigService.update(id, req));
    }

    /**
     * 软删除配置
     */
    @DeleteMapping("/{id}")
    @SaCheckPermission("system:config:delete")
    public R<Void> delete(@PathVariable Long id) {
        systemConfigService.delete(id);
        return R.ok();
    }

    /**
     * 切换启用 / 禁用状态
     */
    @PatchMapping("/{id}/enabled")
    @SaCheckPermission("system:config:update")
    public R<Void> toggleEnabled(
            @PathVariable Long id,
            @RequestBody EnabledRequest request) {
        systemConfigService.toggleEnabled(id, request.isEnabled());
        return R.ok();
    }

    @Data
    static class EnabledRequest {
        private boolean enabled;
    }
}
