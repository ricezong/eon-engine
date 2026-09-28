package cn.kong.eon.engine.context;

/**
 * 有序注入段——app 向上下文注入内容的唯一形式。
 * order ∈ [0,1000) 注入历史之前，[1000,∞) 注入历史之后，同区间内按 order 升序；
 * 渲染文本以 {@code <label>…</label>} 包裹后作为 USER 消息注入。
 */
public interface ContextSection {

    /** 稳定标识，同时用作注入标签名。 */
    String label();

    int order();

    /** null/空白 = 本轮缺省；抛异常按缺省降级，不中断循环。 */
    String render(String sessionId);
}
