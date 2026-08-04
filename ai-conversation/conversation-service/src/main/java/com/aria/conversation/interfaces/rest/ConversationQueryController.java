package com.aria.conversation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.aria.common.core.page.PageResult;
import com.aria.common.web.response.R;
import com.aria.conversation.application.query.ConversationPageQuery;
import com.aria.conversation.application.service.ConversationQueryService;
import com.aria.conversation.interfaces.rest.vo.SessionMessageVO;
import com.aria.conversation.interfaces.rest.vo.SessionRecordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话查询管理端接口（座席工作台「会话查询」页面）。
 *
 * <p>提供分页 + 多条件筛选的会话记录查询、详情消息、客服下拉三个只读能力，
 * 与 {@link SessionQueueController} 的 {@code getAllSessions}（实时队列推送用）互补。
 *
 * <p>本类仅负责参数接收与响应组装，全部用例编排委托 {@link ConversationQueryService}
 * （对齐项目 {@code UserController} 的 DDD 分层范式，接口层不直接依赖仓储/Mapper/SDK）。
 *
 * <p>权限：由 {@code system:session:query} 权限点控制（super_admin / kf_manager 持有）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/sessions")
@RequiredArgsConstructor
@Validated
public class ConversationQueryController {

    private final ConversationQueryService queryService;

    /**
     * 分页查询会话记录。
     *
     * @param query 查询参数（page=0-based, size, startDate, endDate, status, agentId, agentIds, keyword, tag, closedBy）
     */
    @GetMapping("/query")
    @SaCheckPermission("system:session:query")
    public R<PageResult<SessionRecordVO>> query(ConversationPageQuery query) {
        return R.ok(queryService.query(query));
    }

    /**
     * 获取会话的消息记录（从 DB 读取，不依赖 Redis），供详情抽屉展示对话历史。
     *
     * @param sessionId 会话唯一标识
     */
    @GetMapping("/{sessionId}/messages")
    @SaCheckPermission("system:session:query")
    public R<List<SessionMessageVO>> messages(@PathVariable String sessionId) {
        return R.ok(queryService.messages(sessionId));
    }

    /**
     * 搜索客服列表（供会话查询页面筛选下拉使用）。
     *
     * @param keyword 搜索关键词（可选）
     * @param page    页码（0-based，默认 0）
     * @param size    每页大小（默认 10）
     */
    @GetMapping("/agents")
    @SaCheckPermission("system:session:query")
    public R<Map<String, Object>> searchAgents(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(queryService.searchAgents(keyword, page, size));
    }
}
