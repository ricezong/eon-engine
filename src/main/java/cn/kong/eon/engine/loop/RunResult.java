package cn.kong.eon.engine.loop;

import cn.kong.eon.engine.msg.LlmUsage;
import cn.kong.eon.engine.stop.StopCategory;

public record RunResult(StopCategory category, String message, LlmUsage totalUsage) {
}
