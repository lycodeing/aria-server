package com.aidevplatform.conversation.infrastructure.repository;

import com.aidevplatform.common.web.redis.RedisCacheHelper;
import com.aidevplatform.common.web.redis.RedisCounterHelper;
import com.aidevplatform.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aidevplatform.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话历史 Repository。
 *
 * <p>职责一：维护 Redis List 热数据（key: {@code chat:session:{id}}），供 AI 对话和 WS 路由实时读取。
 * <p>职责二：通过 {@link RedisCounterHelper} 生成 session 内单调递增 seq，
 * 支持客户端断线重连后的 sinceSeq 增量同步。
 * <p>职责三：每次追加消息时，通过 {@link ConversationMessagePublisher} 发布到 RabbitMQ Direct Exchange，
 * 由 Consumer 异步消费并持久化到 PostgreSQL（含 seq 字段）。
 *
 * <p>双写策略：
 * <pre>
 *   append() / appendAgentMessage()
 *     ├─ Redis List  chat:session:{id}   （热数据，三元组 [role, content, seq]，TTL 24h）
 *     └─ RabbitMQ MESSAGE 事件（含 seq）  （冷存储，异步持久化 → DB）
 * </pre>
 *
 * <p>角色约定：
 * <ul>
 *   <li>Redis List / AI 请求：user / assistant（OpenAI 标准，兼容 AI 回退）</li>
 *   <li>Stream → DB：user / assistant（AI）/ agent（人工座席，便于质检分析）</li>
 * </ul>
 *
 * <p>Redis 操作统一通过 {@link RedisCacheHelper} / {@link RedisCounterHelper} 进行，
 * 不直接使用 RedisTemplate。
 */
@Slf4j
@Repository
public class ConversationHistoryRepository {

    /** Redis List key 前缀（热数据，实时路由用） */
    private static final String KEY_PREFIX        = "chat:session:";
    /** Redis seq 计数器 key 前缀，session 内单调递增 */
    private static final String SEQ_KEY_PREFIX    = "chat:seq:";
    /** 热数据 TTL（小时） */
    private static final long   TTL_HOURS         = 24L;
    /** 每个会话最多保留的对话轮数 */
    private static final int    MAX_HISTORY_TURNS = 20;
    /** Redis List 中每条消息占用的元素数（role, content, seq 三元组） */
    private static final int    ELEMENTS_PER_MSG  = 3;

    /** AI 兼容角色标识（Redis List 和 AI 请求使用） */
    private static final String ROLE_ASSISTANT = "assistant";
    /** DB 持久化时人工座席角色标识（DB 专用，区分 AI 和人工） */
    private static final String ROLE_AGENT     = "agent";

    /** 消息 Map 字段名，与前端/MQ payload 保持一致 */
    private static final String FIELD_ROLE    = "role";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_SEQ     = "seq";

    private final RedisCacheHelper             cache;
    private final RedisCounterHelper           counter;
    private final ConversationMessagePublisher publisher;
    private final ConversationMessageMapper    messageMapper;

    public ConversationHistoryRepository(RedisCacheHelper cache,
                                          RedisCounterHelper counter,
                                          ConversationMessagePublisher publisher,
                                          ConversationMessageMapper messageMapper) {
        this.cache         = cache;
        this.counter       = counter;
        this.publisher     = publisher;
        this.messageMapper = messageMapper;
    }

    // -------------------------------------------------------
    // seq 生成
    // -------------------------------------------------------

