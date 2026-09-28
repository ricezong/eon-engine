package cn.kong.eon.engine.port.store;

import java.time.Instant;
import java.util.Optional;

import cn.kong.eon.engine.msg.LlmUsage;
import cn.kong.eon.engine.context.CompressionState;

/** 终态快照端口：保存/恢复会话级状态（累计用量、压缩状态、轮次）。 */
public interface SnapshotPort {

    void save(SessionSnapshot snapshot);

    SessionSnapshot load(String sessionId);

    record SessionSnapshot(
            String sessionId,
            LlmUsage totalUsage,
            CompressionState compression,
            int turnCount,
            Instant savedAt) {
    }
}
