package cn.kong.eon.engine.scope;

import java.util.Objects;
import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.kong.eon.engine.EngineConfig;
import cn.kong.eon.engine.TokenEstimator;
import cn.kong.eon.engine.port.store.LedgerPort;
import cn.kong.eon.engine.port.store.SnapshotPort;
import cn.kong.eon.engine.port.store.SnapshotPort.SessionSnapshot;
import cn.kong.eon.engine.context.CompressionState;
import cn.kong.eon.engine.window.ContextWindow;

/**
 * 会话管理器：TTL 缓存（Caffeine）、从账本+快照重建、并发互斥的第一道闸。
 *
 * <p>缓存时间由 run 生命周期驱动：抢到运行权与结束各 retime 一次，读不续期——
 * 过期只以最后一次 retime 为准。运行中永不过期：逐出在途 run 的实例会破坏「同会话至多一个 RUNNING」。
 *
 * <p>恢复 = 账本重放重建窗口 + 快照恢复累计状态。重放起点由压缩水位决定，已被摘要覆盖的消息不再入窗。
 * 快照不自洽时降级为全量回放且不恢复摘要——最坏是多占 token，按坏快照回放会静默丢历史。
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final LoadingCache<String, Session> cache;
    private final LedgerPort ledger;
    private final SnapshotPort snapshots;
    private final TokenEstimator estimator;
    private final EngineConfig config;
    private final long idleNanos;

    public SessionManager(LedgerPort ledger,
                          SnapshotPort snapshots,
                          TokenEstimator estimator,
                          EngineConfig config) {
        this(ledger, snapshots, estimator, config, Ticker.systemTicker(), null);
    }

    /** 测试注入：假时钟（确定性 TTL 测试）+ 同步淘汰执行器（回调内联，断言无竞态）。 */
    SessionManager(LedgerPort ledger,
                   SnapshotPort snapshots,
                   TokenEstimator estimator,
                   EngineConfig config,
                   Ticker ticker,
                   Executor removalExecutor) {
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.estimator = estimator;
        this.config = config;
        this.idleNanos = config.sessionIdleTtl().toNanos();

        Caffeine<String, Session> builder = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Session>() {
                    @Override
                    public long expireAfterCreate(String id, Session session, long now) {
                        return ttl(session);
                    }

                    @Override
                    public long expireAfterRead(String id, Session session, long now, long currentDuration) {
                        return currentDuration; // 读不续期：保持原过期时刻
                    }

                    @Override
                    public long expireAfterUpdate(String id, Session session, long now, long currentDuration) {
                        return ttl(session);
                    }
                })
                .scheduler(Scheduler.systemScheduler())
                .ticker(ticker)
                .removalListener(this::onRemoval);
        if (config.sessionCacheMaxSize() > 0) {
            builder.maximumSize(config.sessionCacheMaxSize());
        }
        if (removalExecutor != null) {
            builder.executor(removalExecutor);
        }
        this.cache = builder.build(new CacheLoader<>() {
            @Override
            public Session load(String id) {
                return SessionManager.this.load(id);
            }
        });
    }

    /** 运行中永不过期，其余按闲置 TTL。 */
    private long ttl(Session session) {
        SessionStatus st = session.status();
        return st == SessionStatus.RUNNING || st == SessionStatus.STOPPING
                ? Long.MAX_VALUE
                : idleNanos;
    }

    /** 取会话：缓存命中或从持久层重建（按 key 原子装载）。返回的会话可能忙（调用方需 tryAcquire）。 */
    public Session acquire(String sessionId) {
        return cache.get(sessionId);
    }

    public Session peek(String sessionId) {
        return cache.getIfPresent(sessionId);
    }

    /** 以会话当前状态重估缓存时间：run 抢到运行权后、run 结束（release 之后）各调用一次。 */
    public void retime(Session session) {
        cache.put(session.id(), session);
    }

    /** 测试触发：立即执行缓存维护（淘汰到期条目）。 */
    void cleanUp() {
        cache.cleanUp();
    }

    /**
     * 显式淘汰：空闲则移除并返回 true；不存在视为成功（幂等）；运行中返回 false。
     * 仅清内存不触碰持久层，账本与快照的清理由外部（登记属主）负责。
     */
    public boolean evict(String sessionId) {
        Session session = cache.getIfPresent(sessionId);
        if (session == null) {
            return true;
        }
        if (!session.evictIfIdle()) {
            return false;
        }
        cache.invalidate(sessionId); // EXPLICIT 回调不介入，此处已同步处理
        return true;
    }

    /** TTL/容量淘汰回调：IDLE 经 CAS 关闭；CLOSED 死对象直接丢弃。 */
    private void onRemoval(String id, Session session, RemovalCause cause) {
        if (session == null || cause == RemovalCause.EXPLICIT || cause == RemovalCause.REPLACED) {
            return; // 显式 evict 与 retime 的同实例替换已同步处理
        }
        if (session.evictIfIdle()) {
            log.info("[Session] {} 淘汰闲置会话 {} (TTL={} 分钟)", cause.name(), id, config.sessionIdleTtl().toMinutes());
        } else {
            log.warn("[Session] {} 逐出非闲置会话 {}，存在双实例风险", cause.name(), id);
        }
    }

    private Session load(String id) {
        Session session = new Session(id, new ContextWindow(estimator, config.tailGuardBlocks()));
        SessionSnapshot snapshot = snapshots.load(id);
        CompressionState compressionState;
        // 恢复压缩状态，不存在则初始化
        if (Objects.isNull(snapshot)) {
            compressionState = CompressionState.EMPTY;
        } else {
            compressionState = snapshot.compression();
        }
        // 恢复或重建：恢复的前提是快照存在，无快照的新会话走全量回放
        boolean resume = snapshot != null && compressionState.consistentWith(ledger.size(id));
        long fromSeq = resume ? compressionState.summarizedThroughSeq() + 1 : 1;
        ledger.replay(id, fromSeq).forEach(r -> session.window().addFromMessage(r.seq(), r.msg()));

        if (resume) {
            session.state().restore(snapshot);
            log.info("[Session] 会话[{}]恢复, fromSeq={}, blocks={}", id, fromSeq, session.window().size());
        } else {
            log.warn("[Session] 快照不自洽，降级为全量回放 session={}, compression={}", id, compressionState);
        }
        return session;
    }
}
