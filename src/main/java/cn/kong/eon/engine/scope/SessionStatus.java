package cn.kong.eon.engine.scope;

/** 会话状态机：IDLE → RUNNING → IDLE；中断经 STOPPING 收尾；淘汰经 CLOSED 终结（不可逆）。 */
public enum SessionStatus {
    IDLE,       // 空闲
    RUNNING,    // 运行
    STOPPING,   // 停止
    CLOSED      // 关闭
}
