package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import com.aria.conversation.infrastructure.dit.config.ToolConfig;
import com.aria.conversation.infrastructure.dit.mapper.ToolMapper;
import com.aria.conversation.infrastructure.dit.repository.PendingSlotRepository;
import com.aria.conversation.infrastructure.dit.repository.PendingSlotState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位解析器。
 *
 * <p>按配置的策略顺序（{@link SlotConfig#resolveStrategy()}）依次尝试解析意图所需的槽位：
 * <ol>
 *   <li>EXTRACT  — LLM 从消息和历史中直接提取</li>
 *   <li>SESSION  — 从会话上下文中取（登录用户信息等）</li>
 *   <li>DISCOVER — 调用发现工具获取候选列表，展示给用户选择</li>
 *   <li>ASK_USER — 以上均失败，询问用户直接输入</li>
 * </ol>
 *
 * <p>当某个槽位进入 DISCOVERED 或 MISSING 状态时，pipeline 挂起，
 * 等待用户下一轮输入后从 {@link PendingSlotRepository} 恢复上下文继续解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotResolver {

    /** 候选项最多展示条数 */
    private static final int MAX_DISPLAYED_CANDIDATES = 5;
    private static final String GIVE_UP_PROMPT = "抱歉，我没能理解您需要的信息，已为您转接人工客服，请稍候。";
    private static final List<String> DEFAULT_STRATEGY =
            List.of("EXTRACT", "SESSION", "DISCOVER", "ASK_USER");

    private final SlotExtractService extractService;
    private final PendingSlotRepository pendingSlotRepo;
    private final HttpToolRunner httpToolRunner;
    private final ToolMapper toolMapper;
    private final ObjectMapper objectMapper;

    /**
     * 解析指定意图所需的所有槽位。
     *
     * <p>如果会话有挂起状态（上轮已 DISCOVERED/MISSING），先尝试从当前消息中
     * 解析挂起槽位，再继续解析剩余槽位。
     *
     * @param sessionId     会话 ID
     * @param domainCode    领域标识（存入挂起状态，下轮恢复时使用）
     * @param userMessage   当前用户消息
     * @param recentHistory 最近对话历史（供 EXTRACT 级 LLM 提取用）
     * @param intentConfig  意图配置
     * @param sessionCtx    会话上下文（如 user_id、account_id 等登录信息）
     * @return 解析结果
     */
    public SlotResolveResult resolve(String sessionId,
                                     String domainCode,
                                     String userMessage,
                                     List<ChatMessage> recentHistory,
                                     IntentConfig intentConfig,
                                     Map<String, Object> sessionCtx) {
        List<SlotConfig> requiredSlots = intentConfig.slots().stream()
                .filter(SlotConfig::required)
                .toList();

        if (requiredSlots.isEmpty()) {
            return SlotResolveResult.resolved(Map.of());
        }

        // 检查是否有挂起状态（上轮对话未完成槽位解析）
        PendingSlotState pending = pendingSlotRepo.find(sessionId).orElse(null);

        // 基础已解析槽位集合（从挂起状态恢复 + 本次 EXTRACT 新提取）
        Map<String, Object> resolved = new HashMap<>(
                pending != null && pending.getResolvedSlots() != null
                        ? pending.getResolvedSlots() : Map.of()
        );

        // Level 1: EXTRACT —— LLM 批量提取（对所有待解析槽位一次性提取，减少 LLM 调用次数）
        List<SlotConfig> toExtract = requiredSlots.stream()
                .filter(s -> !resolved.containsKey(s.slotName()))
                .filter(s -> strategyContains(s, "EXTRACT"))
                .toList();
        if (!toExtract.isEmpty()) {
            Map<String, Object> extracted = extractService.extract(userMessage, recentHistory, toExtract);
            resolved.putAll(extracted);
        }

        // 逐槽按配置策略顺序检查
        for (SlotConfig slot : requiredSlots) {
            if (resolved.containsKey(slot.slotName())) continue;

            List<String> strategies = slot.resolveStrategy() != null && !slot.resolveStrategy().isEmpty()
                    ? slot.resolveStrategy() : DEFAULT_STRATEGY;

            for (String strategy : strategies) {
                switch (strategy) {
                    case "EXTRACT" -> {
                        // 已在外层批量提取，若仍未解析则跳过（LLM 无法提取）
                    }
                    case "SESSION" -> {
                        if (slot.sessionKey() != null && sessionCtx.containsKey(slot.sessionKey())) {
                            resolved.put(slot.slotName(), sessionCtx.get(slot.sessionKey()));
                        }
                    }
                    case "DISCOVER" -> {
                        if (slot.discoverToolCode() == null) break;
                        int retryCount = pending != null ? pending.getRetryCount() : 0;
                        if (retryCount >= PendingSlotState.MAX_RETRY) {
                            pendingSlotRepo.delete(sessionId);
                            return SlotResolveResult.giveUp(GIVE_UP_PROMPT);
                        }
                        List<Map<String, String>> candidates =
                                callDiscoverTool(slot, resolved, sessionCtx);
                        pendingSlotRepo.delete(sessionId);
                        if (candidates != null && !candidates.isEmpty()) {
                            PendingSlotState newPending = new PendingSlotState(
                                    sessionId, domainCode, intentConfig.code(),
                                    slot.slotName(), "DISCOVERED",
                                    candidates, resolved, retryCount + 1);
                            pendingSlotRepo.save(newPending);
                            return SlotResolveResult.discovered(slot.slotName(), candidates,
                                    buildDiscoveredPrompt(candidates, slot), resolved);
                        }
                        // DISCOVER 返回空列表：挂起为 MISSING 状态，询问用户直接输入
                        PendingSlotState newPending = new PendingSlotState(
                                sessionId, domainCode, intentConfig.code(),
                                slot.slotName(), "MISSING",
                                null, resolved, retryCount + 1);
                        pendingSlotRepo.save(newPending);
                        return SlotResolveResult.missing(slot.slotName(),
                                buildMissingPrompt(slot, "no_results"), resolved);
                    }
                    case "ASK_USER" -> {
                        int retryCount = pending != null ? pending.getRetryCount() : 0;
                        if (retryCount >= PendingSlotState.MAX_RETRY) {
                            pendingSlotRepo.delete(sessionId);
                            return SlotResolveResult.giveUp(GIVE_UP_PROMPT);
                        }
                        pendingSlotRepo.delete(sessionId);
                        PendingSlotState newPending = new PendingSlotState(
                                sessionId, domainCode, intentConfig.code(),
                                slot.slotName(), "MISSING",
                                null, resolved, retryCount + 1);
                        pendingSlotRepo.save(newPending);
                        return SlotResolveResult.missing(slot.slotName(),
                                buildMissingPrompt(slot, "ask_user"), resolved);
                    }
                    default -> log.warn("[DIT] 未知槽位解析策略: {} slot={}", strategy, slot.slotName());
                }
                // 若本轮策略已解析到值，跳出策略循环
                if (resolved.containsKey(slot.slotName())) break;
            }
        }

        // 所有必填槽位已解析
        pendingSlotRepo.delete(sessionId);
        return SlotResolveResult.resolved(resolved);
    }

    /**
     * 调用 DISCOVER 工具（HTTP 调用）获取候选列表。
     * 从工具注册表加载工具配置，执行 HTTP 调用，按约定格式解析候选项。
     * 失败时返回 null，不阻断主流程。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> callDiscoverTool(SlotConfig slot,
                                                        Map<String, Object> resolved,
                                                        Map<String, Object> sessionCtx) {
        try {
            // 合并 slot 固定参数 + 已解析槽位 + 会话上下文
            Map<String, Object> params = new HashMap<>(sessionCtx);
            params.putAll(resolved);
            if (slot.discoverFixedParams() != null && !slot.discoverFixedParams().isBlank()) {
                try {
                    Map<String, Object> fixed = objectMapper.readValue(
                            slot.discoverFixedParams(), new TypeReference<>() {});
                    params.putAll(fixed);
                } catch (Exception e) {
                    log.warn("[DIT] DISCOVER 固定参数解析失败 tool={}", slot.discoverToolCode(), e);
                }
            }

            // 从工具注册表加载工具配置（走 ToolMapper，不走缓存，发现工具通常不频繁变更）
            var toolDO = toolMapper.findByCode(slot.discoverToolCode()).orElse(null);
            if (toolDO == null) {
                log.warn("[DIT] DISCOVER 工具未找到 code={}", slot.discoverToolCode());
                return null;
            }
            ToolConfig toolConfig = new ToolConfig(
                    toolDO.getCode(), toolDO.getName(), toolDO.getDescription(),
                    toolDO.getToolType(), toolDO.getHttpMethod(), toolDO.getUrlTemplate(),
                    toolDO.getHeadersTemplate(), toolDO.getBodyTemplate(), toolDO.getParamSchema(),
                    toolDO.getResponseJsonpath(), toolDO.getAuthType(), toolDO.getAuthConfig(),
                    toolDO.getTimeoutMs() != null ? toolDO.getTimeoutMs() : 5000,
                    Boolean.TRUE.equals(toolDO.getIsDiscoverTool())
            );

            ToolCallResult result = httpToolRunner.execute(toolConfig, params, sessionCtx);
            if (!result.isSuccess() || result.getResponse() == null) {
                log.warn("[DIT] DISCOVER 工具调用失败 tool={} status={}", slot.discoverToolCode(), result.getStatus());
                return null;
            }

            // 解析响应为候选项列表，约定格式：[{"id":"xxx","label":"xxx"}, ...]
            return objectMapper.readValue(result.getResponse(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[DIT] DISCOVER 工具调用失败 tool={}", slot.discoverToolCode(), e);
            return null;
        }
    }

    /** 是否包含指定策略 */
    private boolean strategyContains(SlotConfig slot, String strategy) {
        List<String> strategies = slot.resolveStrategy() != null && !slot.resolveStrategy().isEmpty()
                ? slot.resolveStrategy() : DEFAULT_STRATEGY;
        return strategies.contains(strategy);
    }

    private String buildDiscoveredPrompt(List<Map<String, String>> candidates, SlotConfig slot) {
        StringBuilder sb = new StringBuilder("为您找到以下结果，请问您需要哪个？\n");
        for (int i = 0; i < Math.min(candidates.size(), MAX_DISPLAYED_CANDIDATES); i++) {
            Map<String, String> c = candidates.get(i);
            String label = c.getOrDefault("label", c.getOrDefault("id", "选项" + (i + 1)));
            sb.append(i + 1).append(". ").append(label).append("\n");
        }
        sb.append("或者请直接提供").append(slot.description()).append("。");
        return sb.toString();
    }

    private String buildMissingPrompt(SlotConfig slot, String context) {
        if ("no_results".equals(context)) {
            return "没有找到相关记录。" +
                    (slot.askUserPrompt() != null ? slot.askUserPrompt() : "请直接提供" + slot.description() + "。");
        }
        return slot.askUserPrompt() != null
                ? slot.askUserPrompt()
                : "请提供" + slot.description() + "，我来帮您处理。";
    }
}
