package cn.kong.eon.engine.port.model;

import java.util.List;

import cn.kong.eon.engine.msg.ChatMsg;

/**
 * LLM 端口。实现约定：
 * <ul>
 *   <li>finishReason 统一翻译为 openai 词表（stop / length / tool_calls…），如 anthropic 的 max_tokens → length；</li>
 *   <li>无参工具调用的 argumentsJson 产出 "{}"，不得为 null 或空串（空串无法与截断区分）；</li>
 *   <li>流式增量在回调线程上顺序回调；终点必须是 onDone 或 onError 之一。</li>
 * </ul>
 */
public interface LlmPort {

    void chat(LlmRequest request, StreamCallbacks callbacks);

    /** 同步纯文本补全，不带工具；引擎内部用途（SUMMARIZE 摘要生成）。 */
    String complete(List<ChatMsg> messages);
}
