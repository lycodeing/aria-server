package com.aria.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.auth.application.query.UserPageQuery;
import com.aria.auth.application.service.UserApplicationService;
import com.aria.auth.domain.model.user.User;
import com.aria.auth.interfaces.assembler.UserAssembler;
import com.aria.auth.interfaces.rest.vo.PageVO;
import com.aria.auth.interfaces.rest.vo.UserVO;
import com.aria.auth.interfaces.rest.vo.*;
import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 用户管理接口。
 * Controller 只负责参数接收/校验和响应组装，所有业务逻辑委托给 UserApplicationService。
 * 不直接依赖任何 Domain 仓储或基础设施对象。
 *
 * <p>未实现接口（MFA/GPG/头像/通知设置等）统一返回 501 Not Implemented，
 * 明确标识功能待开发，禁止返回假数据误导用户。
 */
@Validated
@RestController
@RequestMapping("/api/v1/users")
@SaCheckLogin
@RequiredArgsConstructor
public class UserController {

    private final UserApplicationService userAppService;

    @GetMapping("/me")
    public R<UserVO> me() {
        Long uid = StpUtil.getLoginIdAsLong();
        User user = userAppService.getCurrentUser(uid);
        return R.ok(UserAssembler.toVO(user));
    }

    /**
     * Vben Admin 框架兼容接口：GET /api/v1/users/info
     * Vben 内置路径为 /user/info，前端 baseURL=/api/v1 → 实际请求 /api/v1/user/info，
     * nginx proxy 将 /api/v1/user 转发到 auth-service，此处补全路径兼容。
     */
    @GetMapping("/info")
    public R<UserVO> info() {
        return me();
    }

    @GetMapping
    public R<PageVO<UserVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        UserPageQuery query = new UserPageQuery();
        query.setKeyword(keyword);
        query.setPage(page);
        query.setSize(size);
        PageResult<User> result = userAppService.search(query);
        List<UserVO> items = result.items().stream().map(UserAssembler::toVO).toList();
        return R.ok(new PageVO<>(items, result.total()));
    }

    @PostMapping
    public R<UserVO> create(@RequestBody @Valid CreateUserRequest req) {
        User user = userAppService.create(
                req.getUsername(), req.getDisplayName(),
                req.getEmail(), req.getPhone(), req.getPassword());
        return R.ok(UserAssembler.toVO(user));
    }

    @GetMapping("/{id}")
    public R<UserVO> getById(@PathVariable Long id) {
        return R.ok(UserAssembler.toVO(userAppService.getById(id)));
    }

    @PutMapping("/me")
    public R<UserVO> updateMe(@RequestBody @Valid UpdateUserRequest req) {
        Long uid = StpUtil.getLoginIdAsLong();
        User user = userAppService.updateProfile(uid, req.getDisplayName(),
                req.getEmail(), req.getPhone());
        return R.ok(UserAssembler.toVO(user));
    }

    @PutMapping("/{id}")
    public R<UserVO> update(@PathVariable Long id, @RequestBody @Valid UpdateUserRequest req) {
        User user = userAppService.updateProfile(id, req.getDisplayName(),
                req.getEmail(), req.getPhone());
        return R.ok(UserAssembler.toVO(user));
    }

    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable Long id) {
        userAppService.disable(id);
        return R.ok();
    }

    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable Long id) {
        userAppService.enable(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long operatorId = StpUtil.getLoginIdAsLong();
        userAppService.delete(id, operatorId);
        return R.ok();
    }

    @PostMapping("/{id}/change-password")
    public R<Void> changePassword(@PathVariable Long id,
                                   @RequestBody @Valid ChangePasswordRequest req) {
        userAppService.changePassword(id, req.getOldPassword(), req.getNewPassword());
        return R.ok();
    }

    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id,
                                  @RequestBody @Valid ResetPasswordRequest req) {
        userAppService.resetPassword(id, req.getNewPassword());
        return R.ok();
    }

    @cn.dev33.satoken.annotation.SaCheckRole("admin")
    @PostMapping("/{id}/roles")
    public R<Void> assignRoles(@PathVariable Long id,
                                @RequestBody @Valid AssignRolesRequest req) {
        userAppService.assignRoles(id, req.getRoleIds());
        return R.ok();
    }

    // -------------------------------------------------------
    // 未实现接口（统一返回 501 Not Implemented）
    // -------------------------------------------------------

    @GetMapping("/me/login-records")
    public R<Void> listLoginRecords() {
        return R.fail(501, "登录记录功能暂未实现");
    }

    @GetMapping("/me/notification-settings")
    public R<Void> getNotificationSettings() {
        return R.fail(501, "通知设置功能暂未实现");
    }

    @PutMapping("/me/notification-settings")
    public R<Void> updateNotificationSettings(@RequestBody(required = false) Object req) {
        return R.fail(501, "通知设置功能暂未实现");
    }

    @PostMapping("/me/avatar")
    public R<Void> uploadAvatar(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return R.fail(501, "头像上传功能暂未实现");
    }

    @PostMapping("/me/mfa/enable")
    public R<Void> enableMfa() {
        return R.fail(501, "MFA 功能暂未实现");
    }

    @GetMapping("/me/mfa")
    public R<Void> getMfaStatus() {
        return R.fail(501, "MFA 功能暂未实现");
    }

    @PostMapping("/me/mfa/disable")
    public R<Void> disableMfa(@RequestBody(required = false) Object req) {
        return R.fail(501, "MFA 功能暂未实现");
    }

    @GetMapping("/me/ssh-keys/gpg")
    public R<Void> listGpgKeys() {
        return R.fail(501, "GPG Key 功能暂未实现");
    }

    @PostMapping("/me/ssh-keys/gpg")
    public R<Void> addGpgKey(@RequestBody(required = false) Object req) {
        return R.fail(501, "GPG Key 功能暂未实现");
    }

    @DeleteMapping("/me/ssh-keys/gpg/{gpgKeyId}")
    public R<Void> deleteGpgKey(@PathVariable Long gpgKeyId) {
        return R.fail(501, "GPG Key 功能暂未实现");
    }

    @GetMapping("/me/linked-accounts")
    public R<Void> linkedAccounts() {
        return R.fail(501, "关联账号功能暂未实现");
    }

    // -------------------------------------------------------
    // Request DTO（@Data + @Valid 校验）
    // -------------------------------------------------------

    @Data
    public static class CreateUserRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度须为 3~50 位")
        private String username;

        @NotBlank(message = "显示名称不能为空")
        private String displayName;

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不合法")
        private String email;

        private String phone;

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, message = "密码长度不得少于 8 位")
        private String password;
    }

    @Data
    public static class UpdateUserRequest {
        private String displayName;

        @Email(message = "邮箱格式不合法")
        private String email;

        private String phone;
    }

    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, message = "密码长度不得少于 8 位")
        private String newPassword;
    }

    @Data
    public static class ResetPasswordRequest {
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, message = "密码长度不得少于 8 位")
        private String newPassword;
    }

    @Data
    public static class AssignRolesRequest {
        @NotNull(message = "角色 ID 列表不能为 null")
        private Set<Long> roleIds;
    }
}
