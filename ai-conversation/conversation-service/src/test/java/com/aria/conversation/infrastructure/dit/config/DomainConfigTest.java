package com.aria.conversation.infrastructure.dit.config;

import com.aria.conversation.infrastructure.dit.repository.PendingSlotState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DIT 配置 Record 辅助方法")
class DomainConfigTest {

    private static ToolConfig tool(String code) {
        return new ToolConfig(code, "name", "desc", "HTTP", "GET",
                "http://x", "{}", null, "{}", null, "NONE", "{}", 5000, false);
    }

    private static IntentConfig makeIntent(String code, boolean autoTransfer, boolean skipRag,
                                           List<IntentToolBinding> bindings) {
        return new IntentConfig(code, code + "_name", "desc", "[]",
                autoTransfer, skipRag, null, List.of(), bindings);
    }

    // ---- DomainConfig ----

    @Test
    @DisplayName("findIntent: 存在时返回对应意图")
    void findIntent_found() {
        IntentConfig intent = makeIntent("query_order", false, true, List.of());
        DomainConfig domain = new DomainConfig("ecommerce", "电商", null, null, null, List.of(intent));

        Optional<IntentConfig> result = domain.findIntent("query_order");

        assertTrue(result.isPresent());
        assertEquals("query_order", result.get().code());
    }

    @Test
    @DisplayName("findIntent: 不存在时返回 empty")
    void findIntent_notFound() {
        DomainConfig domain = new DomainConfig("ecommerce", "电商", null, null, null, List.of());
        assertTrue(domain.findIntent("no_such_intent").isEmpty());
    }

    // ---- IntentConfig ----

    @Test
    @DisplayName("requiredTools: 只返回 REQUIRED 绑定，按 executionOrder 升序排列")
    void requiredTools_filteredAndSorted() {
        IntentToolBinding req1 = new IntentToolBinding(tool("t1"), "REQUIRED", 2, "{}");
        IntentToolBinding req2 = new IntentToolBinding(tool("t2"), "REQUIRED", 1, "{}");
        IntentToolBinding opt  = new IntentToolBinding(tool("t3"), "OPTIONAL", 0, "{}");
        IntentConfig intent = makeIntent("q", false, false, List.of(req1, req2, opt));

        List<IntentToolBinding> required = intent.requiredTools();

        assertEquals(2, required.size());
        assertEquals(1, required.get(0).executionOrder()); // order=1 在前
        assertEquals(2, required.get(1).executionOrder());
    }

    @Test
    @DisplayName("optionalTools: 只返回 OPTIONAL 绑定")
    void optionalTools_filtered() {
        IntentToolBinding req = new IntentToolBinding(tool("t1"), "REQUIRED", 0, "{}");
        IntentToolBinding opt = new IntentToolBinding(tool("t2"), "OPTIONAL", 0, "{}");
        IntentConfig intent = makeIntent("q", false, false, List.of(req, opt));

        assertEquals(1, intent.optionalTools().size());
        assertTrue(intent.optionalTools().get(0).isOptional());
    }

    // ---- IntentToolBinding ----

    @Test
    @DisplayName("isRequired / isOptional 正确")
    void executionMode_helpers() {
        assertTrue(new IntentToolBinding(tool("t"), "REQUIRED", 0, "{}").isRequired());
        assertFalse(new IntentToolBinding(tool("t"), "REQUIRED", 0, "{}").isOptional());
        assertTrue(new IntentToolBinding(tool("t"), "OPTIONAL", 0, "{}").isOptional());
        assertFalse(new IntentToolBinding(tool("t"), "OPTIONAL", 0, "{}").isRequired());
    }

    // ---- PendingSlotState ----

    @Test
    @DisplayName("shouldGiveUp: retryCount >= MAX_RETRY 时为 true")
    void pendingSlotState_giveUp() {
        PendingSlotState s0 = new PendingSlotState("sid", "d", "i", "order_id", "MISSING",
                null, Map.of(), 0);
        PendingSlotState s1 = s0.withIncrementedRetry();
        PendingSlotState s2 = s1.withIncrementedRetry();

        assertFalse(s0.shouldGiveUp()); // retry=0
        assertFalse(s1.shouldGiveUp()); // retry=1
        assertTrue(s2.shouldGiveUp());  // retry=2 >= MAX_RETRY=2
    }

    @Test
    @DisplayName("withIncrementedRetry: 不可变，返回新实例")
    void pendingSlotState_withIncrementedRetry_immutable() {
        PendingSlotState original = new PendingSlotState("sid", "d", "i", "slot", "MISSING",
                null, Map.of(), 0);
        PendingSlotState incremented = original.withIncrementedRetry();

        assertEquals(0, original.getRetryCount());
        assertEquals(1, incremented.getRetryCount());
    }

    @Test
    @DisplayName("isDiscovered / isMissing 正确")
    void pendingType_helpers() {
        PendingSlotState discovered = new PendingSlotState("s", "d", "i", "slot",
                "DISCOVERED", List.of(), Map.of(), 0);
        PendingSlotState missing = new PendingSlotState("s", "d", "i", "slot",
                "MISSING", null, Map.of(), 0);

        assertTrue(discovered.isDiscovered());
        assertFalse(discovered.isMissing());
        assertTrue(missing.isMissing());
        assertFalse(missing.isDiscovered());
    }
}
