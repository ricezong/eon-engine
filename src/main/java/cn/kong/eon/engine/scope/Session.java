package cn.kong.eon.engine.scope;

import java.util.concurrent.atomic.AtomicReference;

import cn.kong.eon.engine.window.ContextWindow;

/**
 * 会话聚合根：窗口 + 累计状态。状态迁移全部 CAS，同一会话同时至多一个 RUNNING；
 * 中断是「标记 + 状态迁移」，循环在检查点观察标志并优雅停止。
 *
 * CLOSED 是淘汰终态，仅可从 IDLE 迁入，此后 tryAcquire 必然失败、实例不可复用。
 * 淘汰只清内存，账本/快照不受影响，再次 acquire 从磁盘重建。
 */
public final class Session {

    private final String id;
    private final ContextWindow window;
    private final SessionState state = new SessionState();
    private final AtomicReference<SessionStatus> status = new AtomicReference<>(SessionStatus.IDLE);
    private volatile boolean interruptRequested;

    public Session(String id, ContextWindow window) {
        this.id = id;
        this.window = window;
    }

    // ---- 状态迁移（CAS） ----

    /** 抢占运行权：IDLE→RUNNING。 */
    public boolean tryAcquire() {
        return status.compareAndSet(SessionStatus.IDLE, SessionStatus.RUNNING);
    }

    public SessionStatus status() {
        return status.get();
    }

    /** run 结束统一回 IDLE 并清中断标志；不复活 CLOSED。 */
    public void release() {
        status.compareAndSet(SessionStatus.RUNNING, SessionStatus.IDLE);
        status.compareAndSet(SessionStatus.STOPPING, SessionStatus.IDLE);
        interruptRequested = false;
    }

    // ---- 中断 ----

    public void requestInterrupt() {
        if (status.compareAndSet(SessionStatus.RUNNING, SessionStatus.STOPPING)) {
            interruptRequested = true;
        }
    }

    public boolean isInterruptRequested() {
        return interruptRequested;
    }

    // ---- 淘汰 ----

    /**
     * 空闲时淘汰：CAS IDLE→CLOSED。与 tryAcquire 在同一状态位上互斥，故淘汰成功的实例永不再运行；
     * 运行中返回 false。
     */
    public boolean evictIfIdle() {
        return status.compareAndSet(SessionStatus.IDLE, SessionStatus.CLOSED);
    }

    // ---- 视图 ----

    public String id() {
        return id;
    }

    public ContextWindow window() {
        return window;
    }

    public SessionState state() {
        return state;
    }
}
