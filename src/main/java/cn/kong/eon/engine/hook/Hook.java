package cn.kong.eon.engine.hook;

/** 钩子标记接口。order 决定同阶段执行顺序（小者先）。 */
public interface Hook {

    default String name() {
        return getClass().getSimpleName();
    }

    default int order() {
        return 100;
    }
}
