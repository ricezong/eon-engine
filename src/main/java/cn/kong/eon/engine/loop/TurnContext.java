package cn.kong.eon.engine.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cn.kong.eon.engine.EngineConfig;
import cn.kong.eon.engine.event.EventPublisher;
import cn.kong.eon.engine.port.model.LlmReply;
import cn.kong.eon.engine.scope.Session;
import cn.kong.eon.engine.scope.Task;
import cn.kong.eon.engine.window.ContextWindow;

/**
 * 一次运行（run）的上下文，run 级实例、逐轮复用。
 * reply 在模型返回后填充；nudges 累积注入下一轮组装；attributes 承载守卫的跨轮计数。
 */
public final class TurnContext {

    private final Session session;
    private final Task task;
    private final EventPublisher events;
    private final EngineConfig config;
    private final List<String> nudges = new ArrayList<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private LlmReply reply;
    /** 本次 run 内的当前轮次（1 起算），由循环每轮更新。 */
    private volatile int runTurn;

    public TurnContext(Session session, Task task, EventPublisher events, EngineConfig config) {
        this.session = session;
        this.task = task;
        this.events = events;
        this.config = config;
    }

    public Session session() {
        return session;
    }

    public Task task() {
        return task;
    }

    public EventPublisher events() {
        return events;
    }

    public EngineConfig config() {
        return config;
    }

    public ContextWindow window() {
        return session.window();
    }

    public String sessionId() {
        return session.id();
    }

    public LlmReply reply() {
        return reply;
    }

    public void setReply(LlmReply reply) {
        this.reply = reply;
    }

    /** 本次 run 内的当前轮次（1 起算）。与 SessionState.turnCount（会话累计）口径不同。 */
    public int runTurn() {
        return runTurn;
    }

    public void setRunTurn(int runTurn) {
        this.runTurn = runTurn;
    }

    /** 累积提醒（钩子写入；组装后由循环清空）。 */
    public List<String> nudges() {
        return nudges;
    }

    /** run 级扩展槽：钩子与守卫存放跨轮状态；随本次 run 结束自然丢弃，无需重置。 */
    public Map<String, Object> attributes() {
        return attributes;
    }
}
