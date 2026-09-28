package cn.kong.eon.engine.port.model;

/** LLM 适配器最终失败（重试耗尽/协议错误/未回调终点）。 */
public class LlmPortException extends RuntimeException {

    public LlmPortException(String message) {
        super(message);
    }

    public LlmPortException(String message, Throwable cause) {
        super(message, cause);
    }
}
