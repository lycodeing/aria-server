package com.aria.customerservice.auth.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.aria.customerservice.auth.application.query.UserPageQuery;
import com.aria.customerservice.auth.application.service.UserApplicationService;
import com.aria.customerservice.auth.domain.model.user.User;
import com.aria.customerservice.auth.interfaces.assembler.UserAssembler;
import com.aria.customerservice.auth.interfaces.rest.vo.*;
import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 用户管理接口。
 * Controller 只负责参数接收/校验和响应组装，所有业务逻辑委托给 UserApplicationService。
 * 不直接依赖任何 Domain 仓储或基础设施对象。
 */
@RestController
@RequestMapping("/api/v1/users")
@SaCheckLogin
public class UserController {

    private final UserApplicationService userAppService;

    public UserController(UserApplicationService userAppService) {
        this.userAppService = userAppService;
    }

    @GetMapping("/me")
    public R<UserVO> me() {
        Long uid = StpUtil.getLoginIdAsLong();
        User user = userAppService.getCurrentUser(uid);
        return R.ok(UserAssembler.toVO(user));
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
    public R<UserVO> create(@RequestBody CreateUserRequest req) {
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
    public R<UserVO> updateMe(@RequestBody UpdateUserRequest req) {
        Long uid = StpUtil.getLoginIdAsLong();
        User user = userAppService.updateProfile(uid, req.getDisplayName(),
                req.getEmail(), req.getPhone());
        return R.ok(UserAssembler.toVO(user));
    }

    @PutMapping("/{id}")
    public R<UserVO> update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        User user = userAppService.updateProfile(id, req.getDisplayName(),
                req.getEmail(), req.getPhone());
        return R.ok(UserAssembler.toVO(user));
    }

    @GetMapping("/me/login-records")
    public R<List<UserVO>> listLoginRecords() {
        return R.ok(List.of());
    }

    @GetMapping("/me/notification-settings")
    public R<NotificationSettingsVO> getNotificationSettings() {
        return R.ok(new NotificationSettingsVO(true, true));
    }

    @PutMapping("/me/notification-settings")
    public R<NotificationSettingsVO> updateNotificationSettings(
            @RequestBody NotificationSettingsRequest req) {
        return R.ok(new NotificationSettingsVO(
                req.isMrCommentEmail(), req.isMrMergedEmail()));
    }

    @PostMapping("/me/avatar")
    public R<AvatarVO> uploadAvatar(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) return R.fail(400, "文件不能为空");
        return R.ok(new AvatarVO("/api/v1/users/me/avatar/default.png"));
    }

    @PostMapping("/me/mfa/enable")
    public R<MfaInitVO> enableMfa() {
        return R.ok(new MfaInitVO(
                "otpauth://totp/aidev:admin?secret=BASE32SECRET&issuer=AI-Dev-Platform"));
    }

    @GetMapping("/me/mfa")
    public R<MfaStatusVO> getMfaStatus() {
        return R.ok(new MfaStatusVO(false));
    }

    @PostMapping("/me/mfa/disable")
    public R<Void> disableMfa(@RequestBody(required = false) Object req) {
        return R.ok(null);
    }

    @GetMapping("/me/ssh-keys/gpg")
    public R<List<GpgKeyVO>> listGpgKeys() {
        return R.ok(List.of());
    }

    @PostMapping("/me/ssh-keys/gpg")
    public R<GpgKeyVO> addGpgKey(@RequestBody AddGpgKeyRequest req) {
        if (req.getPublicKey() == null) return R.fail(400, "公钥不能为空");
        return R.ok(new GpgKeyVO(
                com.aria.common.core.util.IdGenerator.nextId(),
                "ABCD1234",
                OffsetDateTime.now().toString()));
    }

    @DeleteMapping("/me/ssh-keys/gpg/{gpgKeyId}")
    public R<Void> deleteGpgKey(@PathVariable Long gpgKeyId) {
        return R.ok(null);
    }

    @GetMapping("/me/linked-accounts")
    public R<List<UserVO>> linkedAccounts() {
        return R.ok(List.of());
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
        userAppService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/change-password")
    public R<Void> changePassword(@PathVariable Long id,
                                   @RequestBody ChangePasswordRequest req) {
        userAppService.changePassword(id, req.getOldPassword(), req.getNewPassword());
        return R.ok();
    }

    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id,
                                  @RequestBody ResetPasswordRequest req) {
        userAppService.resetPassword(id, req.getNewPassword());
        return R.ok();
    }

    @cn.dev33.satoken.annotation.SaCheckRole("admin")
    @PostMapping("/{id}/roles")
    public R<Void> assignRoles(@PathVariable Long id,
                                @RequestBody AssignRolesRequest req) {
        userAppService.assignRoles(id, req.getRoleIds());
        return R.ok();
    }

    // -------------------------------------------------------
    // Request DTO
    // -------------------------------------------------------

    public static class AddGpgKeyRequest {
        private String publicKey;
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String v) { this.publicKey = v; }
    }

    public static class NotificationSettingsRequest {
        private boolean mrCommentEmail;
        private boolean mrMergedEmail;
        public boolean isMrCommentEmail() { return mrCommentEmail; }
        public void setMrCommentEmail(boolean v) { this.mrCommentEmail = v; }
        public boolean isMrMergedEmail() { return mrMergedEmail; }
        public void setMrMergedEmail(boolean v) { this.mrMergedEmail = v; }
    }

    public static class CreateUserRequest {
        private String username;
        private String displayName;
        private String email;
        private String phone;
        private String password;
        public String getUsername() { return username; }
        public void setUsername(String v) { this.username = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getEmail() { return email; }
        public void setEmail(String v) { this.email = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
        public String getPassword() { return password; }
        public void setPassword(String v) { this.password = v; }
    }

    public static class UpdateUserRequest {
        private String displayName;
        private String email;
        private String phone;
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { this.displayName = v; }
        public String getEmail() { return email; }
        public void setEmail(String v) { this.email = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
    }

    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
        public String getOldPassword() { return oldPassword; }
        public void setOldPassword(String v) { this.oldPassword = v; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String v) { this.newPassword = v; }
    }

    public static class ResetPasswordRequest {
        private String newPassword;
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String v) { this.newPassword = v; }
    }

    public static class AssignRolesRequest {
        private Set<Long> roleIds;
        public Set<Long> getRoleIds() { return roleIds; }
        public void setRoleIds(Set<Long> v) { this.roleIds = v; }
    }
}
