package com.aria.conversation.infrastructure.dit.pipeline;

import com.aria.conversation.infrastructure.ai.ChatMessage;
import com.aria.conversation.infrastructure.dit.config.IntentConfig;
import com.aria.conversation.infrastructure.dit.config.SlotConfig;
import com.aria.conversation.infrastructure.dit.repository.PendingSlotRepository;
import com.aria.conversation.infrastructure.dit.repository.PendingSlotState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 槽位解析器。
 *
 * <p>按四级策略依次尝试解析意图所需的槽位：
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

    private static final String GIVE_UP_PROMPT = "抱歉，我没能理解您需要的信息，已为您转接人工客服，请稍候。";

    private final SlotExtractService extractService;
    private final PendingSlotRepository pendingSlotRepo;
    private final WebClient.Builder webClientBuilder;

    /**
     * 解析指定意图所需的所有槽位。
     *
     * <p>如果会话有挂起状态（上轮已 DISCOVERED/MISSING），先尝试从当前消息中
     * 解析挂起槽位，再继续解析剩余槽位。
     *
     * @param sessionId     会话 ID
     * @param userMessage   当前用户消息
     * @param recentHistory 最近对话历史（供 EXTRACT 级 LLM 提取用）
     * @param intentConfig  意图配置
     * @param sessionCtx    会话上下文（如 user_id、account_id 等登录信息）
     * @return 解析结果
     */
    public SlotResolveResult resolve(String sessionId,
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
                pending != null ? pending.getResolvedSlots() : Map.of()
        );

        // Level 1: EXTRACT —— LLM 批量提取
        List<SlotConfig> toExtract = requiredSlots.stream()
                .filter(s -> !resolved.containsKey(s.slotName()))
                .toList();
        if (!toExtract.isEmpty()) {
            Map<String, Object> extracted = extractService.extract(userMessage, recentHistory, toExtract);
            resolved.putAll(extracted);
        }

        // 逐槽检查是否全部解析完成
        for (SlotConfig slot : requiredSlots) {
            if (resolved.containsKey(slot.slotName())) continue;

            // Level 2: SESSION —— 从会话上下文取
            if (slot.sessionKey() != null && sessionCtx.containsKey(slot.sessionKey())) {
                resolved.put(slot.slotName(), sessionCtx.get(slot.sessionKey()));
                continue;
            }

            // Level 3: DISCOVER —— 调用发现工具获取候选项
            if (slot.discoverToolCode() != null) {
                int retryCount = pending != null ? pending.getRetryCount() : 0;
                if (retryCount < PendingSlotState.MAX_RETRY) {
                    List<Map<String, String>> candidates = callDiscoverTool(
                            slot, resolved, sessionCtx);
                    if (candidates != null && !candidates.isEmpty()) {
                        // 有候选项，展示给用户选择
                        pendingSlotRepo.delete(sessionId);
                        PendingSlotState newPending = new PendingSlotState(
                                sessionId, intentConfig.code(), intentConfig.code(),
                                slot.slotName(), "DISCOVERED",
                                candidates, resolved, retryCount + 1);
                        pendingSlotRepo.save(newPending);
                        String prompt = buildDiscoveredPrompt(candidates, slot);
                        return SlotResolveResult.discovered(slot.slotName(), candidates, prompt, resolved);
                    }
                    // DISCOVER 返回空，进 ASK_USER
                    pendingSlotRepo.delete(sessionId);
                    PendingSlotState newPending = new PendingSlotState(
                            sessionId, intentConfig.code(), intentConfig.code(),
                            slot.slotName(), "MISSING",
                            null, resolved, retryCount + 1);
                    pendingSlotRepo.save(newPending);
                    String prompt = buildMissingPrompt(slot, "no_results");
                    return SlotResolveResult.missing(slot.slotName(), prompt, resolved);
                }
                // 重试超限，兜底转人工
                pendingSlotRepo.delete(sessionId);
                return SlotResolveResult.giveUp(GIVE_UP_PROMPT);
            }

            // Level 4: ASK_USER —— 直接询问用户
            int retryCount = pending != null ? pending.getRetryCount() : 0;
            if (retryCount >= PendingSlotState.MAX_RETRY) {
                pendingSlotRepo.delete(sessionId);
                return SlotResolveResult.giveUp(GIVE_UP_PROMPT);
            }
            pendingSlotRepo.delete(sessionId);
            PendingSlotState newPending = new PendingSlotState(
                    sessionId, intentConfig.code(), intentConfig.code(),
                    slot.slotName(), "MISSING",
                    null, resolved, retryCount + 1);
            pendingSlotRepo.save(newPending);
            String prompt = buildMissingPrompt(slot, "ask_user");
            return SlotResolveResult.missing(slot.slotName(), prompt, resolved);
        }

        // 所有必填槽位已解析
        pendingSlotRepo.delete(sessionId);
        return SlotResolveResult.resolved(resolved);
    }

    /**
     * 调用 DISCOVER 工具（HTTP GET/POST）获取候选列表。
     * 失败时返回 null，不阻断主流程。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> callDiscoverTool(SlotConfig slot,
                                                        Map<String, Object> resolved,
                                                        Map<String, Object> sessionCtx) {
        try {
            // 发现工具的 URL 需从 ToolRepository 取，这里通过 slot.discoverToolCode() 标识
            // 实际 HTTP 调用在 P3 的 HttpToolRunner 中实现；此处返回 null 表示未配置
            log.debug("[DIT] DISCOVER 工具调用：tool={} slot={}", slot.discoverToolCode(), slot.slotName());
            return null; // P3 实现 HttpToolRunner 后替换
        } catch (Exception e) {
            log.warn("[DIT] DISCOVER 工具调用失败 tool={}", slot.discoverToolCode(), e);
            return null;
        }
    }

    private String buildDiscoveredPrompt(List<Map<String, String>> candidates, SlotConfig slot) {
        StringBuilder sb = new StringBuilder("为您找到以下结果，请问您需要哪个？\n");
        for (int i = 0; i < Math.min(candidates.size(), 5); i++) {
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
