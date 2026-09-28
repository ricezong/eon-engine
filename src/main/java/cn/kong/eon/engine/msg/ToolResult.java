package cn.kong.eon.engine.msg;

/**
 * 工具执行结果。失败不等于中断：失败结果同样回填给模型，只有钩子能终止循环。
 *
 * @param output   模型可见的纯文本正文
 * @param toolView UI 结构化视图的 JSON，引擎按不透明值透传；无视图时为 null
 */
public record ToolResult(String toolCallId, String toolName, boolean success, String output, String toolView) {

    public static ToolResult success(ToolCall call, String output) {
        return new ToolResult(call.id(), call.name(), true, output, null);
    }

    public static ToolResult success(ToolCall call, String output, String toolView) {
        return new ToolResult(call.id(), call.name(), true, output, toolView);
    }

    public static ToolResult failure(ToolCall call, String reason) {
        return new ToolResult(call.id(), call.name(), false, "执行失败: " + reason, null);
    }
}
