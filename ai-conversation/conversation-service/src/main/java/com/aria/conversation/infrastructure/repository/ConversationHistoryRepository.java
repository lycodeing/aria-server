package com.aria.conversation.infrastructure.repository;

import com.aria.common.web.redis.RedisCacheHelper;
import com.aria.common.web.redis.RedisLockHelper;
import com.aria.conversation.domain.ConversationMessage;
import com.aria.conversation.infrastructure.mq.ConversationMessagePublisher;
import com.aria.conversation.infrastructure.persistence.mapper.ConversationMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话历史 Repository。
 *
 * <p>职责一：维护 Redis List 热数据（key: {@code chat:session:{id}}），供 AI 对话和 WebSocket 路由实时读取。
 * <p>职责二：通过 Lua 原子脚本生成 session 内单调递增 seq，支持客户端断线重连的 sinceSeq 增量同步。
 * <p>职责三：每次追加消息时，通过 {@link ConversationMessagePublisher} 发布到 RabbitMQ，
 * 由 Consumer 异步持久化到 PostgreSQL。
 *
 * <p>双写策略：
 * <pre>
 *   append() / appendAgentMessage()
 *     ├─ Redis List  chat:session:{id}  （热数据，四元组 [role, content, seq, timestamp]，TTL 24h）
 *     └─ RabbitMQ MESSAGE 事件（含 seq） （冷存储，异步持久化至 DB）
 * </pre>
 *
 * <p>角色约定：
 * <ul>
 *   <li>Redis List / AI 请求：user / assistant（OpenAI 标准）</li>
 *   <li>DB 持久化：user / assistant（AI）/ agent（人工座席，便于质检分析）</li>
 * </ul>
 */
@Slf4j
@Repository
public class ConversationHistoryRepository {

    // ---- Redis key 前缀 ----
    private static final String KEY_PREFIX     = "chat:session:";
    private static final String SEQ_KEY_PREFIX = "chat:seq:";

