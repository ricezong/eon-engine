package cn.kong.eon.engine.event;

import java.util.List;

import cn.kong.eon.engine.context.CompressionLevel;
import cn.kong.eon.engine.msg.LlmUsage;
import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolResult;
import cn.kong.eon.engine.stop.StopCategory;

/** 引擎事件——内核的可观测性出口（只读流），按发生顺序同步派发。分层：运行 / 透传 / 工具 / 治理 / 扩展 / 收尾。 */
public sealed interface EngineEvent permits
        EngineEvent.RunStarted,
        EngineEvent.UserMessage,
        EngineEvent.TurnStarted,
        EngineEvent.TurnFinished,
        EngineEvent.ModelDelta,
        EngineEvent.ToolExecutionStarted,
        EngineEvent.ToolExecutionFinished,
        EngineEvent.GuardTriggered,
        EngineEvent.CompressionApplied,
        EngineEvent.SessionStateUpdated,
        EngineEvent.RunFinished {

    String sessionId();

    // ---- 运行层 ----

    record RunStarted(String sessionId) implements EngineEvent {
    }

    record UserMessage(String sessionId, String text) implements EngineEvent {
    }

    record TurnStarted(String sessionId, int turn) implements EngineEvent {
    }

    /** 轮次权威记录：提交关卡之后的最终形态，落盘即发布。 */
    record TurnFinished(String sessionId, int turn, String text, String thinking,
                        List<ToolCall> toolCalls, String finishReason, LlmUsage usage)
            implements EngineEvent {

        public TurnFinished {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    // ---- 透传层（LlmPort 流式增量） ----

    /** 预览帧，非权威记录；权威形态见 TurnFinished。toolCallId 仅 TOOL_ARGS 非空。 */
    record ModelDelta(String sessionId, int turn, Kind kind, String toolCallId, String delta)
            implements EngineEvent {

        public enum Kind { TEXT, THINKING, TOOL_ARGS }
    }

    // ---- 工具层 ----

    record ToolExecutionStarted(String sessionId, String toolCallId, String toolName) implements EngineEvent {
    }

    record ToolExecutionFinished(String sessionId, ToolResult result) implements EngineEvent {
    }

    // ---- 治理层（钩子/压缩） ----

    record GuardTriggered(String sessionId, String hook, String message, boolean stopped) implements EngineEvent {
    }

    /** 压缩净释放 = beforeTokens - afterTokens，与判档同口径。 */
    record CompressionApplied(String sessionId, CompressionLevel level,
                              long beforeTokens, long afterTokens) implements EngineEvent {
    }

    // ---- 扩展层（app） ----

    /** app 级会话状态变更通知（如待办、用户画像）。引擎不解释其内容，viewJson 按不透明值透传；
     * 仅由 app 工具经 ToolContext.events() 发布。 */
    record SessionStateUpdated(String sessionId, String key, String viewJson) implements EngineEvent {
    }

    // ---- 收尾 ----

    record RunFinished(String sessionId, StopCategory category, String message, LlmUsage totalUsage) implements EngineEvent {
    }
}