    /**
     * 生成 session 内的下一个单调递增 seq（Redis INCR + 24h TTL）。
     *
     * <p>Redis key 过期或首次写入时（INCR 返回 1），从 DB MAX(seq) 重建初始值，
     * 防止"Redis 重启 / TTL 过期后 seq 从 1 重新计数与 DB 历史 seq 冲突"。
     *
     * <p>失败处理：counter.increment 抛 IllegalStateException 时（Redis 不可用），
     * 调用方应捕获并决定降级策略（如本地维护内存 seq + 异步同步）。
     *
     * @param sessionId 会话唯一标识
     * @return 单调递增 seq（≥ 1）
     */
    public long nextSeq(String sessionId) {
        String key = SEQ_KEY_PREFIX + sessionId;
        long seq = counter.increment(key, Duration.ofHours(TTL_HOURS));
        if (seq == 1L) {
            // 首次 INCR（或 TTL 过期后重启）：用 DB max+1 兜底，避免与历史 seq 冲突
            long dbMax = messageMapper.selectMaxSeq(sessionId);
            if (dbMax > 0L) {
                long restored = dbMax + 1;
                cache.set(key, String.valueOf(restored), Duration.ofHours(TTL_HOURS));
                log.info("[Seq] Redis 计数器过期，从 DB 恢复 sessionId={} dbMax={} restored={}",
                        sessionId, dbMax, restored);
                return restored;
            }
        }
        return seq;
    }

    // -------------------------------------------------------
    // 写入路径
    // -------------------------------------------------------

    /**
     * 追加访客或 AI 消息（role=user / assistant），并发布到 MQ 持久化。
     *
     * @param sessionId 会话 ID
     * @param role      消息角色（user / assistant）
     * @param content   消息内容
     * @return 该消息的 seq（供调用方推送给客户端）
     */
    public long append(String sessionId, String role, String content) {
        long seq = nextSeq(sessionId);
        writeToListWithTrim(sessionId, role, content, seq);
        publishMessageEvent(sessionId, role, content, seq);
        return seq;
    }

    /**
     * 追加人工座席消息。
     * Redis List 仍写 assistant 角色（保持 AI 历史格式兼容），DB 中标记为 agent。
     *
     * @return 该消息的 seq
     */
    public long appendAgentMessage(String sessionId, String content) {
        long seq = nextSeq(sessionId);
        writeToListWithTrim(sessionId, ROLE_ASSISTANT, content, seq);
        publishMessageEvent(sessionId, ROLE_AGENT, content, seq);
        return seq;
    }

    // -------------------------------------------------------
    // 读取路径
    // -------------------------------------------------------

    /**
     * 获取全量历史消息列表（已截断至 MAX_HISTORY_TURNS）。
     * 供 AI 对话（ChatAppService）和座席接入时加载上下文使用。
     *
     * <p>返回元素含 role / content / seq，AI 请求侧自行忽略 seq。
     */
    public List<Map<String, Object>> findAll(String sessionId) {
        List<String> raw = cache.lRange(KEY_PREFIX + sessionId, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> messages = parseTriples(raw);
        if (messages.size() > MAX_HISTORY_TURNS) {
            messages = messages.subList(messages.size() - MAX_HISTORY_TURNS, messages.size());
        }
        return new ArrayList<>(messages);
    }

    /**
     * 增量查询：返回 sessionId 在 sinceSeq 之后的所有消息（seq 严格大于）。
     *
     * <p>查询路径（先热后冷）：
     * <ol>
     *   <li>Redis List 命中（24h 热数据），filter seq > sinceSeq，命中即返</li>
     *   <li>Redis 缺失或起始 seq 早于 List 头部 → 从 DB 查 {@code findBySessionSinceSeq}</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @param sinceSeq  起始 seq（不含）
     * @return 按 seq 升序排列的消息列表
     */
    public List<Map<String, Object>> findSince(String sessionId, long sinceSeq) {
        List<String> raw = cache.lRange(KEY_PREFIX + sessionId, 0, -1);
        List<Map<String, Object>> redisMsgs = parseTriples(raw);

        // 判断 Redis List 是否包含 sinceSeq 之后的全部数据：
        // 若 List 中最早一条 seq <= sinceSeq + 1，说明 Redis 包含全部所需消息
        if (!redisMsgs.isEmpty()) {
            long earliestSeq = (long) redisMsgs.get(0).get(FIELD_SEQ);
            if (earliestSeq <= sinceSeq + 1) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map<String, Object> msg : redisMsgs) {
                    if ((long) msg.get(FIELD_SEQ) > sinceSeq) {
                        result.add(msg);
                    }
                }
                return result;
            }
        }

