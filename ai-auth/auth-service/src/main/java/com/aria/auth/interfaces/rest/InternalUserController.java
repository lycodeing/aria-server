package com.aria.auth.interfaces.rest;

import com.aria.auth.application.query.UserPageQuery;
import com.aria.auth.application.service.UserApplicationService;
import com.aria.auth.domain.model.user.User;
import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内部用户查询接口（供 conversation-service 等内部服务调用）。
 *
 * <p>鉴权由 {@code InternalSecretFilter}（common-web 自动装配）统一负责，
 * Controller 无需重复校验 {@code X-Internal-Secret} 头。
 *
 * <p>路径前缀 {@code /api/v1/internal/users}，与 {@link InternalAuthController} 同级。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserApplicationService userAppService;

    /**
     * 分页搜索用户（关键词模糊匹配 username/displayName/email）。
     * 供会话查询页面搜索客服列表使用。
     *
     * @param keyword 搜索关键词（可选）
     * @param page    页码（0-based，默认 0）
     * @param size    每页大小（默认 10，上限 200）
     */
    @GetMapping("/search")
    public R<PageResult<Map<String, Object>>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UserPageQuery query = new UserPageQuery();
        query.setKeyword(keyword);
        query.setPage(page);
        query.setSize(size);
        PageResult<User> result = userAppService.search(query);
        // 仅返回必要字段，避免泄露 email/phone 等敏感信息
        List<Map<String, Object>> items = result.items().stream()
                .map(u -> Map.<String, Object>of(
                        "id", u.getId().getValue(),
                        "username", u.getUsername(),
                        "displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername()))
                .toList();
        return R.ok(PageResult.of(result.total(), result.page(), result.size(), items));
    }

    /**
     * 批量查询用户显示名称（根据 ID 列表）。
     * 供 conversation-service 将 agentId 解析为 displayName 使用。
     *
     * @param ids 逗号分隔的用户 ID
     */
    @GetMapping("/names")
    public R<Map<String, String>> getDisplayNames(@RequestParam String ids) {
        Map<String, String> names = userAppService.getDisplayNamesByIds(
                java.util.Arrays.stream(ids.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
        return R.ok(names);
    }
}
