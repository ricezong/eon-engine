package cn.kong.eon.engine.context;

/**
 * 压缩状态（会话级，随快照持久化）。
 *
 * @param summarizedThroughSeq 已被摘要覆盖的账本序号上界（含），重放从其后一位开始；
 * @param summarizeCount       累计摘要次数
 */
public record CompressionState(String lastSummary, long summarizedThroughSeq, int summarizeCount) {

    public static final CompressionState EMPTY = new CompressionState(null, 0, 0);

    public boolean hasSummary() {
        return lastSummary != null && !lastSummary.isBlank();
    }

    /** 摘要与水位是否同真同假、且水位未越过账本长度。 */
    public boolean consistentWith(long ledgerSize) {
        return (summarizedThroughSeq > 0) == hasSummary() && summarizedThroughSeq <= ledgerSize;
    }
}
