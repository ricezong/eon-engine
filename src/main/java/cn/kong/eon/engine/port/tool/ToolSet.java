package cn.kong.eon.engine.port.tool;

import java.util.List;
import java.util.Optional;

import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolResult;
import cn.kong.eon.engine.msg.ToolSpec;

/** 工具集合端口。统一执行入口保证：未知工具、实现异常都转为 failure 结果回填给模型，不中断循环。 */
public interface ToolSet {

    List<ToolSpec> specs();

    Optional<Tool> find(String name);

    default boolean isDestructive(String toolName) {
        return find(toolName).map(t -> t.spec().destructive()).orElse(false);
    }

    default ToolResult execute(ToolCall call, ToolContext ctx) {
        Optional<Tool> found = find(call.name());
        if (found.isEmpty()) {
            return ToolResult.failure(call, "未知工具: " + call.name());
        }
        try {
            return found.get().execute(call, ctx);
        } catch (Exception e) {
            return ToolResult.failure(call, "工具执行异常: " + e);
        }
    }
}
