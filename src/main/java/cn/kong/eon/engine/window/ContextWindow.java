package cn.kong.eon.engine.window;

import java.util.ArrayList;
import java.util.List;

import cn.kong.eon.engine.msg.ChatMsg;
import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.TokenEstimator;

/**
 * 上下文窗口：会话历史的内存结构，压缩直接作用在这里。
 * 写入三类：追加、就地改写、摘要式回收。
 *
 * 账本才是真相源：窗口被压缩掉的内容仍可从账本重放，故淘汰重建后窗口回到未压缩态。
 */
public final class ContextWindow {

    private final List<Block> blocks = new ArrayList<>();
    private final TokenEstimator estimator;
    private final int tailGuardBlocks;

    public ContextWindow(TokenEstimator estimator, int tailGuardBlocks) {
        this.estimator = estimator;
        this.tailGuardBlocks = Math.max(0, tailGuardBlocks);
    }

    // ---- 追加 ----

    public void addUser(long messageSeq, String text) {
        blocks.add(Block.user(messageSeq, text));
    }

    public void addAssistant(long messageSeq, String text, String thinking, List<ToolCall> toolCalls) {
        blocks.add(Block.assistant(messageSeq, text, thinking, toolCalls));
    }

    public void addToolResult(long messageSeq, String toolCallId, String toolName, String text, String toolView) {
        blocks.add(Block.toolResult(messageSeq, toolCallId, toolName, text, toolView));
    }

    /** 账本重放；SYSTEM 不进窗口。 */
    public void addFromMessage(long messageSeq, ChatMsg msg) {
        switch (msg.role()) {
            case USER -> addUser(messageSeq, msg.text());
            case ASSISTANT -> addAssistant(messageSeq, msg.text(), msg.thinking(), msg.toolCalls());
            case TOOL -> addToolResult(messageSeq, msg.toolCallId(), msg.toolName(), msg.text(), msg.toolView());
            default -> { /* SYSTEM 忽略 */ }
        }
    }

    // ---- 读取 ----

    public List<Block> blocks() {
        return List.copyOf(blocks);
    }

    public int size() {
        return blocks.size();
    }

    // ---- 就地改写 ----

    /** 保护区起始下标：该下标之前的块可被压缩，之后的不可（思维链清除除外）。 */
    public int protectedFrom() {
        return Math.max(0, blocks.size() - tailGuardBlocks);
    }

    public Block blockAt(int index) {
        return blocks.get(index);
    }

    public void replace(int index, Block block) {
        blocks.set(index, block);
    }

    // ---- 回收 ----

    /**
     * 移除 messageSeq 早于 seq 的块（已被摘要代表）；保护区内的块永不移动。
     *
     * @return 移除块的粗估 token
     */
    public long removeBeforeSeq(long seq) {
        long freed = 0;
        for (int i = protectedFrom() - 1; i >= 0; i--) {
            Block b = blocks.get(i);
            if (b.messageSeq() < seq) {
                freed += estimator.estimate(textOf(b)); // 回收量按存储全文计
                blocks.remove(i);
            }
        }
        return freed;
    }

    // ---- 内部 ----

    private static String textOf(Block b) {
        StringBuilder sb = new StringBuilder();
        if (b.text() != null) {
            sb.append(b.text());
        }
        if (b.thinking() != null) {
            sb.append(b.thinking());
        }
        for (ToolCall c : b.toolCalls()) {
            sb.append(c.name()).append(c.argumentsJson() == null ? "" : c.argumentsJson());
        }
        return sb.toString();
    }
}
