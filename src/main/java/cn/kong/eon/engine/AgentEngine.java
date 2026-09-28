package cn.kong.eon.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import cn.kong.eon.engine.context.Compressor;
import cn.kong.eon.engine.context.ContextPipeline;
import cn.kong.eon.engine.context.ContextSection;
import cn.kong.eon.engine.event.EngineEvent;
import cn.kong.eon.engine.event.EventPublisher;
import cn.kong.eon.engine.hook.Hook;
import cn.kong.eon.engine.hook.HookChain;
import cn.kong.eon.engine.loop.RunResult;
import cn.kong.eon.engine.loop.TurnLoop;
import cn.kong.eon.engine.port.store.ToolResultPort;
import cn.kong.eon.engine.event.EventSink;
import cn.kong.eon.engine.port.interaction.InteractionChannel;
import cn.kong.eon.engine.port.store.LedgerPort;
import cn.kong.eon.engine.port.model.LlmPort;
import cn.kong.eon.engine.port.store.SnapshotPort;
import cn.kong.eon.engine.port.tool.ToolSet;
import cn.kong.eon.engine.port.store.Workspace;
import cn.kong.eon.engine.scope.Session;
import cn.kong.eon.engine.scope.SessionManager;
import cn.kong.eon.engine.scope.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 引擎门面。命令：run / interrupt / answer / evict / close；事件：subscribe（只读流）。
 * 端口与注入段经 Builder 装配，估算器、压缩工具箱、会话缓存为引擎内置。
 */
