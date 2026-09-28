package cn.kong.eon.engine.window;

import java.util.List;

import cn.kong.eon.engine.msg.ToolCall;

/**
 * 窗口块：一段不可变的会话历史。
 */
public record Block(
        long messageSeq,    // 来源消息在账本中的序号，压缩水位的坐标系
        BlockKind kind,
        String text,
        String thinking,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName,
        String toolView) {

    public Block {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Block user(long messageSeq, String text) {
        return new Block(messageSeq, BlockKind.USER, text, null, null, null, null, null);
    }

    public static Block assistant(long messageSeq, String text, String thinking, List<ToolCall> toolCalls) {
        return new Block(messageSeq, BlockKind.ASSISTANT, text, thinking, toolCalls, null, null, null);
    }

    public static Block toolResult(long messageSeq, String toolCallId, String toolName, String text, String toolView) {
        return new Block(messageSeq, BlockKind.TOOL_RESULT, text, null, null, toolCallId, toolName, toolView);
    }

    public Block withText(String newText) {
        return new Block(messageSeq, kind, newText, thinking, toolCalls, toolCallId, toolName, toolView);
    }

    public Block withThinking(String newThinking) {
        return new Block(messageSeq, kind, text, newThinking, toolCalls, toolCallId, toolName, toolView);
    }

    public Block withToolCalls(List<ToolCall> newToolCalls) {
        return new Block(messageSeq, kind, text, thinking, newToolCalls, toolCallId, toolName, toolView);
    }
}
