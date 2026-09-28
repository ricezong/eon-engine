package cn.kong.eon.engine.event;

/** 事件出口端口：SSE/WebSocket/日志/回放都是这个接口的实现。 */
@FunctionalInterface
public interface EventSink {

    void onEvent(EngineEvent event);
}
