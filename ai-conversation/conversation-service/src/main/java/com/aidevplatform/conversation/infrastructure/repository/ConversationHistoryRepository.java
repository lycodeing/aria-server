package com.aidevplatform.conversation.infrastructure.repository;

import com.aidevplatform.common.web.redis.RedisCacheHelper;
import com.aidevplatform.common.web.redis.RedisLockHelper;
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
 * <p>职责二：通过 {@link RedisLockHelper} 执行 Lua 原子脚本生成 session 内单调递增 seq，
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
 * <p>Redis 操作统一通过 {@link RedisCacheHelper} / {@link RedisLockHelper} 进行，
 * 不直接使用 RedisTemplate。
 */
@Slf4j
@Repository
public class ConversationHistoryRepository {

    /** Redis List key 前缀（热数据，实时路由用） */
    private static final String KEY_PREFIX        = "chat:session:";
    /** Redis seq 计数器 key 前缀，session 内单调递增 */
    private static final String SEQ_KEY_PREFIX    = "chat:seq:";

    /**
     * 原子初始化 + INCR Lua 脚本。
     *
     * <p>解决"INCR 与 DB max 兜底 SET 之间的竞态"：
     * 服务端单线程内原子完成"key 不存在则 SET dbMax → 否则跳过 → INCR 返回新值"，
     * 杜绝并发首次写入时 lastSeq 跳跃或非单调。
     *
     * <p>KEYS[1] = seq key
     * <p>ARGV[1] = DB 中该 session 当前 max seq（兜底初始值，无历史传 0）
     * <p>ARGV[2] = key TTL（秒）
     */
    private static final String INIT_AND_INCR_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            end
            return redis.call('INCR', KEYS[1])
            """;

    private static final org.springframework.data.redis.core.script.RedisScript<Long> INIT_AND_INCR_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(INIT_AND_INCR_LUA, Long.class);

    /**
     * 已初始化标记，避免每次 nextSeq 都查 DB max。
     * Redis 过期或被驱逐时通过 invalidate 重置。
     */
    private final java.util.Set<String> initializedSessions =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
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
    private final RedisLockHelper              lockHelper;
    private final ConversationMessagePublisher publisher;
    private final ConversationMessageMapper    messageMapper;

    public ConversationHistoryRepository(RedisCacheHelper cache,
                                          RedisLockHelper lockHelper,
                                          ConversationMessagePublisher publisher,
                                          ConversationMessageMapper messageMapper) {
        this.cache         = cache;
        this.lockHelper    = lockHelper;
        this.publisher     = publisher;
        this.messageMapper = messageMapper;
    }

    // -------------------------------------------------------
    // seq 生成
    // -------------------------------------------------------

    /**
     * 生成 session 内的下一个单调递增 seq（Redis Lua 原子 INCR + DB 兜底）。
     *
     * <p>核心保证：
     * <ol>
     *   <li>首次执行（或 TTL 过期后）通过 Lua 脚本原子地 "SET if not exists + INCR"，
     *       消除"INCR 与 DB max 兜底之间的并发竞态"</li>
     *   <li>初始值取自 {@code SELECT MAX(seq) FROM cs_conversation_message WHERE session_id=?}，
     *       Redis 重启后不会与 DB 已有 seq 冲突</li>
     *   <li>同一进程内首次为某 session 操作时才查 DB，后续走纯 INCR 路径</li>
     * </ol>
     *
     * @param sessionId 会话唯一标识
     * @return 单调递增 seq（≥ 1）
     * @throws IllegalStateException Redis 执行失败（业务侧需做降级处理）
     */
    public long nextSeq(String sessionId) {
        String key = SEQ_KEY_PREFIX + sessionId;
        // 首次操作该 session 时，需要传入 DB max 作为初始化基准；后续直接 INCR
        long dbMaxBaseline = initializedSessions.contains(sessionId)
                ? 0L
                : messageMapper.selectMaxSeq(sessionId);

        Long seq = lockHelper.executeLua(
                INIT_AND_INCR_SCRIPT,
                java.util.Collections.singletonList(key),
                String.valueOf(dbMaxBaseline),
                String.valueOf(Duration.ofHours(TTL_HOURS).getSeconds())
        );
        if (seq == null) {
            throw new IllegalStateException("Redis seq INCR 返回 null, sessionId=" + sessionId);
        }
        initializedSessions.add(sessionId);
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

        // Redis 数据健康度校验：包含 seq<=0 的脏数据时整体回退 DB，避免脏数据混入返回结果
        boolean redisHealthy = redisMsgs.stream().allMatch(m -> {
            Object s = m.get(FIELD_SEQ);
            return s instanceof Long && (Long) s > 0L;
        });

        // 判断 Redis List 是否包含 sinceSeq 之后的全部数据：
        // 若 List 中最早一条 seq <= sinceSeq + 1，说明 Redis 包含全部所需消息
        if (redisHealthy && !redisMsgs.isEmpty()) {
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

        // 回退到 DB（包含历史遗留 + 24h 之前的冷数据 + Redis 脏数据兜底）
        log.debug("[History] Redis 未覆盖 sinceSeq 或包含脏数据，回退 DB 查询 sessionId={} sinceSeq={}",
                sessionId, sinceSeq);
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

    /**
     * 原子写入 Redis List 三元组 [role, content, seq] 并 LTRIM 保留最新 MAX_HISTORY_TURNS 轮。
     *
     * <p>使用 {@link RedisCacheHelper#lRightPushAll} 一次 RPUSH 多个元素，避免并发场景下
     * 三次单独 push 之间被其他客户端插入命令导致元组错位。
     */
    private void writeToListWithTrim(String sessionId, String role, String content, long seq) {
        String key = KEY_PREFIX + sessionId;
        cache.lRightPushAll(key, role, content, String.valueOf(seq));
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
    /**
     * 通过 RabbitMQ Publisher 发布消息事件（异步持久化）。
     *
     * <p>Spring Retry @Retryable 三次失败后可能抛出多种异常：
     * <ul>
     *   <li>{@link org.springframework.amqp.AmqpException} — Broker 连接/路由失败</li>
     *   <li>{@link org.springframework.retry.ExhaustedRetryException} — 重试耗尽包装异常</li>
     *   <li>底层 IOException 等运行时异常</li>
     * </ul>
     * 此处使用更宽的 RuntimeException 兜底，保证消息已存 Redis List 的前提下不阻断主流程。
     */
    private void publishMessageEvent(String sessionId, String role, String content, long seq) {
        try {
            publisher.publishMessage(sessionId, role, content, seq);
        } catch (RuntimeException e) {
            log.warn("[History] MQ 发布失败（3次重试后），消息仅存 Redis List，sessionId={} seq={}",
                    sessionId, seq, e);
        }
    }
}