public final class AgentEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private final EngineConfig config;
    private final EventPublisher events = new EventPublisher();
    private final SessionManager sessions;
    private final TurnLoop loop;
    private final InteractionChannel interaction;
    private final ExecutorService runPool;
    private final ExecutorService toolPool;

    private AgentEngine(Builder b) {
        this.config = b.config;
        this.interaction = b.interaction;

        this.runPool = Executors.newCachedThreadPool(r -> named(r, "engine-run"));
        this.toolPool = Executors.newFixedThreadPool(
                Math.max(1, config.toolParallelism()), r -> named(r, "engine-tool"));

        TokenEstimator estimator = new TokenEstimator();
        this.sessions = new SessionManager(b.ledger, b.snapshots, estimator, config);
        HookChain hooks = new HookChain(b.hooks, events);
        Compressor compressor = new Compressor(b.llm, b.ledger, config);
        ContextPipeline pipeline = new ContextPipeline(
                config, estimator, b.sections, compressor, events);
        TurnLoop.Deps deps = new TurnLoop.Deps(
                b.llm, b.tools, hooks, pipeline,
                b.ledger, b.snapshots, b.toolResult,
                b.interaction, b.workspace, events);
        this.loop = new TurnLoop(config, deps, toolPool);
    }

    private static Thread named(Runnable r, String prefix) {
        Thread t = new Thread(r, prefix + "-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    }

    private static final AtomicLong SEQ = new AtomicLong();

    // ---- 命令 ----

    /** 发起一次运行；同一会话同时至多一个 RUNNING */
    public void run(String sessionId, String userInput) {
        Session session = sessions.acquire(sessionId);
        if (!session.tryAcquire()) {
            throw new EngineException("会话正在运行中");
        }
        try {
            runPool.submit(() -> runTask(session, userInput));
        } catch (RejectedExecutionException e) {
            session.release();
            sessions.retime(session);
            throw new EngineException(e.getMessage());
        }
    }

    /** run 任务体：发布起点事件 → 驱动循环 → finally 收敛状态并刷新缓存时间。 */
    private RunResult runTask(Session session, String userInput) {
        events.publish(new EngineEvent.RunStarted(session.id()));
        try {
            sessions.retime(session);
            RunResult run = loop.run(session, userInput);
            log.info("[Engine] 任务执行结束，session={}, userInput={}, run={}", session.id(), userInput, run);
            return run;
        } finally {
            session.release();           // 必须先 release，retime 才能看到 IDLE
            sessions.retime(session);
        }
    }

    /** 请求中断：标记式软中断，循环在下一个检查点优雅停止。返回 false = 无在途 run 可中断。 */
    public boolean interrupt(String sessionId) {
        Session session = sessions.peek(sessionId);
        if (session == null) {
            return false;
        }
        SessionStatus status = session.status();
        if (status != SessionStatus.RUNNING) {
            return false; // 无在途 run，如实返回而非空操作成功
        }
        session.requestInterrupt();
        log.info("[Engine] session[{}]中断, 状态[{}]", sessionId, status);
        return true;
    }

    /** 投递提问答案（按会话校验归属）；同步交互通道返回 false。 */
    public boolean answer(String sessionId, String questionId, List<InteractionChannel.Answer> answers) {
        return interaction.deliverAnswer(sessionId, questionId, answers);
    }

    /** 淘汰会话缓存，仅清内存不动持久层。 */
    public boolean evict(String sessionId) {
        boolean evicted = sessions.evict(sessionId);
        if (evicted) {
            log.info("[Engine] 会话缓存已淘汰: {}", sessionId);
        }
        return evicted;
    }

    public void subscribe(EventSink sink) {
        events.subscribe(sink);
    }

    public void unsubscribe(EventSink sink) {
        events.unsubscribe(sink);
    }

    /** 优雅关闭：等待在途 run 结束，上限 = 工具超时 + 30 秒。 */
    public void shutdown() {
        runPool.shutdown();
        try {
            if (!runPool.awaitTermination(
                    config.toolTimeout().plusSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[Engine] 优雅关闭超时，仍有在途 run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        toolPool.shutdown();
    }

    /** 立即关闭：中断在途线程，不等待。 */
    @Override
    public void close() {
        runPool.shutdownNow();
        toolPool.shutdownNow();
    }

    // ---- 装配 ----

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private EngineConfig config = EngineConfig.defaults();
        private final List<Hook> hooks = new ArrayList<>();
        private LlmPort llm;
        private ToolSet tools;
        private List<ContextSection> sections = List.of();
        private LedgerPort ledger;
        private SnapshotPort snapshots;
        private ToolResultPort toolResult;
        private InteractionChannel interaction;
        private Workspace workspace;

        public Builder config(EngineConfig v) {
            this.config = v;
            return this;
        }

        public Builder llm(LlmPort v) {
            this.llm = v;
            return this;
        }

        public Builder tools(ToolSet v) {
            this.tools = v;
            return this;
        }

        public Builder hooks(Hook... values) {
            for (Hook h : values) {
                hooks.add(h);
            }
            return this;
        }

        /** 有序注入段；记忆/环境/待办等经此进入上下文。 */
        public Builder sections(List<ContextSection> v) {
            this.sections = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder ledger(LedgerPort v) {
            this.ledger = v;
            return this;
        }

        public Builder snapshots(SnapshotPort v) {
            this.snapshots = v;
            return this;
        }

        public Builder toolResult(ToolResultPort v) {
            this.toolResult = v;
            return this;
        }

        public Builder interaction(InteractionChannel v) {
            this.interaction = v;
            return this;
        }

        public Builder workspace(Workspace v) {
            this.workspace = v;
            return this;
        }

        public AgentEngine build() {
            List<String> missing = new ArrayList<>();
            if (llm == null) missing.add("llm");
            if (tools == null) missing.add("tools");
            if (ledger == null) missing.add("ledger");
            if (snapshots == null) missing.add("snapshots");
            if (toolResult == null) missing.add("toolResult");
            if (interaction == null) missing.add("interaction");
            if (workspace == null) missing.add("workspace");
            if (!missing.isEmpty()) {
                throw new EngineException("以下端口未注入: " + String.join(", ", missing));
            }
            return new AgentEngine(this);
        }
    }
}