        // 回退到 DB（包含历史遗留 + 24h 之前的冷数据）
        log.debug("[History] Redis 未覆盖 sinceSeq，回退 DB 查询 sessionId={} sinceSeq={}", sessionId, sinceSeq);
        return messageMapper.findBySessionSinceSeq(sessionId, sinceSeq).stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(FIELD_ROLE,    e.getRole() != null ? e.getRole().getValue() : null);
                    m.put(FIELD_CONTENT, e.getContent());
                    m.put(FIELD_SEQ,     e.getSeq());
                    return m;
                })
                .toList();
    }

    /**
     * 全量覆盖保存历史（AI 流式回复完成后调用）。
     *
     * <p>使用 {@link RedisCacheHelper#replaceListAtomically} 在 Pipeline 中完成
     * DEL + RPUSH + EXPIRE，避免并发场景下 delete 与 rPush 之间产生竞态。
     */
    public void saveAll(String sessionId, List<Map<String, Object>> messages) {
        // 展开为 [role, content, seq, role, content, seq, ...] 三元组序列
        List<String> elements = new ArrayList<>(messages.size() * ELEMENTS_PER_MSG);
        for (Map<String, Object> msg : messages) {
            elements.add(String.valueOf(msg.get(FIELD_ROLE)));
            elements.add(String.valueOf(msg.get(FIELD_CONTENT)));
            Object seq = msg.get(FIELD_SEQ);
            elements.add(seq != null ? String.valueOf(seq) : "0");
        }
        cache.replaceListAtomically(KEY_PREFIX + sessionId, elements, Duration.ofHours(TTL_HOURS));

        // Pipeline 完成后再发布 MQ 事件（最后一条 assistant 消息）
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if (ROLE_ASSISTANT.equals(last.get(FIELD_ROLE))) {
                long seq = nextSeq(sessionId);
                publishMessageEvent(sessionId, ROLE_ASSISTANT,
                        String.valueOf(last.get(FIELD_CONTENT)), seq);
            }
        }
    }

    /** 清除会话历史（会话结束或新建对话时调用） */
    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
        // seq 计数器保留（防止重建会话时 seq 冲突），靠 24h TTL 自动清理
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /** 写入 Redis List 并 LTRIM 保留最新 MAX_HISTORY_TURNS 轮（每轮 3 个元素） */
    private void writeToListWithTrim(String sessionId, String role, String content, long seq) {
        String key = KEY_PREFIX + sessionId;
        cache.lRightPush(key, role);
        cache.lRightPush(key, content);
        cache.lRightPush(key, String.valueOf(seq));
        cache.lTrim(key, -(MAX_HISTORY_TURNS * (long) ELEMENTS_PER_MSG), -1);
        cache.expire(key, Duration.ofHours(TTL_HOURS));
    }

    /**
     * 解析 Redis List 中的 [role, content, seq] 三元组序列为消息列表。
     * 列表长度不是 3 的倍数时（脏数据），跳过尾部不完整记录。
     */
    private List<Map<String, Object>> parseTriples(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> messages = new ArrayList<>(raw.size() / ELEMENTS_PER_MSG);
        for (int i = 0; i + ELEMENTS_PER_MSG - 1 < raw.size(); i += ELEMENTS_PER_MSG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(FIELD_ROLE,    raw.get(i));
            m.put(FIELD_CONTENT, raw.get(i + 1));
            m.put(FIELD_SEQ,     parseSeq(raw.get(i + 2)));
            messages.add(m);
        }
        return messages;
    }

    /** 容错解析 seq 字符串，非法时返回 0（历史遗留二元组数据走 DB 兜底） */
    private long parseSeq(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 通过 RabbitMQ Publisher 发布消息事件（异步持久化）。
     * Publisher 内置 @Retryable（3次），三次失败后异常向上传播，打印 WARN 日志。
     */
    private void publishMessageEvent(String sessionId, String role, String content, long seq) {
        try {
            publisher.publishMessage(sessionId, role, content, seq);
        } catch (org.springframework.amqp.AmqpException e) {
            log.warn("[History] MQ 发布失败（3次重试后），消息仅存 Redis List，sessionId={} seq={}",
                    sessionId, seq, e);
        }
    }
}
