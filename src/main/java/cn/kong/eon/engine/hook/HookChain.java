package cn.kong.eon.engine.hook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import cn.kong.eon.engine.event.EngineEvent;
import cn.kong.eon.engine.event.EventPublisher;
import cn.kong.eon.engine.loop.TurnContext;
import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolResult;

/**
 * 钩子链：按 order 执行四阶段钩子。Nudge 累积注入下轮组装；Stop 立即生效，其后钩子不再执行。
 * 所有 Nudge/Stop 都发布 GuardTriggered 事件。
 */
public final class HookChain {

    private final List<Hook> hooks;
    private final EventPublisher events;

    public HookChain(List<Hook> hooks, EventPublisher events) {
        this.hooks = hooks.stream().sorted(Comparator.comparingInt(Hook::order)).toList();
        this.events = events;
    }

    public HookOutcome runPreModel(TurnContext turn) {
        List<String> nudges = new ArrayList<>();
        for (Hook hook : hooks) {
            if (hook instanceof PreModelHook h) {
                Optional<HookResult.Stop> stop = aggregate(hook, h.beforeModel(turn), turn, nudges);
                if (stop.isPresent()) {
                    return new HookOutcome(List.copyOf(nudges), stop);
                }
            }
        }
        return new HookOutcome(List.copyOf(nudges), Optional.empty());
    }

    public HookOutcome runPostModel(TurnContext turn) {
        List<String> nudges = new ArrayList<>();
        for (Hook hook : hooks) {
            if (hook instanceof PostModelHook h) {
                Optional<HookResult.Stop> stop = aggregate(hook, h.afterModel(turn), turn, nudges);
                if (stop.isPresent()) {
                    return new HookOutcome(List.copyOf(nudges), stop);
                }
            }
        }
        return new HookOutcome(List.copyOf(nudges), Optional.empty());
    }

    public HookOutcome runPreTool(TurnContext turn, List<ToolCall> calls) {
        List<String> nudges = new ArrayList<>();
        for (Hook hook : hooks) {
            if (hook instanceof PreToolHook h) {
                Optional<HookResult.Stop> stop = aggregate(hook, h.beforeTools(turn, calls), turn, nudges);
                if (stop.isPresent()) {
                    return new HookOutcome(List.copyOf(nudges), stop);
                }
            }
        }
        return new HookOutcome(List.copyOf(nudges), Optional.empty());
    }

    public void runPostTool(TurnContext turn, List<ToolResult> results) {
        for (Hook hook : hooks) {
            if (hook instanceof PostToolHook h) {
                h.afterTools(turn, results);
            }
        }
    }

    private Optional<HookResult.Stop> aggregate(Hook hook, HookResult result, TurnContext turn, List<String> nudges) {
        if (result instanceof HookResult.Nudge n) {
            events.publish(new EngineEvent.GuardTriggered(turn.sessionId(), hook.name(), n.message(), false));
            nudges.add(n.message());
            return Optional.empty();
        }
        if (result instanceof HookResult.Stop s) {
            events.publish(new EngineEvent.GuardTriggered(turn.sessionId(), hook.name(), s.message(), true));
            return Optional.of(s);
        }
        return Optional.empty();
    }

    public record HookOutcome(List<String> nudges, Optional<HookResult.Stop> stop) {
    }
}
