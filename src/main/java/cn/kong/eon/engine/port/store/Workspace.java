package cn.kong.eon.engine.port.store;

import java.nio.file.Path;

/**
 * 文件工作区端口：工具文件操作的读写分区契约。实现按 sessionId 路由到会话目录，单例即可。
 *
 * 读区 = 会话目录整体（sessionRoot 为上限）；
 * 写区 = 实现约定的可写子区，写类工具一律走 resolveWritable，无法绕过。
 */
public interface Workspace {

    Path sessionRoot(String sessionId);

    /** 越界路径抛 IllegalArgumentException。 */
    Path resolve(String sessionId, String relativePath);

    /** 越界路径抛 IllegalArgumentException。 */
    Path resolveWritable(String sessionId, String relativePath);

    /** 正斜杠；会话目录外退化为文件名。 */
    String relativize(String sessionId, Path absolute);
}
