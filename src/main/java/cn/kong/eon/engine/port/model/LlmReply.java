package cn.kong.eon.engine.port.model;

import java.util.List;

import cn.kong.eon.engine.msg.LlmUsage;
import cn.kong.eon.engine.msg.ToolCall;

/**
 * 模型一轮的完整回复。toolCalls 为空即循环的正常退出条件（截断除外）。
 *
 * @param finishReason 结束原因（openai 风格：stop/length/tool_calls…）。length 表示输出被截断，
 *                     循环会丢弃本轮工具调用并注入续写提示，而非当作正常完成退出。
 */
public record LlmReply(String text, String thinking, List<ToolCall> toolCalls, LlmUsage usage, String finishReason) {

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }

    public boolean truncated() {
        return "length".equalsIgnoreCase(finishReason);
    }
}
