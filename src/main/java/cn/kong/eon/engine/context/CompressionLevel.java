package cn.kong.eon.engine.context;

/** 压缩档位，动作定义见 {@link Compressor}。PRUNE 覆盖 SNIP 的全部动作。 */
public enum CompressionLevel {
    NONE, SNIP, PRUNE, SUMMARIZE
}
