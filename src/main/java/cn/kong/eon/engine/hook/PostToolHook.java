package cn.kong.eon.engine.hook;

import java.util.List;

import cn.kong.eon.engine.loop.TurnContext;
import cn.kong.eon.engine.msg.ToolResult;

/** 工具执行后。纯观察，不能终止循环。 */
public interface PostToolHook extends Hook {

    void afterTools(TurnContext turn, List<ToolResult> results);
}
