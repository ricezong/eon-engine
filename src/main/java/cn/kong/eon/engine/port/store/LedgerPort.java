package cn.kong.eon.engine.port.store;

import java.util.List;

import cn.kong.eon.engine.msg.ChatMsg;

/**
 * 只追加账本端口：会话消息的持久化事实源（按会话隔离）。
 * 恢复会话 = 账本重放重建窗口 + 快照恢复状态。
 */
public interface LedgerPort {

    /** @return 该会话内递增序号，首条为 1，等于追加后的账本长度 */
    long append(String sessionId, ChatMsg msg);

    /** 重放 fromSeq（含）之后的全部记录。 */
    List<LedgerRecord> replay(String sessionId, long fromSeq);

    long size(String sessionId);

    /** 账本的定位描述，供摘要兜底文案引导模型回查原文；无文件/URL 语义的实现返回 null。 */
    default String locator(String sessionId) {
        return null;
    }

    record LedgerRecord(long seq, ChatMsg msg) {
    }
}
