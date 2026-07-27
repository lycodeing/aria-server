package com.aria.conversation.infrastructure.ai;

import com.aria.conversation.domain.model.IntentResult;
import com.aria.conversation.domain.service.IntentService;
import com.aria.conversation.domain.service.MultiIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 意图分类级联协调器（@Primary），实现 {@link IntentService} 接口。
 *
 * <p>改造后内部代理 {@link MultiIntentService}（实际实现为 {@link MultiHybridIntentService}），
 * 取 {@link com.aria.conversation.domain.model.MultiIntentResult#primaryIntent()} 返回，
 * 对已有的 {@link IntentService} 注入方保持零感知兼容。
 *
 * <p>ChatAppService 等已切换为注入 {@link MultiIntentService}，可直接获取多意图结果；
 * 此类仅供仍注入 {@link IntentService} 的旧调用方使用（向后兼容）。
 */
@Primary
@Component
@RequiredArgsConstructor
@Slf4j
public class HybridIntentService implements IntentService {

    private final MultiIntentService multiIntentService;

    @Override
    public IntentResult classify(String userMessage) {
        // 代理多意图服务，取优先级最高的主意图，对旧调用方零感知
        return multiIntentService.classifyMulti(userMessage).primaryIntent();
    }
}

