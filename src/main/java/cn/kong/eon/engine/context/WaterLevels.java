package cn.kong.eon.engine.context;

/** 水位判档（纯函数）。三档动作定义见 {@link Compressor}。 */
public final class WaterLevels {

    private final double snipWaterLevel;
    private final double pruneWaterLevel;
    private final double summarizeWaterLevel;

    public WaterLevels(double snipWaterLevel, double pruneWaterLevel, double summarizeWaterLevel) {
        this.snipWaterLevel = snipWaterLevel;
        this.pruneWaterLevel = pruneWaterLevel;
        this.summarizeWaterLevel = summarizeWaterLevel;
    }

    public CompressionLevel judge(double water) {
        if (water >= summarizeWaterLevel) {
            return CompressionLevel.SUMMARIZE;
        }
        if (water >= pruneWaterLevel) {
            return CompressionLevel.PRUNE;
        }
        if (water >= snipWaterLevel) {
            return CompressionLevel.SNIP;
        }
        return CompressionLevel.NONE;
    }
}
