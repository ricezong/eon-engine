package cn.kong.eon.engine.loop;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import cn.kong.eon.engine.EngineConfig;
import cn.kong.eon.engine.context.ContextPipeline;
import cn.kong.eon.engine.context.ResultIntake;
import cn.kong.eon.engine.event.EngineEvent;
import cn.kong.eon.engine.event.EngineEvent.ModelDelta.Kind;
import cn.kong.eon.engine.event.EventPublisher;
import cn.kong.eon.engine.hook.HookChain;
import cn.kong.eon.engine.hook.HookChain.HookOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import cn.kong.eon.engine.msg.ChatMsg;
import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolResult;
import cn.kong.eon.engine.msg.ToolSpec;
import cn.kong.eon.engine.port.store.ToolResultPort;
import cn.kong.eon.engine.port.interaction.InteractionChannel;
import cn.kong.eon.engine.port.interaction.InteractionChannel.Decision;
import cn.kong.eon.engine.port.model.LlmPort;
import cn.kong.eon.engine.port.model.LlmPortException;
import cn.kong.eon.engine.port.model.LlmReply;
import cn.kong.eon.engine.port.model.LlmRequest;
import cn.kong.eon.engine.port.model.StreamCallbacks;
import cn.kong.eon.engine.port.store.LedgerPort;
import cn.kong.eon.engine.port.store.SnapshotPort;
import cn.kong.eon.engine.port.store.SnapshotPort.SessionSnapshot;
import cn.kong.eon.engine.port.store.Workspace;
import cn.kong.eon.engine.port.tool.Tool;
import cn.kong.eon.engine.port.tool.ToolSet;
import cn.kong.eon.engine.scope.Session;
import cn.kong.eon.engine.scope.Task;
import cn.kong.eon.engine.stop.StopCategory;
import cn.kong.eon.engine.port.tool.ToolContext;

/**
 * 主循环——整个引擎唯一的 while。
 *
 * <p>每轮:PreModel 钩子 → 组装 → 模型 → 提交关卡(落盘) → PostModel 钩子
 * →(无调用且无需补救则正常退出;无调用需补救则注入 Nudge 重试)
 * → PreTool 钩子(门禁) → 执行工具 → PostTool 钩子 → 入账关卡 → 回填账本与窗口。
 *
 * <p>中断检查点:轮首 / 组装前 / 工具执行前。
 */
public final class TurnLoop {

    private static final Logger log = LoggerFactory.getLogger(TurnLoop.class);

    private static final String TRUNCATED_TEXT_NUDGE =
            "上一条回复因输出长度限制被截断。请从截断处继续输出剩余部分；如果剩余内容仍然过长，请分多次输出。";
    private static final String BROKEN_TOOLS_NUDGE =
                    """
                    上一条回复中部分工具调用不完整，已丢弃未执行，其余调用照常执行。
                    请重新发起被丢弃的工具调用，并确保参数是完整、合法的 JSON；如因输出过长被截断，请分多次输出。
                    """;

    /** 依赖聚合，由 AgentEngine 装配注入。 */
    public record Deps(
            LlmPort llm,
            ToolSet tools,
            HookChain hooks,
            ContextPipeline pipeline,
            LedgerPort ledger,
            SnapshotPort snapshots,
            ToolResultPort toolResult,
            InteractionChannel interaction,
            Workspace workspace,
            EventPublisher events) {
    }

    /** 已提交到工具池的调用。 */
    private record Pooled(int index, ToolCall call, Duration timeout, Future<ToolResult> future) {
    }

    private final EngineConfig config;
    private final Deps deps;
    private final ExecutorService toolPool;
    private final ResultIntake intake;

    public TurnLoop(EngineConfig config, Deps deps, ExecutorService toolPool) {
        this.config = config;
        this.deps = deps;
        this.toolPool = toolPool;
        this.intake = new ResultIntake(deps.toolResult(), config);
    }

