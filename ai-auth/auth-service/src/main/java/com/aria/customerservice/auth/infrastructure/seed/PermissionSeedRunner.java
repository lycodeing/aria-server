package com.aria.customerservice.auth.infrastructure.seed;

import com.aria.customerservice.auth.infrastructure.persistence.role.PermissionDO;
import com.aria.customerservice.auth.infrastructure.persistence.role.PermissionMapper;
import com.aria.customerservice.auth.infrastructure.persistence.role.RoleDO;
import com.aria.customerservice.auth.infrastructure.persistence.role.RoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 权限种子数据兜底：Flyway 被禁用（spring.flyway.enabled=false）时，sys_permission 可能未初始化，
 * 导致 /roles/permissions/tree 返回空、角色权限分配面板无可选项（P1 缺陷根因）。
 * 启动时检测若权限表为空则自动补种子，避免数据丢失后无法自愈。
 */
@Configuration
public class PermissionSeedRunner {
    private static final Logger log = LoggerFactory.getLogger(PermissionSeedRunner.class);

    /** module -> {key: name}，与 V2__seed_data.sql 保持一致 */
    private static final Map<String, Map<String, String>> SEED = Map.of(
            "requirement", Map.of(
                    "requirement:access", "需求模块访问",
                    "requirement:story:create", "创建Story",
                    "requirement:story:transit", "流转Story状态"),
            "code", Map.of(
                    "code:access", "代码模块访问",
                    "code:push", "推送代码",
                    "code:pr:create", "创建PR",
                    "code:pr:merge", "合并PR"),
            "testcase", Map.of(
                    "testcase:access", "测试模块访问",
                    "testcase:case:create", "创建测试用例",
                    "testcase:execute", "执行测试"),
            "pipeline", Map.of(
                    "pipeline:access", "流水线访问",
                    "pipeline:trigger", "触发流水线",
                    "pipeline:config", "配置流水线"),
            "snapshot", Map.of(
                    "snapshot:access", "快照访问",
                    "snapshot:create", "创建快照",
                    "snapshot:release", "发布快照"),
            "admin", Map.of(
                    "admin:access", "管理后台访问",
                    "admin:user:manage", "用户管理",
                    "admin:config", "系统配置")
    );

    @Bean
    @Transactional
    public ApplicationRunner seedPermissionsIfEmpty(PermissionMapper permissionMapper, RoleMapper roleMapper) {
        return args -> {
            long existing = permissionMapper.selectCount(null);
            if (existing > 0) {
                log.debug("sys_permission 已有 {} 条，跳过种子初始化", existing);
                return;
            }
            log.warn("sys_permission 为空，开始自动初始化权限种子数据...");
            int inserted = 0;
            for (Map.Entry<String, Map<String, String>> mod : SEED.entrySet()) {
                for (Map.Entry<String, String> p : mod.getValue().entrySet()) {
                    PermissionDO row = new PermissionDO();
                    row.setModule(mod.getKey());
                    row.setPermissionKey(p.getKey());
                    row.setPermissionName(p.getValue());
                    permissionMapper.insert(row);
                    inserted++;
                }
            }
            // admin 角色关联全部权限
            List<RoleDO> admins = roleMapper.selectList(null).stream()
                    .filter(r -> "admin".equals(r.getRoleKey())).toList();
            List<PermissionDO> allPerms = permissionMapper.selectList(null);
            for (RoleDO admin : admins) {
                roleMapper.deleteRolePermissions(admin.getId());
                for (PermissionDO p : allPerms) {
                    roleMapper.insertRolePermission(admin.getId(), p.getId());
                }
            }
            log.warn("权限种子初始化完成：{} 条权限，admin 角色已关联全部权限", inserted);
        };
    }
}
