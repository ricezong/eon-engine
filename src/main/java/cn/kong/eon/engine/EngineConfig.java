package cn.kong.eon.engine;

import java.time.Duration;

/**
 * 引擎配置（不可变）。只放引擎行为参数；模型参数（temperature 等）属于 LLM 适配器自身。
 */
public final class EngineConfig {

    // ---- 循环 ----（默认值见 Builder）
    private final int maxTurns;                 // 单次 run 最大轮次
    private final int toolParallelism;          // 工具池并行度
    private final Duration toolTimeout;         // 单个工具执行上限
    private final boolean autoApproveDestructive; // 破坏性工具免门禁

    // ---- 上下文 ----
    private final String systemPrompt;          // 系统提示词
    private final long maxContextTokens;        // 上下文预算，水位判档的分母
    private final int tailGuardBlocks;          // 尾部保护块数，压缩不动
    private final int thinkingReplayTurns;      // 保留思维链的最近轮数，更早的从窗口清除
    private final int spillThresholdChars;      // 工具结果溢出阈值
    private final int spillKeepChars;           // 溢出后头尾各保留字符数
    private final double snipWaterLevel;        // SNIP 档水位下限
    private final double pruneWaterLevel;       // PRUNE 档水位下限
    private final double summarizeWaterLevel;   // SUMMARIZE 档水位下限
    private final int snipKeepChars;            // SNIP/PRUNE 截断后头尾各保留字符数
    private final int argsPruneMinChars;        // 参数整体超过该长度才骨架化
    private final int summarizeMaxInputChars;   // 单段摘要输入上限，超出分段
    private final int summarizeMaxOutputChars;  // 摘要输出上限，写入提示词
    private final Duration sessionIdleTtl;      // 会话缓存闲置 TTL
    private final int sessionCacheMaxSize;      // 会话缓存容量上限，0 = 不限

    private EngineConfig(Builder b) {
        this.maxTurns = b.maxTurns;
        this.toolParallelism = b.toolParallelism;
        this.toolTimeout = b.toolTimeout;
        this.autoApproveDestructive = b.autoApproveDestructive;
        this.systemPrompt = b.systemPrompt;
        this.maxContextTokens = b.maxContextTokens;
        this.tailGuardBlocks = b.tailGuardBlocks;
        this.thinkingReplayTurns = b.thinkingReplayTurns;
        this.spillThresholdChars = b.spillThresholdChars;
        this.spillKeepChars = b.spillKeepChars;
        this.snipWaterLevel = b.snipWaterLevel;
        this.pruneWaterLevel = b.pruneWaterLevel;
        this.summarizeWaterLevel = b.summarizeWaterLevel;
        this.snipKeepChars = b.snipKeepChars;
        this.argsPruneMinChars = b.argsPruneMinChars;
        this.summarizeMaxInputChars = b.summarizeMaxInputChars;
        this.summarizeMaxOutputChars = b.summarizeMaxOutputChars;
        this.sessionIdleTtl = b.sessionIdleTtl;
        this.sessionCacheMaxSize = b.sessionCacheMaxSize;
    }

    public static EngineConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxTurns() {
        return maxTurns;
    }

    public int toolParallelism() {
        return toolParallelism;
    }

    public Duration toolTimeout() {
        return toolTimeout;
    }

    public boolean autoApproveDestructive() {
        return autoApproveDestructive;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public long maxContextTokens() {
        return maxContextTokens;
    }

    public int tailGuardBlocks() {
        return tailGuardBlocks;
    }

    public int thinkingReplayTurns() {
        return thinkingReplayTurns;
    }

    public int spillThresholdChars() {
        return spillThresholdChars;
    }

    public int spillKeepChars() {
        return spillKeepChars;
    }

    public double snipWaterLevel() {
        return snipWaterLevel;
    }

    public double pruneWaterLevel() {
        return pruneWaterLevel;
    }

    public double summarizeWaterLevel() {
        return summarizeWaterLevel;
    }

    public int snipKeepChars() {
        return snipKeepChars;
    }

    public int argsPruneMinChars() {
        return argsPruneMinChars;
    }

    public int summarizeMaxInputChars() {
        return summarizeMaxInputChars;
    }

    public int summarizeMaxOutputChars() {
        return summarizeMaxOutputChars;
    }

    public Duration sessionIdleTtl() {
        return sessionIdleTtl;
    }

    public int sessionCacheMaxSize() {
        return sessionCacheMaxSize;
    }

    public static final class Builder {

        private int maxTurns = 30;
        private int toolParallelism = 4;
        private Duration toolTimeout = Duration.ofSeconds(30);
        private boolean autoApproveDestructive = false;
        private String systemPrompt = "你是一个能调用工具的助手，按需使用工具完成任务。";
        private long maxContextTokens = 200_000;
        private int tailGuardBlocks = 12;
        private int thinkingReplayTurns = 2;
        private int spillThresholdChars = 8_000;
        private int spillKeepChars = 2_000;
        private double snipWaterLevel = 0.65;
        private double pruneWaterLevel = 0.80;
        private double summarizeWaterLevel = 0.92;
        private int snipKeepChars = 1_000;
        private int argsPruneMinChars = 2_000;
        private int summarizeMaxInputChars = 80_000;
        private int summarizeMaxOutputChars = 12_000;
        private Duration sessionIdleTtl = Duration.ofMinutes(120);
        private int sessionCacheMaxSize = 0;

        public Builder maxTurns(int v) {
            this.maxTurns = v;
            return this;
        }

        public Builder toolParallelism(int v) {
            this.toolParallelism = v;
            return this;
        }

        public Builder toolTimeout(Duration v) {
            this.toolTimeout = v;
            return this;
        }

        public Builder autoApproveDestructive(boolean v) {
            this.autoApproveDestructive = v;
            return this;
        }

        public Builder systemPrompt(String v) {
            this.systemPrompt = v;
            return this;
        }

        public Builder maxContextTokens(long v) {
            this.maxContextTokens = v;
            return this;
        }

        public Builder tailGuardBlocks(int v) {
            this.tailGuardBlocks = v;
            return this;
        }

        public Builder thinkingReplayTurns(int v) {
            this.thinkingReplayTurns = v;
            return this;
        }

        public Builder spillThresholdChars(int v) {
            this.spillThresholdChars = v;
            return this;
        }

        public Builder spillKeepChars(int v) {
            this.spillKeepChars = v;
            return this;
        }

        public Builder snipWaterLevel(double v) {
            this.snipWaterLevel = v;
            return this;
        }

        public Builder pruneWaterLevel(double v) {
            this.pruneWaterLevel = v;
            return this;
        }

        public Builder summarizeWaterLevel(double v) {
            this.summarizeWaterLevel = v;
            return this;
        }

        public Builder snipKeepChars(int v) {
            this.snipKeepChars = v;
            return this;
        }

        public Builder argsPruneMinChars(int v) {
            this.argsPruneMinChars = v;
            return this;
        }

        public Builder summarizeMaxInputChars(int v) {
            this.summarizeMaxInputChars = v;
            return this;
        }

        public Builder summarizeMaxOutputChars(int v) {
            this.summarizeMaxOutputChars = v;
            return this;
        }

        public Builder sessionIdleTtl(Duration v) {
            this.sessionIdleTtl = v;
            return this;
        }

        public Builder sessionCacheMaxSize(int v) {
            this.sessionCacheMaxSize = v;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(this);
        }
    }
}
