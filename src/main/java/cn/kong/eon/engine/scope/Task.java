package cn.kong.eon.engine.scope;

import java.time.Instant;

/** 一次运行：用户输入驱动的一轮完整 agent 过程（多轮模型+工具）。 */
public record Task(String input, Instant startedAt) {

    public static Task of(String input) {
        return new Task(input, Instant.now());
    }
}
