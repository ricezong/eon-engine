package cn.kong.eon.engine.stop;

/** 运行终止分类——统一收尾流程的唯一判定依据。 */
public enum StopCategory {

    COMPLETED("正常完成"),
    MAX_TURNS("步数上限"),
    BUDGET_EXCEEDED("预算耗尽"),
    GATE_REJECTED("门禁拒绝"),
    INTERRUPTED("用户中断"),
    INTERNAL_ERROR("内部错误");

    private final String label;

    StopCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
