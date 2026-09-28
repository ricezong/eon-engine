package cn.kong.eon.engine.hook;

import cn.kong.eon.engine.stop.StopCategory;

/** 钩子结论：放行 / 温和提醒（注入下轮上下文） / 终止。 */
public sealed interface HookResult permits HookResult.Ok, HookResult.Nudge, HookResult.Stop {

    record Ok() implements HookResult {
    }

    record Nudge(String message) implements HookResult {
    }

    record Stop(StopCategory category, String message) implements HookResult {
    }

    static HookResult ok() {
        return new Ok();
    }

    static HookResult nudge(String message) {
        return new Nudge(message);
    }

    static HookResult stop(StopCategory category, String message) {
        return new Stop(category, message);
    }
}
