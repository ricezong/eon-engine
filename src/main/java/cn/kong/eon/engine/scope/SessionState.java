package cn.kong.eon.engine.scope;

import cn.kong.eon.engine.msg.LlmUsage;
import cn.kong.eon.engine.context.CompressionState;
import cn.kong.eon.engine.port.store.SnapshotPort.SessionSnapshot;

/**
 * 会话累计状态：轮次、总用量、压缩状态（随快照持久化/恢复）。
 */
public final class SessionState {

    private int turnCount;
    private LlmUsage totalUsage = LlmUsage.ZERO;
    private CompressionState compression = CompressionState.EMPTY;

    public synchronized void addUsage(LlmUsage usage) {
        totalUsage = totalUsage.plus(usage);
    }

    public synchronized void turnStarted() {
        turnCount++;
    }

    public synchronized void setCompression(CompressionState next) {
        compression = next;
    }

    public synchronized void restore(SessionSnapshot snap) {
        turnCount = snap.turnCount();
        totalUsage = snap.totalUsage();
        compression = snap.compression();
    }

    public synchronized int turnCount() {
        return turnCount;
    }

    public synchronized LlmUsage totalUsage() {
        return totalUsage;
    }

    public synchronized CompressionState compression() {
        return compression;
    }
}