    /** 驱动一次 run。调用方已持有会话（RUNNING）。 */
    public RunResult run(Session session, String userInput) {
        Task task = Task.of(userInput);
        TurnContext turn = new TurnContext(session, task, deps.events(), config);
        // 步数上限按"单次 run"计：会话累计轮次由 SessionState 单独统计并随快照持久化
        int turnInRun = 0;
        try {
            log.info("[Run] 开始 session={}, inputChars={}", session.id(), userInput.length());
            // 用户输入：事件 → 账本 → 窗口
            deps.events().publish(new EngineEvent.UserMessage(session.id(), userInput));
            long userSeq = deps.ledger().append(session.id(), ChatMsg.user(userInput));
            session.window().addUser(userSeq, userInput);

            while (true) {
                // ---- 检查点：中断 / 步数上限 ----
                if (session.isInterruptRequested()) {
                    return finish(session, StopCategory.INTERRUPTED, "用户中断");
                }
                if (turnInRun >= config.maxTurns()) {
                    return finish(session, StopCategory.MAX_TURNS, "本次运行达到步数上限 " + config.maxTurns());
                }
                int turnNo = turnInRun + 1;
                turn.setRunTurn(turnNo);
                session.state().turnStarted();
                deps.events().publish(new EngineEvent.TurnStarted(session.id(), turnNo));
                log.debug("[Run] 轮次开始 session={}, turn={}", session.id(), turnNo);

                // ---- 模型前：钩子 ----
                HookOutcome pre = deps.hooks().runPreModel(turn);
                if (pre.stop().isPresent()) {
                    return finish(session, pre.stop().get().category(), pre.stop().get().message());
                }
                turn.nudges().addAll(pre.nudges()); // 本轮组装即生效
                if (session.isInterruptRequested()) {
                    return finish(session, StopCategory.INTERRUPTED, "用户中断");
                }

                // ---- 组装 ----
                List<ToolSpec> specs = deps.tools().specs();
                List<ChatMsg> msgs = deps.pipeline().assemble(session, List.copyOf(turn.nudges()), specs);
                LlmRequest request = new LlmRequest(msgs, specs);
                turn.nudges().clear();

                // ---- 模型 ----
                long modelStart = System.currentTimeMillis();
                LlmReply reply = callModel(session.id(), turnNo, request);
                log.debug("[Run] 模型返回 session={}, turn={}, costMs={}, toolCalls={}, finishReason={}",
                        session.id(), turnNo, System.currentTimeMillis() - modelStart, reply.toolCalls().size(), reply.finishReason());
                turn.setReply(reply);
                session.state().addUsage(reply.usage());

                // ---- 提交关卡 ----
                Commit commit = commitGate(session.id(), turnNo, reply);
                long assistantSeq = deps.ledger().append(session.id(),
                        ChatMsg.assistant(reply.text(), reply.thinking(), commit.calls()));
                session.window().addAssistant(assistantSeq, reply.text(), reply.thinking(), commit.calls());
                if (commit.nudge() != null) {
                    turn.nudges().add(commit.nudge()); // 注入下一轮组装
                }
                deps.events().publish(new EngineEvent.TurnFinished(session.id(), turnNo,
                        reply.text(), reply.thinking(), commit.calls(), reply.finishReason(), reply.usage()));

                // ---- 模型后：钩子（每轮有回复必经，含补救轮） ----
                HookOutcome post = deps.hooks().runPostModel(turn);
                if (post.stop().isPresent()) {
                    return finish(session, post.stop().get().category(), post.stop().get().message());
                }
                turn.nudges().addAll(post.nudges()); // 注入下一轮组装

                // ---- 退出 / 补救重试 ----
                if (commit.calls().isEmpty()) {
                    if (commit.nudge() == null) {
                        return finish(session, StopCategory.COMPLETED, "正常完成");
                    }
                    if (turnInRun + 1 >= config.maxTurns()) {
                        return finish(session, StopCategory.MAX_TURNS,
                                "回复不可用且已达步数上限 " + config.maxTurns());
                    }
                    turnInRun++;
                    continue;
                }

                // ---- 工具前：钩子（门禁审批） ----
                HookOutcome preTool = deps.hooks().runPreTool(turn, commit.calls());
                if (preTool.stop().isPresent()) {
                    return finish(session, preTool.stop().get().category(), preTool.stop().get().message());
                }
                turn.nudges().addAll(preTool.nudges()); // 注入下一轮组装
                if (session.isInterruptRequested()) {
                    return finish(session, StopCategory.INTERRUPTED, "用户中断");
                }

                // ---- 工具执行 ----
                List<ToolResult> results = executeTools(session, commit.calls(), turnNo);

                // ---- 工具后：钩子（纯观察，不能终止） ----
                deps.hooks().runPostTool(turn, results);

                // ---- 入账关卡 + 回填账本与窗口 ----
                long baseSeq = deps.ledger().size(session.id());
                for (int i = 0; i < results.size(); i++) {
                    ToolResult r = results.get(i);
                    String stored = intake.admit(session.id(), r, baseSeq + i + 1);
                    long seq = deps.ledger().append(session.id(),
                            ChatMsg.toolResult(r.toolCallId(), r.toolName(), stored, r.toolView()));
                    session.window().addToolResult(seq, r.toolCallId(), r.toolName(), stored, r.toolView());
                }

                turnInRun++;
            }
        } catch (LlmPortException e) {
            log.error("[Run] 模型调用失败 session={}: {}", session.id(), e.getMessage(), e);
            return finish(session, StopCategory.INTERNAL_ERROR, "模型调用失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[Run] 内部错误 session={}", session.id(), e);
            return finish(session, StopCategory.INTERNAL_ERROR, "内部错误: " + e);
        }
    }

