package cn.kong.eon.engine.port.tool;

import java.time.Duration;

import cn.kong.eon.engine.msg.ToolCall;
import cn.kong.eon.engine.msg.ToolResult;
import cn.kong.eon.engine.msg.ToolSpec;

/** 单个工具。参数以原始 JSON 字符串传入，内核不解析。 */
public interface Tool {

    ToolSpec spec();

    /** 实现应返回 failure 结果表达失败，而非向外抛异常。 */
    ToolResult execute(ToolCall call, ToolContext ctx);

    /** 在 run 线程上内联执行：不进工具池、不受超时约束。供阻塞等人回答与顺序敏感的工具使用。 */
    default boolean inline() {
        return false;
    }

    /** 本工具的执行上限。内联工具不受此约束。 */
    default Duration timeout(Duration engineDefault) {
        return engineDefault;
    }
}