    /**
     * 原子初始化 + INCR Lua 脚本。
     *
     * <p>解决"INCR 与 DB max 兜底 SET 之间的竞态"：在 Redis 单线程内原子完成
     * "key 不存在则 SET dbMax → 否则跳过 → INCR 返回新值"，杜绝并发首次写入时 seq 跳跃。
     *
     * <p>KEYS[1] = seq key；ARGV[1] = DB max seq（兜底初始值）；ARGV[2] = key TTL（秒）
     */
    private static final String INIT_AND_INCR_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            end
            return redis.call('INCR', KEYS[1])
            """;

    /**
     * 强制重置 seq 基准并 INCR Lua 脚本。
     *
     * <p>用于 Redis TTL 过期后 seq 从 1 重新开始时，以 DB max 为基准重置后再 INCR，
     * 防止与已持久化的 seq 产生冲突。
     *
     * <p>KEYS[1] = seq key；ARGV[1] = DB max seq；ARGV[2] = key TTL（秒）
     */
    private static final String RESET_AND_INCR_LUA = """
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return redis.call('INCR', KEYS[1])
            """;

    private static final RedisScript<Long> INIT_AND_INCR_SCRIPT =
            new DefaultRedisScript<>(INIT_AND_INCR_LUA, Long.class);

    private static final RedisScript<Long> RESET_AND_INCR_SCRIPT =
            new DefaultRedisScript<>(RESET_AND_INCR_LUA, Long.class);

    private static final long TTL_HOURS         = 24L;
    private static final int  MAX_HISTORY_TURNS = 20;
    /** Redis List 中每条消息占用的元素数（role / content / seq / timestamp 四元组） */
    private static final int  ELEMENTS_PER_MSG  = 4;

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_AGENT     = "agent";

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
     * 已成功初始化 seq 计数器的 sessionId 本地缓存。
     *
     * <p>优化目标：热路径（Redis key 已存在）跳过 DB 查询，只有冷路径（首次或 TTL 过期）才查 DB。
     * <p>Redis TTL 过期检测：若 Lua INCR 返回 1 且本地标记为已初始化，说明 Redis key 已过期重建，
     * 此时清除标记并用 DB max 重新修正基准值，防止 seq 从 1 重新开始与 DB 已有记录冲突。
     */
    private final Set<String> initializedSessions = ConcurrentHashMap.newKeySet();
    
    /**
     * 生成 session 内的下一个单调递增 seq（Redis Lua 原子 INCR + DB 兜底）。
     *
     * <p>热路径优化：已初始化的 session 跳过 DB 查询，仅走 Redis INCR；
     * 首次或 TTL 过期时查 DB 作为基准，由 Lua 原子初始化后再 INCR。
     *
     * @param sessionId 会话唯一标识
     * @return 单调递增 seq（≥ 1）
     */
    public long nextSeq(String sessionId) {
        String key = SEQ_KEY_PREFIX + sessionId;
        boolean alreadyInitialized = initializedSessions.contains(sessionId);
    
        // 冷路径：首次访问，查 DB 获取基准值
        long seq = coldPathInitSeq(key, sessionId, alreadyInitialized);
    
        // 热路径兜底：seq==1 且本地标记为已初始化，说明 Redis key TTL 已过期被重建
        if (seq == 1L && alreadyInitialized) {
            seq = hotPathRecoverSeq(key, sessionId);
        }
        return seq;
    }
    
    /**
     * 冷路径：初始化 seq 计数器。
     * 首次访问时查 DB 获取基准值，通过 Lua 原子初始化后再 INCR。
     *
     * @param key                Redis seq key
     * @param sessionId          会话 ID
     * @param alreadyInitialized 是否已初始化
     * @return INCR 后的 seq 值
     */
    private long coldPathInitSeq(String key, String sessionId, boolean alreadyInitialized) {
        long dbMaxBaseline = alreadyInitialized ? 0L : messageMapper.selectMaxSeq(sessionId);  // 冷路径：查 DB 作为初始基准，热路径跳过
        if (!alreadyInitialized) {
            initializedSessions.add(sessionId);
        }
    
        Long seq = lockHelper.executeLua(
                INIT_AND_INCR_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(dbMaxBaseline),
                String.valueOf(Duration.ofHours(TTL_HOURS).getSeconds())
        );
        if (seq == null) {
            throw new IllegalStateException("Redis seq INCR 返回 null, sessionId=" + sessionId);
        }
        return seq;
    }
    
    /**
     * 热路径恢复：Redis key TTL 过期后重新以 DB max 为基准修正 seq。
     * 防止与已持久化的 seq 产生冲突。
     *
     * @param key       Redis seq key
     * @param sessionId 会话 ID
     * @return 修正后的 seq 值
     */
    private long hotPathRecoverSeq(String key, String sessionId) {
        long dbMax = messageMapper.selectMaxSeq(sessionId);
        if (dbMax > 0) {
            // 重新执行 RESET_AND_INCR，强制设置为 dbMax 后再 INCR
            lockHelper.executeLua(
                    RESET_AND_INCR_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(dbMax),
                    String.valueOf(Duration.ofHours(TTL_HOURS).getSeconds())
            );
            return dbMax + 1L;
        }
        return 1L;
    }

    // -------------------------------------------------------
    // 写入路径
    // -------------------------------------------------------

    /**
     * 追加访客或 AI 消息（role = user / assistant），并发布到 MQ 异步持久化。
     *
     * @param sessionId 会话 ID
     * @param role      消息角色（user / assistant）
     * @param content   消息正文
     * @return 分配给该消息的 seq
     */
    public long append(String sessionId, String role, String content) {
        long seq = nextSeq(sessionId);
        writeToListWithTrim(sessionId, role, content, seq);
        publishMessageEvent(sessionId, role, content, seq);
        return seq;
    }

    /**
     * 追加人工座席消息。
     * Redis List 写入 assistant 角色（保持与 AI 历史格式兼容），DB 中标记为 agent（便于质检）。
     *
     * @return 分配给该消息的 seq
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
     * 获取全量历史消息列表，截断至最近 MAX_HISTORY_TURNS 轮。
     * 供 AI 对话和座席接入时加载上下文使用。
     */
    public List<ConversationMessage> findAll(String sessionId) {
        List<String> raw = cache.lRange(KEY_PREFIX + sessionId, 0, -1);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<ConversationMessage> messages = parseQuadruples(raw);
        if (messages.size() > MAX_HISTORY_TURNS) {
            messages = messages.subList(messages.size() - MAX_HISTORY_TURNS, messages.size());
        }
        return new ArrayList<>(messages);
    }

    /**
     * 增量查询：返回 seq 严格大于 sinceSeq 的消息列表（按 seq 升序）。
     *
     * <p>查询路径（先热后冷）：
     * <ol>
     *   <li>Redis 热数据健康且覆盖 sinceSeq 范围 → 过滤后直接返回</li>
     *   <li>Redis 缺失、数据早于 sinceSeq、或含脏数据（seq ≤ 0）→ 回退至 DB 查询</li>
     * </ol>
     *
     * @param sinceSeq 起始 seq（不含），客户端传入 lastSeq 以补齐空窗消息
     */
    public List<ConversationMessage> findSince(String sessionId, long sinceSeq) {
        List<String> raw = cache.lRange(KEY_PREFIX + sessionId, 0, -1);
        List<ConversationMessage> redisMsgs = parseQuadruples(raw);

        // 校验 Redis 数据健康：含 seq≤0 的脏数据时整体回退 DB
        boolean redisHealthy = redisMsgs.stream().allMatch(m -> m.seq() > 0L);

        if (redisHealthy && !redisMsgs.isEmpty()) {
            long earliestSeq = redisMsgs.get(0).seq();
            // Redis List 包含 sinceSeq 之后的全部数据时直接过滤返回
            if (earliestSeq <= sinceSeq + 1) {
                return redisMsgs.stream()
                        .filter(m -> m.seq() > sinceSeq)
                        .toList();
            }
        }

        // 回退 DB（覆盖 24h 前冷数据及 Redis 脏数据场景）
        log.debug("[History] 回退 DB 查询 sessionId={} sinceSeq={}", sessionId, sinceSeq);
        return messageMapper.findBySessionSinceSeq(sessionId, sinceSeq).stream()
                .map(e -> ConversationMessage.of(
                        e.getRole() != null ? e.getRole().getValue() : null,
                        e.getContent(),
                        e.getSeq()))
                .toList();
    }

    /**
     * 全量原子替换 Redis List（AI 流式回复完成后调用）。
     * 使用 Pipeline 完成 DEL + RPUSH + EXPIRE，避免并发场景下的竞态。
     */
    public void saveAll(String sessionId, List<ConversationMessage> messages) {
        List<String> elements = new ArrayList<>(messages.size() * ELEMENTS_PER_MSG);
        for (ConversationMessage msg : messages) {
            elements.add(msg.role());
            elements.add(msg.content());
            elements.add(String.valueOf(msg.seq()));
            elements.add(msg.timestamp() != null ? String.valueOf(msg.timestamp()) : "");
        }
        cache.replaceListAtomically(KEY_PREFIX + sessionId, elements, Duration.ofHours(TTL_HOURS));

        // 发布最后一条 assistant 消息的 MQ 事件（使用已有 seq，不重新生成）
        if (!messages.isEmpty()) {
            ConversationMessage last = messages.get(messages.size() - 1);
            if (ROLE_ASSISTANT.equals(last.role()) && last.seq() > 0) {
                publishMessageEvent(sessionId, ROLE_ASSISTANT, last.content(), last.seq());
            }
        }
    }

    /** 清除会话历史（会话结束或重新开始时调用）。seq 计数器保留，靠 TTL 自动清理。 */
    public void delete(String sessionId) {
        cache.delete(KEY_PREFIX + sessionId);
        // 同步清理本地初始化标记，下次 nextSeq 时重新从 DB 获取基准值
        initializedSessions.remove(sessionId);
    }

    // -------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------

    /**
     * 原子写入 Redis List 四元组 [role, content, seq, timestamp] 并 LTRIM 保留最新历史。
     * 一次 RPUSH 多个元素，避免三次单独 push 之间被其他命令插入导致元组错位。
     */
    private void writeToListWithTrim(String sessionId, String role, String content, long seq) {
        String key = KEY_PREFIX + sessionId;
        cache.lRightPushAll(key, role, content, String.valueOf(seq),
                String.valueOf(System.currentTimeMillis()));
        cache.lTrim(key, -(MAX_HISTORY_TURNS * (long) ELEMENTS_PER_MSG), -1);
        cache.expire(key, Duration.ofHours(TTL_HOURS));
    }

    /**
     * 将 Redis List 原始字符串序列解析为 {@link ConversationMessage} 列表。
     *
     * <p>兼容两种格式：
     * <ul>
     *   <li>新格式（4元组）：[role, content, seq, timestamp]，ELEMENTS_PER_MSG=4</li>
     *   <li>旧格式（3元组）：[role, content, seq]，历史数据或滚动部署窗口内可能出现</li>
     * </ul>
     * 长度不是 3 或 4 的整数倍时，跳过尾部不完整记录（脏数据容错）。
     */
    private List<ConversationMessage> parseQuadruples(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        // 探测格式：4元组优先；若 size%4!=0 且 size%3==0 则按旧格式解析
        int step = ELEMENTS_PER_MSG;
        if (raw.size() % ELEMENTS_PER_MSG != 0 && raw.size() % 3 == 0) {
            step = 3;
            log.debug("[History] 检测到旧3元组格式数据，按兼容模式解析，size={}", raw.size());
        }
        List<ConversationMessage> messages = new ArrayList<>(raw.size() / step);
        for (int i = 0; i + step - 1 < raw.size(); i += step) {
            String role    = raw.get(i);
            String content = raw.get(i + 1);
            long   seq     = parseSeq(raw.get(i + 2));
            Long   timestamp = (step == 4) ? parseTimestamp(raw.get(i + 3)) : null;
            messages.add(new ConversationMessage(role, content, seq, timestamp));
        }
        return messages;
    }

    /** 容错解析 seq 字符串，非法时返回 0（旧数据走 DB 兜底）。 */
    private long parseSeq(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 容错解析 timestamp 字符串，空或非法时返回 null。 */
    private Long parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 通过 RabbitMQ Publisher 发布消息事件（异步持久化至 DB）。
     * Publisher 内置重试（3次），耗尽后捕获异常并打印 WARN，不阻断主流程。
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