    // ---- 提交关卡 ----

    /** 提交关卡结论：calls 为落盘并执行的调用，nudge 非空表示需模型补救，两者可同时存在。 */
    record Commit(List<ToolCall> calls, String nudge) {
    }

    /**
     * 唯一不变量：落盘必可重放（账本 append-only，残缺参数一旦落盘永久毒化会话）。
     * 残缺只丢尾部、保留前缀——流式顺序保证残缺必为后缀；length 仅在无调用时改判为正文续写。
     */
    static Commit commitGate(String sessionId, int turnNo, LlmReply reply) {
        List<ToolCall> calls = reply.toolCalls();
        int brokenAt = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (!calls.get(i).replayable()) {
                brokenAt = i;
                break;
            }
        }
        if (brokenAt >= 0) {
            log.warn("[Run] 工具调用参数残缺，保留前缀丢弃尾部 session={}, turn={}, brokenAt={}/{}",
                    sessionId, turnNo, brokenAt + 1, calls.size());
            return new Commit(calls.subList(0, brokenAt), BROKEN_TOOLS_NUDGE);
        }
        if (reply.truncated() && calls.isEmpty()) {
            return new Commit(calls, TRUNCATED_TEXT_NUDGE);
        }
        return new Commit(calls, null);
    }

    // ---- 模型调用 ----

    private LlmReply callModel(String sessionId, int turnNo, LlmRequest request) {
        LlmReply[] done = new LlmReply[1];
        LlmPortException[] failed = new LlmPortException[1];
        deps.llm().chat(request, new StreamCallbacks() {
            @Override
            public void onTextDelta(String delta) {
                deps.events().publish(new EngineEvent.ModelDelta(sessionId, turnNo, Kind.TEXT, null, delta));
            }

            @Override
            public void onThinkingDelta(String delta) {
                deps.events().publish(new EngineEvent.ModelDelta(sessionId, turnNo, Kind.THINKING, null, delta));
            }

            @Override
            public void onToolCallDelta(String toolCallId, String delta) {
                deps.events().publish(new EngineEvent.ModelDelta(sessionId, turnNo, Kind.TOOL_ARGS, toolCallId, delta));
            }

            @Override
            public void onDone(LlmReply reply) {
                done[0] = reply;
            }

            @Override
            public void onError(LlmPortException error) {
                failed[0] = error;
            }
        });
        if (failed[0] != null) {
            throw failed[0];
        }
        if (done[0] == null) {
            throw new LlmPortException("适配器未回调终点（onDone/onError）");
        }
        return done[0];
    }

    // ---- 工具执行 ----

    /**
     * 工具执行：inline 的在 run 线程上跑（不进池、不受超时约束），其余进池并行、各自按声明的超时计时。
     *
     * <p>并行任务先入池再跑内联任务——内联可能长时间阻塞，不能让并行任务干等。
     * 单个工具超时转 failure 并 cancel，不中断循环；中断仍是检查点语义，不在等待期间抢占。
     */
    private List<ToolResult> executeTools(Session session, List<ToolCall> calls, int turnNo) {
        ToolContext ctx = new ToolContext(
                session.id(), turnNo, deps.workspace(), deps.toolResult(),
                deps.interaction(), deps.events());

        ToolResult[] out = new ToolResult[calls.size()];
        boolean[] inline = new boolean[calls.size()];
        List<Pooled> pooled = new ArrayList<>();

        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            Tool tool = deps.tools().find(call.name()).orElse(null);
            inline[i] = tool != null && tool.inline();
            if (inline[i]) {
                continue;
            }
            Duration timeout = tool == null ? config.toolTimeout() : tool.timeout(config.toolTimeout());
            try {
                pooled.add(new Pooled(i, call, timeout, toolPool.submit(() -> runOne(session, call, ctx))));
            } catch (RejectedExecutionException e) {
                out[i] = ToolResult.failure(call, "工具线程池已关闭");
            }
        }
        for (int i = 0; i < calls.size(); i++) {
            if (inline[i]) {
                out[i] = runOne(session, calls.get(i), ctx);
            }
        }
        for (Pooled p : pooled) {
            out[p.index()] = await(session, p);
        }
        return List.of(out);
    }

    private ToolResult runOne(Session session, ToolCall call, ToolContext ctx) {
        long start = System.currentTimeMillis();
        log.debug("[Tool] 开始 session={}, tool={}, callId={}", session.id(), call.name(), call.id());
        deps.events().publish(new EngineEvent.ToolExecutionStarted(session.id(), call.id(), call.name()));
        ToolResult result = executeWithGate(session, call, ctx);
        deps.events().publish(new EngineEvent.ToolExecutionFinished(session.id(), result));
        log.debug("[Tool] 结束 session={}, tool={}, callId={}, success={}, costMs={}",
                session.id(), call.name(), call.id(), result.success(), System.currentTimeMillis() - start);
        return result;
    }

    private ToolResult await(Session session, Pooled p) {
        try {
            return p.future().get(p.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            p.future().cancel(true);
            log.warn("[Tool] 超时取消 session={}, tool={}, callId={}, 上限={}s",
                    session.id(), p.call().name(), p.call().id(), p.timeout().toSeconds());
            return ToolResult.failure(p.call(),
                    "工具执行超时（上限 " + p.timeout().toSeconds() + " 秒），已取消");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.future().cancel(true);
            return ToolResult.failure(p.call(), "执行被中断");
        } catch (CancellationException e) {
            return ToolResult.failure(p.call(), "执行被取消（引擎正在关闭）");
        } catch (ExecutionException e) {
            // 任务体已捕获工具异常，这里只可能是基础设施错误
            return ToolResult.failure(p.call(), "执行基础设施错误: " + e.getCause());
        }
    }

    /** 破坏性工具走门禁；其余直接执行。 */
    private ToolResult executeWithGate(Session session, ToolCall call, ToolContext ctx) {
        if (deps.tools().isDestructive(call.name()) && !config.autoApproveDestructive()) {
            var spec = deps.tools().find(call.name()).map(t -> t.spec()).orElse(null);
            Decision decision = deps.interaction().gate(call, spec);
            if (decision == Decision.REJECTED) {
                log.warn("[Tool] 门禁拒绝 session={}, tool={}, callId={}", session.id(), call.name(), call.id());
                return ToolResult.failure(call, "门禁拒绝：破坏性操作未获批准");
            }
        }
        return deps.tools().execute(call, ctx);
    }

    // ---- 收尾 ----

    private RunResult finish(Session session, StopCategory category, String message) {
        RunResult result = new RunResult(category, message, session.state().totalUsage());
        log.info("[Run] 结束 session={}, category={}, turns={}, totalTokens={}, detail={}",
                session.id(), category, session.state().turnCount(),
                session.state().totalUsage().total(), message);
        deps.events().publish(new EngineEvent.RunFinished(
                session.id(), category, message, session.state().totalUsage()));
        try {
            deps.snapshots().save(new SessionSnapshot(
                    session.id(),
                    session.state().totalUsage(),
                    session.state().compression(),
                    session.state().turnCount(),
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[Run] 快照保存失败 session={}", session.id(), e);
        }
        return result;
    }
}
