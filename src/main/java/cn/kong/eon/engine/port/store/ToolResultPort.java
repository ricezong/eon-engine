package cn.kong.eon.engine.port.store;

/** 工件端口：工具超大结果的落盘与读回。 */
public interface ToolResultPort {

    /** seq 用于生成跨重放稳定的引用 ID。 */
    ToolResultRef spill(String sessionId, String toolName, String content, long seq);

    String read(String sessionId, String refId, int offset, int limit);

    record ToolResultRef(String refId, int totalLines) {
    }
}
