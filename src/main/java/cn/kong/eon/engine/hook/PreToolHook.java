package cn.kong.eon.engine.hook;

import java.util.List;

import cn.kong.eon.engine.loop.TurnContext;
import cn.kong.eon.engine.msg.ToolCall;

public interface PreToolHook extends Hook {

    HookResult beforeTools(TurnContext turn, List<ToolCall> calls);
}
