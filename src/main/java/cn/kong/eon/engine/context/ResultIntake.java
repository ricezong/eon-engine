package cn.kong.eon.engine.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.kong.eon.engine.EngineConfig;
import cn.kong.eon.engine.msg.ToolResult;
import cn.kong.eon.engine.port.store.ToolResultPort;
import cn.kong.eon.engine.port.store.ToolResultPort.ToolResultRef;

/**
 * 入账关卡：工具结果进入账本前的体积关卡。担保单条 TOOL 正文 ≤ spillThresholdChars，
 * 唯一例外是工件落盘失败时保留原文入窗。
 */
public final class ResultIntake {

    private static final Logger log = LoggerFactory.getLogger(ResultIntake.class);

    /** 溢出引用标记的固定文案；PRUNE 按它识别「已落盘」并提取 refId。 */
    public static final String REF_HINT = "完整内容见 tool_result://";

    private static final String SEP = "\n…\n";

    private final ToolResultPort toolResult;
    private final EngineConfig config;

    public ResultIntake(ToolResultPort toolResult, EngineConfig config) {
        this.toolResult = toolResult;
        this.config = config;
    }

    /** 超限时返回「头 + 分隔 + 尾 + 引用标记」，否则原样返回。 */
    public String admit(String sessionId, ToolResult r, long seq) {
        String output = r.output() == null ? "" : r.output();
        if (output.length() <= config.spillThresholdChars()) {
            return output;
        }
        ToolResultRef ref;
        try {
            ref = toolResult.spill(sessionId, r.toolName(), output, seq);
        } catch (Exception e) {
            log.warn("[ResultIntake] 工件落盘失败，保留原文入窗 session={}, tool={}", sessionId, r.toolName(), e);
            return output;
        }
        log.info("[ResultIntake] 工具结果溢出落盘 session={}, tool={}, chars={}, ref={}", sessionId, r.toolName(), output.length(), ref.refId());
        int keep = config.spillKeepChars();
        String head = output.substring(0, Math.min(keep, output.length()));
        String tail = output.substring(output.length() - keep);
        return head + SEP + tail + "\n[" + REF_HINT + ref.refId() + "]";
    }
}
