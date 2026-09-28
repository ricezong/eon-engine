package cn.kong.eon.engine.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事件发布器。派发契约（订阅者必须遵守）：
 * <ul>
 *   <li>同步、按发生顺序、在发布线程上派发——部分订阅者功能性依赖该时序，故不做异步多播</li>
 *   <li>订阅者不得阻塞、不得执行慢 IO；传输类订阅者须在内部线程上出舱发送</li>
 *   <li>订阅者异常被隔离（记日志，不中断执行）</li>
 * </ul>
 */
public final class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();

    public void subscribe(EventSink sink) {
        sinks.add(sink);
    }

    public void unsubscribe(EventSink sink) {
        sinks.remove(sink);
    }

    public void publish(EngineEvent event) {
        for (EventSink sink : sinks) {
            try {
                sink.onEvent(event);
            } catch (Exception e) {
                log.warn("[Event] 订阅者异常 sink={}, event={}", sink.getClass().getName(), event.getClass().getSimpleName(), e);
            }
        }
    }
}
